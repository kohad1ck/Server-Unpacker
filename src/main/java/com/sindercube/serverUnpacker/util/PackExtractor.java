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

				// 规范化 name：统一分隔符为 '/'，去掉前导 "./", 前导 "/"，合并重复分隔符，并移除末尾的 '/'
				String entryName = normalizeEntryName(rawEntryName);

				if (entryName == null || entryName.isEmpty()) {
					continue;
				}

				// --- 关键：先判断是否为目录（在任何缩短逻辑之前） ---
				boolean entryIsDirectory = entry.isDirectory()
						|| rawEntryName.endsWith("/")
						|| rawEntryName.endsWith("\\");

				// 如果被显式标记为目录，但 ZIP 中存在同名的显式文件条目，我们应优先把它当作文件处理（避免把 pack.mcmeta/ 导出为目录）
				if (entryIsDirectory) {
					if (hasFileExtension(entryName) && zipHasExplicitFile(zip, entryName)) {
						// 优先当作文件
						entryIsDirectory = false;
					}
				}

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
		String n = entryName.replace('\\', '/');
		while (n.endsWith("/")) n = n.substring(0, n.length() - 1);
		int idx = n.lastIndexOf('/');
		String last = idx >= 0 ? n.substring(idx + 1) : n;
		int dot = last.lastIndexOf('.');
		return dot > 0 && dot < last.length() - 1;
	}

	/**
	 * 规范化 zip 条目名以便比较：统一分隔符为 '/'，删除前导 "./" 和前导 "/", 合并重复分隔符并移除末尾的 '/'
	 */
	private String normalizeEntryName(String entryName) {
		if (entryName == null) return null;
		// 先把反斜杠统一为正斜杠，方便后续比较
		String n = entryName.replace('\\', '/')
				.replaceAll("^\\./+", "")
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
	 * 更温和的缩短流程（仅用于“文件”路径）：
	 * - 尽量不移除导致路径深度降为 1 的单字母目录；
	 * - 截断时保留更多字符（非末尾至少保留 2，末尾文件名主体至少保留 3）；
	 * - 尝试从较温和的截断长度开始（16 -> 4），仅在需要时使用哈希替换中间部分；
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

			// 目标：缩短后至少保留的组件数（如果原始有多于1个组件，至少保留 2 个）
			int minComponents = Math.min(2, n);

			// 1) 尝试移除单字母目录（不包含最后一部分），但不要把深度降到 1
			List<String> partsNoSingles = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				String p = parts.get(i);
				if (i != n - 1 && p.length() == 1) continue;
				partsNoSingles.add(p);
			}
			// 如果移除导致深度小于要求，则放弃移除单字母目录（减少过激行为）
			if (partsNoSingles.size() < minComponents) {
				partsNoSingles = new ArrayList<>(parts); // revert to original parts (不移除)
			} else {
				String candidate1 = String.join("/", partsNoSingles);
				Path p1 = destRootPath.resolve(candidate1.replace('/', File.separatorChar)).normalize().toAbsolutePath();
				if (p1.toString().length() <= maxLen) {
					System.err.println("Removed single-letter dirs (conservative): " + entryName + " -> " + candidate1);
					return candidate1;
				}
			}

			// 2) 逐步截断每个组件（从较大长度到较小，尝试更温和的截断）
			for (int compLen = 16; compLen >= 4; compLen--) {
				List<String> truncated = new ArrayList<>();
				for (int i = 0; i < partsNoSingles.size(); i++) {
					String comp = partsNoSingles.get(i);
					if (i == partsNoSingles.size() - 1) {
						// 末尾（文件名主体）至少保留 3 个字符（如果可能）
						truncated.add(truncateFilename(comp, destRootPath, maxLen, Math.max(3, compLen)));
					} else {
						// 非末尾组件至少保留 2 个字符
						truncated.add(truncateComponent(comp, Math.max(2, compLen)));
					}
				}
				boolean anyNull = false;
				for (String t : truncated) {
					if (t == null) { anyNull = true; break; }
				}
				if (anyNull) continue;
				String candidate = String.join("/", truncated);
				// 若截断后组件数少于要求（例如被截成单组件），则跳过该候选
				int compCount = candidate.isEmpty() ? 0 : candidate.split("/").length;
				if (compCount < minComponents) continue;

				Path cp = destRootPath.resolve(candidate.replace('/', File.separatorChar)).normalize().toAbsolutePath();
				if (cp.toString().length() <= maxLen) {
					System.err.println("Truncated components to avoid long path (conservative): " + entryName + " -> " + candidate);
					return candidate;
				}
			}

			// 3) 使用中间哈希，但保留首/尾的最少字符（首保留至少2，尾保留至少3）
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
				// 检查组件数
				int compCount = 3; // first/hash/last
				if (compCount >= minComponents) {
					Path cp = destRootPath.resolve(candidate.replace('/', File.separatorChar)).normalize().toAbsolutePath();
					if (cp.toString().length() <= maxLen) {
						System.err.println("Replaced middle with hash to avoid long path: " + entryName + " -> " + candidate);
						return candidate;
					}
				}
				// 尝试压短首尾（但保留最低长度）
				String firstShort = truncateComponent(first, 4); // 最少 2 => truncateComponent 会保证
				String lastShort = truncateFilename(last, destRootPath, maxLen, 4); // 最少 3 保留
				String candidate2 = firstShort + "/" + hash + "/" + lastShort;
				int compCount2 = candidate2.isEmpty() ? 0 : candidate2.split("/").length;
				if (compCount2 >= minComponents) {
					Path cp2 = destRootPath.resolve(candidate2.replace('/', File.separatorChar)).normalize().toAbsolutePath();
					if (cp2.toString().length() <= maxLen) {
						System.err.println("Replaced middle with hash and truncated ends (conservative): " + entryName + " -> " + candidate2);
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
		// 改为至少保留 2 个字符，避免单字符目录
		int actual = Math.max(2, Math.min(len, comp.length()));
		return comp.substring(0, actual);
	}

	private String truncateFilename(String filename, Path destRootPath, int maxLen, Integer compLen) {
		if (filename == null) return null;
		int idx = filename.lastIndexOf('.');
		String namePart = idx >= 0 ? filename.substring(0, idx) : filename;
		String extPart = idx >= 0 ? filename.substring(idx) : "";

		if (compLen != null && compLen > 0) {
			// 对末尾文件主体，至少保留 3 个字符（当可能时）
			int actual = Math.max(3, Math.min(compLen, Math.max(1, namePart.length())));
			if (namePart.length() > actual) {
				namePart = namePart.substring(0, actual);
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

	/**
	 * 检查 ZIP 中是否存在以 name 为前缀的子项（规范化比较）
	 * 要求找到的条目是严格子项（nn.startsWith(prefix) 且 nn != normName）
	 */
	private boolean zipContainsDirPrefix(ZipFile zip, String name) {
		try {
			if (name == null) return false;
			String normName = normalizeEntryName(name);
			String prefix = normName.isEmpty() ? "" : (normName + "/");
			Enumeration<? extends ZipEntry> en = zip.entries();
			while (en.hasMoreElements()) {
				String n = en.nextElement().getName();
				String nn = normalizeEntryName(n);
				if (nn.startsWith(prefix) && !nn.equals(normName)) return true;
			}
		} catch (Exception ignored) {
		}
		return false;
	}

	/**
	 * 检查 ZIP 中是否存在与 name 完全匹配的非目录条目（显式文件）
	 * 用于在出现显式目录条目时，判断是否也存在文件条目；如果存在文件条目则优先当作文件处理。
	 */
	private boolean zipHasExplicitFile(ZipFile zip, String name) {
		try {
			if (name == null) return false;
			String normName = normalizeEntryName(name);
			Enumeration<? extends ZipEntry> en = zip.entries();
			while (en.hasMoreElements()) {
				ZipEntry ze = en.nextElement();
				String nn = normalizeEntryName(ze.getName());
				if (nn.equals(normName)) {
					// 如果这个条目本身不是目录（名称不以 '/' 结尾且 ZipEntry 不是 directory），认为是显式文件
					if (!ze.isDirectory() && !ze.getName().endsWith("/") && !ze.getName().endsWith("\\")) {
						return true;
					}
				}
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
