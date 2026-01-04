package com.sindercube.serverUnpacker.util;

import java.io.*;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Formatter;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * PackExtractor：安全的 zip 解压工具，包含：
 * - 防止 Zip Slip（路径穿越）
 * - 在路径超长时尽可能缩短路径（移除单字母目录、截断组件、使用中间哈希）
 * - 避免在路径过长时调用 getCanonicalPath() 导致的 IOException（改为使用 Path.resolve(...).normalize() 做语法规范化与判断）
 *
 * 注意：真正写入文件/创建目录时仍会调用文件系统操作（mkdirs、FileOutputStream），因此在非常极端的环境下仍可能遇到系统限制。
 */
public class PackExtractor {

	public static final PackExtractor INSTANCE = new PackExtractor();

	/**
	 * 最大允许的解压后绝对路径长度（字符数）。
	 * 260 是一个比较保守的 Windows MAX_PATH 边界；如果你在现代 Windows 或 Linux 上并需要更长路径，可以增大此值。
	 */
	private static final int MAX_PATH_LENGTH = 260;

	public void extractPack(Path destination, File pack, String name) {
		extractPack(destination, pack, name, c -> {}, () -> {});
	}

	/**
	 * 解压 zip 包到 destination/name 目录下。
	 */
	public void extractPack(Path destination, File pack, String name, LongConsumer itemCountConsumer, Runnable onItemFinished) {
		File destRoot = destination.resolve(name).toFile();
		try (ZipFile zip = new ZipFile(pack)) {
			itemCountConsumer.accept(zip.size());

			Enumeration<? extends ZipEntry> entries = zip.entries();
			Path destRootPath = destRoot.toPath().toAbsolutePath().normalize();

			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();

				String rawEntryName = entry.getName();

				// 规范化 entryName：去掉前导 "./", 前导 "/"，合并重复分隔符
				String entryName = rawEntryName.replaceAll("^\\./+", "")
						.replaceAll("^/+", "")
						.replaceAll("/{2,}", "/");

				if (entryName == null || entryName.isEmpty()) {
					continue;
				}

				// 目录判断：entry 标记为目录、名字以 '/' 结尾、或者存在 prefix 则视为目录
				boolean entryIsDirectory = entry.isDirectory() || rawEntryName.endsWith("/") || rawEntryName.endsWith("\\");

				if (!entryIsDirectory && zipContainsDirPrefix(zip, entryName)) {
					entryIsDirectory = true;
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

				String adjustedEntryName = sanitizeEntryName(entryName);

				// 构建 candidate，并在过长时尝试缩短
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
		// 确保文件名不为空（例如原始最后一部分以点开头导致 basename 为空）
		String last = parts.get(parts.size() - 1);
		if (last.isEmpty()) {
			parts.set(parts.size() - 1, "file");
		}
		return String.join("/", parts);
	}

	/**
	 * 更激进的缩短流程：
	 * 1) 移除所有单字母目录
	 * 2) 逐步截断每个组件到较短长度（从 8 到 1）
	 * 3) 使用中间哈希（保留首尾）替换中间组件
	 */
	private String shortenPathMoreAggressively(String entryName, Path destRootPath, int maxLen) {
		try {
			// 分割并清理
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
				// 单文件名，尝试截断文件名但保留扩展
				String single = truncateFilename(parts.get(0), destRootPath, maxLen, null);
				return single == null ? null : single;
			}

			// 1) 移除所有单字母目录（保留最后文件名）
			List<String> partsNoSingles = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				String p = parts.get(i);
				if (i != n - 1 && p.length() == 1) {
					continue; // 删除单字母目录
				}
				partsNoSingles.add(p);
			}
			// 若移除后长度满足直接返回
			String candidate1 = String.join("/", partsNoSingles);
			Path p1 = destRootPath.resolve(candidate1.replace('/', File.separatorChar)).normalize().toAbsolutePath();
			if (p1.toString().length() <= maxLen) {
				System.err.println("Removed all single-letter dirs: " + entryName + " -> " + candidate1);
				return candidate1;
			}

			// 2) 逐步截断每个组件（对于目录使用固定前缀长度，对于最后文件名保留后缀）
			// 尝试从较长的前缀到较短前缀
			for (int compLen = 8; compLen >= 1; compLen--) {
				List<String> truncated = new ArrayList<>();
				for (int i = 0; i < partsNoSingles.size(); i++) {
					String comp = partsNoSingles.get(i);
					if (i == partsNoSingles.size() - 1) {
						// 文件名，保留扩展
						truncated.add(truncateFilename(comp, destRootPath, maxLen, compLen));
					} else {
						truncated.add(truncateComponent(comp, compLen));
					}
				}
				// 如果 truncateFilename 返回 null（表示无法在当前策略下满足），继续尝试
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

			// 3) 使用中间哈希：保留第一个和最后一个组件，中间替换为 8 位 hex 的 hash
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
				// 如果还不够短，尝试缩短 first 和 last 再做 hash 方案
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

			// 如果都失败，返回 null
			return null;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * 截断纯目录组件到指定长度（若小于等于则原样返回）
	 */
	private String truncateComponent(String comp, int len) {
		if (comp == null) return comp;
		if (comp.length() <= len) return comp;
		return comp.substring(0, Math.max(1, len));
	}

	/**
	 * 截断文件名，同时保留扩展（若存在）。如果指定 compLen != null，则目录组件长度使用 compLen。
	 * 返回截断后的文件名字符串（不会返回 null，除非输入为 null）。
	 */
	private String truncateFilename(String filename, Path destRootPath, int maxLen, Integer compLen) {
		if (filename == null) return null;
		// 找扩展名
		int idx = filename.lastIndexOf('.');
		String namePart = idx >= 0 ? filename.substring(0, idx) : filename;
		String extPart = idx >= 0 ? filename.substring(idx) : "";

		// 按 compLen 截断 namePart（若 compLen 为 null 则优先保留更多）
		if (compLen != null && compLen > 0) {
			if (namePart.length() > compLen) {
				namePart = namePart.substring(0, Math.max(1, compLen));
			}
		} else {
			// 若 compLen 为 null，尽量不截断文件名（但如果整体太长，会在外层再次尝试其他策略）
		}

		String candidate = namePart + extPart;
		// 防止 namePart 变得只是扩展名（如 ".json" 变成 ".json"），确保 namePart 非空
		if (namePart.isEmpty()) candidate = "file" + extPart;
		return candidate;
	}

	/**
	 * 把中间段哈希为 8 位 hex（SHA-1 的前 8 字节 -> 16 hex 字符，按需可改为更短）
	 */
	private String hashToHex(String s) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			byte[] b = md.digest(s == null ? new byte[0] : s.getBytes("UTF-8"));
			// 取前 8 字节转为 hex（16 个 hex 字符）
			int len = Math.min(8, b.length);
			StringBuilder sb = new StringBuilder(len * 2);
			for (int i = 0; i < len; i++) {
				sb.append(String.format("%02x", b[i]));
			}
			return sb.toString();
		} catch (Exception e) {
			// 兜底，使用简单替代
			return Integer.toHexString(s == null ? 0 : s.hashCode());
		}
	}

	/**
	 * 判断 zip 中是否存在以 name + '/' 为前缀的条目（表明 name 实际上是一个目录）
	 */
	private boolean zipContainsDirPrefix(ZipFile zip, String name) {
		try {
			String prefix = name.endsWith("/") ? name : name + "/";
			Enumeration<? extends ZipEntry> en = zip.entries();
			while (en.hasMoreElements()) {
				String n = en.nextElement().getName();
				if (n.startsWith(prefix)) return true;
			}
		} catch (Exception ignored) {
		}
		return false;
	}

	/**
	 * 将 InputStream 写入目标文件。抛出 IOException 以便上层统一处理。
	 */
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
