package com.sindercube.serverUnpacker.util;

import java.io.*;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * PackExtractor：安全的 zip 解压工具，包含：
 * - 防止 Zip Slip（路径穿越）
 * - 在路径超长时尽可能缩短路径（移除单字母目录、截断组件、使用中间哈希）
 * - 避免在路径过长时调用 getCanonicalPath() 导致的 IOException（改为使用 Path.resolve(...).normalize() 做语法规范化与判断）
 */
public class PackExtractor {

	public static final PackExtractor INSTANCE = new PackExtractor();

	private static final int MAX_PATH_LENGTH = 260;

	public void extractPack(Path destination, File pack, String name) {
		extractPack(destination, pack, name, c -> {}, () -> {});
	}

	public void extractPack(Path destination, File pack, String name, LongConsumer itemCountConsumer, Runnable onItemFinished) {
		File destRoot = destination.resolve(name).toFile();
		try (ZipFile zip = new ZipFile(pack)) {
			itemCountConsumer.accept(zip.size());

			Enumeration<? extends ZipEntry> entries = zip.entries();
			Path destRootPath = destRoot.toPath().toAbsolutePath().normalize();

			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String rawEntryName = entry.getName();

				// 规范化 name：去掉前导 "./", 前导 "/"，合并重复分隔符，并移除末尾的 '/'
				String entryName = normalizeEntryName(rawEntryName);

				if (entryName == null || entryName.isEmpty()) {
					continue;
				}

				// --- 关键：先判断是否为目录（在任何缩短逻辑之前） ---
				boolean entryIsDirectory = entry.isDirectory()
						|| rawEntryName.endsWith("/")
						|| rawEntryName.endsWith("\\");

				// 如果不是显式目录，则检查是否存在以该条目为前缀的其它条目（说明它应当是目录）
				// 但为了避免把像 pack.mcmeta / pack.png 这种带扩展名的文件误判为目录，
				// 只有在最后组件没有文件扩展名时才把它视为目录。
				if (!entryIsDirectory) {
					if (zipContainsDirPrefix(zip, entryName) && !hasFileExtension(entryName)) {
						entryIsDirectory = true;
					}
				}

				if (entryIsDirectory) {
					Path dirPath = destRootPath.resolve(entryName.replace('/', File.separatorChar)).normalize();
					File dir = dirPath.toFile();
					if (!dir.exists() && !dir.mkdirs()) {
						throw new IOException("Failed to create directory: " + dir);
					}
					onItemFinished.run();
					continue;
				}

				// --- 此时确定是文件（file） ---
				// 对 entryName 做安全清理（但不要在这里移除单字母目录以免改变目录检测）
				String adjustedEntryName = sanitizeEntryName(entryName);

				// 构建 candidate，并在过长时尝试缩短（缩短只在文件写入路径时应用）
				Path candidateResolved = destRootPath.resolve(adjustedEntryName.replace('/', File.separatorChar)).normalize().toAbsolutePath();
				String candidateResolvedStr = candidateResolved.toString();

				if (candidateResolvedStr.length() > MAX_PATH_LENGTH) {
					String shortened = shortenPathMoreAggressively(adjustedEntryName, destRootPath, MAX_PATH_LENGTH);
					if (shortened == null) {
						System.err.println("Skipped entry due to excessive path length and cannot shorten: " + entryName);
						continue;
					}
					adjustedEntryName = shortened;
					candidateResolved = destRootPath.resolve(adjustedEntryName.replace('/', File.separatorChar)).normalize().toAbsolutePath();
					candidateResolvedStr = candidateResolved.toString();
				}

				// Zip Slip 检查
				if (!candidateResolved.startsWith(destRootPath)) {
					System.err.println("Skipped entry due to zip-slip attempt: " + entryName + " -> " + adjustedEntryName);
					continue;
				}

				// 最后确认长度
				if (candidateResolvedStr.length() > MAX_PATH_LENGTH) {
					System.err.println("Skipped entry because adjusted path still too long: " + adjustedEntryName);
					continue;
				}

				File adjustedFile = candidateResolved.toFile();
				File parent = adjustedFile.getParentFile();
				if (parent != null && !parent.exists()) {
					if (!parent.mkdirs() && !parent.exists()) {
						throw new IOException("Failed to create parent directories for: " + adjustedFile);
					}
				}

				try (BufferedInputStream inputStream = new BufferedInputStream(zip.getInputStream(entry))) {
					this.writeFile(inputStream, adjustedFile);
				}

				onItemFinished.run();
			}
		} catch (Exception exception) {
			throw new RuntimeException(exception);
		}
	}

	// 判断最后一部分是否看起来像有文件扩展名（简单检查 '.' 且非以 '.' 开头）
	private boolean hasFileExtension(String entryName) {
		if (entryName == null || entryName.isEmpty()) return false;
		// 去掉可能的尾部分隔符
		String n = entryName;
		while (n.endsWith("/") || n.endsWith("\\")) n = n.substring(0, n.length() - 1);
		int idx = n.lastIndexOf('/');
		String last = idx >= 0 ? n.substring(idx + 1) : n;
		int dot = last.lastIndexOf('.');
		return dot > 0 && dot < last.length() - 1;
	}

	/**
	 * 规范化 zip 条目名以便比较：删除前导 "./" 和前导 "/", 合并重复分隔符并移除末尾的 '/'
	 */
	private String normalizeEntryName(String entryName) {
		if (entryName == null) return null;
		String n = entryName.replaceAll("^\\./+", "")
				.replaceAll("^/+", "")
				.replaceAll("/{2,}", "/");
		while (n.endsWith("/")) n = n.substring(0, n.length() - 1);
		return n;
	}

	/**
	 * 去掉前后/重复/空组件等，避免出现异常组件（例如 "."、".."、空组件）
	 */
	private String sanitizeEntryName(String entryName) {
		String[] rawParts = entryName.split("/");
		List<String> parts = new ArrayList<>();
		for (int i = 0; i < rawParts.length; i++) {
			String p = rawParts[i];
			if (p == null || p.isEmpty()) continue;
			if (p.equals(".") || p.equals("..")) continue;
			parts.add(p);
		}
		if (parts.isEmpty()) return entryName.replaceAll("^/+", "");
		String last = parts.get(parts.size() - 1);
		if (last.isEmpty()) {
			parts.set(parts.size() - 1, "file");
		}
		return String.join("/", parts);
	}

	/**
	 * 更激进的缩短流程（仅用于“文件”路径）：
	 * 1) 移除所有非最后组件的单字母目录
	 * 2) 逐步截断组件（保留文件扩展）
	 * 3) 使用中间哈希（保留首尾）替换中间组件
	 */
	private String shortenPathMoreAggressively(String entryName, Path destRootPath, int maxLen) {
		try {
			String[] rawParts = entryName.split("/");
			List<String> parts = new ArrayList<>();
			for (String p : rawParts) {
				if (p == null || p.isEmpty()) continue;
				if (p.equals(".") || p.equals("..")) continue;
				parts.add(p);
			}
			int n = parts.size();
			if (n == 0) return null;
			if (n == 1) {
				String single = truncateFilename(parts.get(0), destRootPath, maxLen, null);
				return single == null ? null : single;
			}

			// 1) 移除所有单字母目录（不包含最后一部分）
			List<String> partsNoSingles = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				String p = parts.get(i);
				if (i != n - 1 && p.length() == 1) continue;
				partsNoSingles.add(p);
			}
			String candidate1 = String.join("/", partsNoSingles);
			Path p1 = destRootPath.resolve(candidate1.replace('/', File.separatorChar)).normalize().toAbsolutePath();
			if (p1.toString().length() <= maxLen) {
				System.err.println("Removed single-letter dirs: " + entryName + " -> " + candidate1);
				return candidate1;
			}

			// 2) 逐步截断每个组件（从 8 到 1）
			for (int compLen = 8; compLen >= 1; compLen--) {
				List<String> truncated = new ArrayList<>();
				for (int i = 0; i < partsNoSingles.size(); i++) {
					String comp = partsNoSingles.get(i);
					if (i == partsNoSingles.size() - 1) {
						truncated.add(truncateFilename(comp, destRootPath, maxLen, compLen));
					} else {
						truncated.add(truncateComponent(comp, compLen));
					}
				}
				boolean anyNull = false;
				for (String t : truncated) {
					if (t == null) { anyNull = true; break; }
				}
				if (anyNull) continue;
				String candidate = String.join("/", truncated);
				Path cp = destRootPath.resolve(candidate.replace('/', File.separatorChar)).normalize().toAbsolutePath();
				if (cp.toString().length() <= maxLen) {
					System.err.println("Truncated components to avoid long path: " + entryName + " -> " + candidate);
					return candidate;
				}
			}

			// 3) 使用中间哈希
			if (partsNoSingles.size() >= 2) {
				String first = partsNoSingles.get(0);
				String last = partsNoSingles.get(partsNoSingles.size() - 1);
				String middleJoined = "";
				if (partsNoSingles.size() > 2) {
					StringBuilder sb = new StringBuilder();
					for (int i = 1; i < partsNoSingles.size() - 1; i++) {
						if (sb.length() > 0) sb.append("/");
						sb.append(partsNoSingles.get(i));
					}
					middleJoined = sb.toString();
				}
				String hash = hashToHex(middleJoined);
				String candidate = first + "/" + hash + "/" + last;
				Path cp = destRootPath.resolve(candidate.replace('/', File.separatorChar)).normalize().toAbsolutePath();
				if (cp.toString().length() <= maxLen) {
					System.err.println("Replaced middle with hash to avoid long path: " + entryName + " -> " + candidate);
					return candidate;
				}
				String firstShort = truncateComponent(first, 4);
				String lastShort = truncateFilename(last, destRootPath, maxLen, 4);
				if (firstShort != null && lastShort != null) {
					String candidate2 = firstShort + "/" + hash + "/" + lastShort;
					Path cp2 = destRootPath.resolve(candidate2.replace('/', File.separatorChar)).normalize().toAbsolutePath();
					if (cp2.toString().length() <= maxLen) {
						System.err.println("Replaced middle with hash and truncated ends: " + entryName + " -> " + candidate2);
						return candidate2;
					}
				}
			}

			return null;
		} catch (Exception e) {
			return null;
		}
	}

	private String truncateComponent(String comp, int len) {
		if (comp == null) return comp;
		if (comp.length() <= len) return comp;
		return comp.substring(0, Math.max(1, len));
	}

	private String truncateFilename(String filename, Path destRootPath, int maxLen, Integer compLen) {
		if (filename == null) return null;
		int idx = filename.lastIndexOf('.');
		String namePart = idx >= 0 ? filename.substring(0, idx) : filename;
		String extPart = idx >= 0 ? filename.substring(idx) : "";

		if (compLen != null && compLen > 0) {
			if (namePart.length() > compLen) {
				namePart = namePart.substring(0, Math.max(1, compLen));
			}
		}

		String candidate = namePart + extPart;
		if (namePart.isEmpty()) candidate = "file" + extPart;
		return candidate;
	}

	private String hashToHex(String s) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			byte[] b = md.digest(s == null ? new byte[0] : s.getBytes("UTF-8"));
			int len = Math.min(8, b.length);
			StringBuilder sb = new StringBuilder(len * 2);
			for (int i = 0; i < len; i++) {
				sb.append(String.format("%02x", b[i]));
			}
			return sb.toString();
		} catch (Exception e) {
			return Integer.toHexString(s == null ? 0 : s.hashCode());
		}
	}

	private boolean zipContainsDirPrefix(ZipFile zip, String name) {
		try {
			if (name == null) return false;
			String normName = normalizeEntryName(name);
			String prefix = normName.isEmpty() ? "" : (normName + "/");
			Enumeration<? extends ZipEntry> en = zip.entries();
			while (en.hasMoreElements()) {
				String n = en.nextElement().getName();
				String nn = normalizeEntryName(n);
				// 要求 nn 是以 prefix 开头且不是完全相同（即为子项），避免把同名文件误判
				if (nn.startsWith(prefix) && !nn.equals(normName)) return true;
			}
		} catch (Exception ignored) {
		}
		return false;
	}

	public void writeFile(InputStream stream, File file) throws IOException {
		try (FileOutputStream outputStream = new FileOutputStream(file)) {
			byte[] buffer = new byte[4096];
			int read;
			while ((read = stream.read(buffer)) >= 0) {
				outputStream.write(buffer, 0, read);
			}
		}
	}
}
