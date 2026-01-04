package com.sindercube.serverUnpacker.util;

import java.io.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * PackExtractor：安全的 zip 解压工具，包含：
 * - 防止 Zip Slip（路径穿越）
 * - 在路径超长时尝试通过移除单字母目录缩短路径
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
	 *
	 * 主要改动点：
	 * - 使用 destRootPath = destRoot.toPath().toAbsolutePath().normalize() 作为基准 Path。
	 * - 用 destRootPath.resolve(...).normalize() 构建 candidate Path 并用其字符串长度判断是否超长（不调用 getCanonicalPath）。
	 * - 使用 Path.startsWith(destRootPath) 来防止 zip-slip。
	 * - 只有在确认路径合法、长度可接受后才实际创建父目录并写入文件。
	 */
	public void extractPack(Path destination, File pack, String name, LongConsumer itemCountConsumer, Runnable onItemFinished) {
		// 根目标目录（destination/name）
		File destRoot = destination.resolve(name).toFile();
		try (ZipFile zip = new ZipFile(pack)) {
			itemCountConsumer.accept(zip.size());

			Enumeration<? extends ZipEntry> entries = zip.entries();

			// 使用绝对规范化的 destRootPath 作为比较基准（不用 canonical 来避免对每个候选路径做文件系统调用）
			Path destRootPath = destRoot.toPath().toAbsolutePath().normalize();

			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();

				// 原始 zip 内路径（zip 里统一使用 '/' 分隔）
				String rawEntryName = entry.getName();

				// 处理并规范化 entryName：去掉前导 "./"，合并重复斜杠，去掉前导斜杠
				String entryName = rawEntryName.replaceAll("^\\./+", "")
						.replaceAll("^/+", "")
						.replaceAll("/{2,}", "/");

				// 跳过空条目（例如 zip 中可能存在空名称）
				if (entryName == null || entryName.isEmpty()) {
					continue;
				}

				// 判断是否为目录：entry 本身标记为目录 或 名称以 '/' 结尾，或者 zip 中存在以该名称为前缀的其它条目（说明它实际是个目录）
				boolean entryIsDirectory = entry.isDirectory() || rawEntryName.endsWith("/") || rawEntryName.endsWith("\\");

				// 如果 zip 中没有显式目录标记，但存在以 "name/" 为前缀的其它 entry，则把当前条目当作目录（避免把 assets 写成文件）
				if (!entryIsDirectory && zipContainsDirPrefix(zip, entryName)) {
					entryIsDirectory = true;
				}

				// 如果是目录，则使用规范化的 Path 来创建目录并继续（避免提前 canonical）
				if (entryIsDirectory) {
					Path dirPath = destRootPath.resolve(entryName.replace('/', File.separatorChar)).normalize();
					File dir = dirPath.toFile();
					if (!dir.exists() && !dir.mkdirs()) {
						throw new IOException("Failed to create directory: " + dir);
					}
					// 目录条目视为处理完成
					onItemFinished.run();
					continue;
				}

				// 默认使用 entryName，之后可能调整
				String adjustedEntryName = entryName;

				// 先用语法上的 resolve/normalize 构造 candidate（不访问文件系统）
				Path candidateResolved = destRootPath.resolve(adjustedEntryName.replace('/', File.separatorChar)).normalize().toAbsolutePath();
				String candidateResolvedStr = candidateResolved.toString();

				// 如果语法化后的路径字符串长度超过限制，尝试用算法缩短 entryName
				if (candidateResolvedStr.length() > MAX_PATH_LENGTH) {
					// 调整算法现在接受 destRootPath 并基于 resolve/normalize 来判断长度（不调用 canonical）
					String shortened = tryShortenByRemovingSingleLetterDirs(adjustedEntryName, destRootPath, MAX_PATH_LENGTH);
					if (shortened == null) {
						System.err.println("Skipped entry due to excessive path length and cannot shorten: " + entryName);
						continue;
					}
					adjustedEntryName = shortened;
					candidateResolved = destRootPath.resolve(adjustedEntryName.replace('/', File.separatorChar)).normalize().toAbsolutePath();
					candidateResolvedStr = candidateResolved.toString();
				}

				// Zip Slip 检查：确保 candidateResolved 在 destRootPath 之下（或等于 destRootPath）
				// 使用 Path.startsWith 比较规范化后的绝对路径以避免穿越
				if (!candidateResolved.startsWith(destRootPath)) {
					System.err.println("Skipped entry due to zip-slip attempt: " + entryName + " -> " + adjustedEntryName);
					continue;
				}

				// 最后确认长度（保险）
				if (candidateResolvedStr.length() > MAX_PATH_LENGTH) {
					System.err.println("Skipped entry because adjusted path still too long: " + adjustedEntryName);
					continue;
				}

				// 确保父目录存在（此处会实际与文件系统交互）
				File adjustedFile = candidateResolved.toFile();
				File parent = adjustedFile.getParentFile();
				if (parent != null && !parent.exists()) {
					if (!parent.mkdirs() && !parent.exists()) {
						throw new IOException("Failed to create parent directories for: " + adjustedFile);
					}
				}

				// 写文件（使用 try-with-resources 以确保流关闭）
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
	 * 尝试通过移除 entryName 中的单字母目录（不移除最后一个组件，即文件名）来缩短路径，使得在 destRootPath 下的规范化路径长度不超过 maxLen。
	 * 如果无法通过移除单字母目录达成，则返回 null（表示无法缩短）。
	 *
	 * 算法策略（更健壮的实现）：
	 * - 将 entryName 按 '/' 分割为组件，忽略空组件。
	 * - 收集索引为单字母的组件（不包含最后一部分）。
	 * - 按 removableIndices 的不同组合依次移除，优先尝试从左到右移除较少数量的组件，再尝试从右向左移除一段。
	 */
	private String tryShortenByRemovingSingleLetterDirs(String entryName, Path destRootPath, int maxLen) {
		try {
			// 分割并忽略空部分
			String[] rawParts = entryName.split("/");
			List<String> partsList = new ArrayList<>();
			for (String p : rawParts) {
				if (p == null || p.isEmpty()) continue;
				partsList.add(p);
			}
			int n = partsList.size();
			if (n == 0) return null;

			// 如果只有一个部分（文件名），无法缩短
			if (n == 1) {
				return null;
			}

			// 收集可移除的单字母部分索引（不包含最后一部分）
			List<Integer> removableIndices = new ArrayList<>();
			for (int i = 0; i < n - 1; i++) {
				if (partsList.get(i).length() == 1) {
					removableIndices.add(i);
				}
			}
			if (removableIndices.isEmpty()) {
				// 没有单字母目录可以移除
				return null;
			}

			// 如果当前 destRoot + original entry 已经不超过 maxLen（防御性检查）
			Path origCandidate = destRootPath.resolve(String.join(File.separator, partsList)).normalize().toAbsolutePath();
			if (origCandidate.toString().length() <= maxLen) {
				return entryName;
			}

			// 按 removableIndices 的顺序逐步移除 1..k 个单字母目录，检查是否达标
			int m = removableIndices.size();
			for (int r = 1; r <= m; r++) {
				boolean[] removed = new boolean[n];
				for (int i = 0; i < r; i++) {
					removed[removableIndices.get(i)] = true;
				}
				// 构造新的 entryName（跳过被移除的部分）
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < n; i++) {
					if (removed[i]) continue;
					if (sb.length() > 0) sb.append('/');
					sb.append(partsList.get(i));
				}
				String candidate = sb.toString();
				Path candidatePath = destRootPath.resolve(candidate.replace('/', File.separatorChar)).normalize().toAbsolutePath();
				if (candidatePath.toString().length() <= maxLen) {
					System.err.println("Adjusted entry name to avoid long path: " + entryName + " -> " + candidate);
					return candidate;
				}
			}

			// 再尝试从右向左移除一段单字母目录（例如移除靠近文件端的若干单字母目录）
			for (int start = m - 1; start >= 0; start--) {
				boolean[] removed2 = new boolean[n];
				for (int i = start; i < m; i++) {
					removed2[removableIndices.get(i)] = true;
				}
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < n; i++) {
					if (removed2[i]) continue;
					if (sb.length() > 0) sb.append('/');
					sb.append(partsList.get(i));
				}
				String candidate = sb.toString();
				Path candidatePath = destRootPath.resolve(candidate.replace('/', File.separatorChar)).normalize().toAbsolutePath();
				if (candidatePath.toString().length() <= maxLen) {
					System.err.println("Adjusted entry name to avoid long path: " + entryName + " -> " + candidate);
					return candidate;
				}
			}

			// 若仍然无法满足，返回 null（表示跳过该条目）
			return null;
		} catch (Exception e) {
			// 在规范化过程中若有异常（尽量不要抛出），放弃缩短尝试
			return null;
		}
	}

	/**
	 * 将 InputStream 写入目标文件。抛出 IOException 以便上层统一处理。
	 */
	public void writeFile(InputStream stream, File file) throws IOException {
		// 使用 try-with-resources，抛出 IOException 以便上层统一处理
		try (FileOutputStream outputStream = new FileOutputStream(file)) {
			byte[] buffer = new byte[4096];
			int read;
			while ((read = stream.read(buffer)) >= 0) {
				outputStream.write(buffer, 0, read);
			}
		}
	}
}
