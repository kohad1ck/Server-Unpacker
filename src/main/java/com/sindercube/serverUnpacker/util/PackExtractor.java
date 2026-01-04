package com.sindercube.serverUnpacker.util;

import java.io.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class PackExtractor {

	public static final PackExtractor INSTANCE = new PackExtractor();

	/**
	 * 最大允许的解压后绝对路径长度（字符数）。可以根据需要调整。
	 * 260 是一个比较保守的 Windows MAX_PATH 边界；如果你在现代 Windows 或 Linux 上并需要更长路径，可以增大此值。
	 */
	private static final int MAX_PATH_LENGTH = 260;

	public void extractPack(Path destination, File pack, String name) {
		extractPack(destination, pack, name, c -> {}, () -> {});
	}

	public void extractPack(Path destination, File pack, String name, LongConsumer itemCountConsumer, Runnable onItemFinished) {
		// 根目标目录（destination/name）
		File destRoot = destination.resolve(name).toFile();
		try (ZipFile zip = new ZipFile(pack)) {
			itemCountConsumer.accept(zip.size());

			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();

				// 原始 zip 内路径（使用 '/' 分隔）
				String entryName = entry.getName();

				// 目标文件（未经规范化）
				File newFile = new File(destRoot, entryName);

				// 如果是目录，则确保目录存在并继续下一个条目（不要 return）
				if (entry.isDirectory()) {
					File dir = newFile;
					if (!dir.exists() && !dir.mkdirs()) {
						throw new IOException("Failed to create directory: " + dir);
					}
					// 认为目录已经处理完，不调用 onItemFinished（如果你希望也调用可以修改）
					continue;
				}

				// 防止 Zip Slip：先用原始 newFile 检查（规范化后）
				String destCanonical = destRoot.getCanonicalPath();

				// 处理超长路径：如果长度超限，尝试通过移除单字母路径段来缩短（尽量保留原始结构与文件名）
				String adjustedEntryName = entryName;
				File adjustedFile = newFile;
				String adjustedCanonical = adjustedFile.getCanonicalPath();
				if (adjustedCanonical.length() > MAX_PATH_LENGTH) {
					adjustedEntryName = tryShortenByRemovingSingleLetterDirs(entryName, destRoot, MAX_PATH_LENGTH);
					if (adjustedEntryName == null) {
						System.err.println("Skipped entry due to excessive path length and cannot shorten: " + entryName);
						continue;
					}
					adjustedFile = new File(destRoot, adjustedEntryName);
					adjustedCanonical = adjustedFile.getCanonicalPath();
				}

				// 再做一次 Zip Slip 检查（使用调整后的路径）
				if (!adjustedCanonical.startsWith(destCanonical + File.separator) && !adjustedCanonical.equals(destCanonical)) {
					// 路径穿越尝试，跳过该条目
					System.err.println("Skipped entry due to zip-slip attempt: " + entryName + " -> " + adjustedEntryName);
					continue;
				}

				// 最后确认长度（保险）
				if (adjustedCanonical.length() > MAX_PATH_LENGTH) {
					System.err.println("Skipped entry because adjusted path still too long: " + adjustedEntryName);
					continue;
				}

				// 确保父目录存在
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
	 * 尝试通过移除 entryName 中的单字母目录（不移除最后一个组件，即文件名）来缩短路径，使得在 destRoot 下的规范化路径长度不超过 maxLen。
	 * 如果无法通过移除单字母目录达成，则返回 null（表示无法缩短）。
	 *
	 * 算法策略：
	 * - 将 entryName 按 '/' 分割为组件。
	 * - 收集索引为单字母的组件（不包含最后一个组件）。
	 * - 按从左到右顺序依次移除这些单字母组件（依次尝试移除一个或多个），每移除一次就构建 candidate 并检查长度是否达标。
	 * - 一旦达标返回新的 entryName（不以多个连续 '/' 产生空段）。
	 */
	private String tryShortenByRemovingSingleLetterDirs(String entryName, File destRoot, int maxLen) {
		try {
			String[] parts = entryName.split("/"); // Zip entry uses '/'
			int n = parts.length;
			if (n == 0) return null;

			// 如果只有一个部分（文件名），无法缩短
			if (n == 1) {
				return null;
			}

			// 收集可移除的单字母部分索引（不包含最后一部分）
			List<Integer> removableIndices = new ArrayList<>();
			for (int i = 0; i < n - 1; i++) {
				if (parts[i].length() == 1) {
					removableIndices.add(i);
				}
			}
			if (removableIndices.isEmpty()) {
				// 没有单字母目录可以移除
				return null;
			}

			// 如果当前 destRoot + original entry 已经不超过 maxLen（未被调用时），直接返回 original（但此处通常是被调用的情况）
			File origFile = new File(destRoot, entryName);
			if (origFile.getCanonicalPath().length() <= maxLen) {
				return entryName;
			}

			// 我们按照从左到右顺序移除单字母目录（优先移除靠近根的单字母目录），每移除一个就检查是否达标。
			// 这样尽可能保留靠近文件端的目录层级与文件名。
			boolean[] removed = new boolean[n];
			for (int idx : removableIndices) removed[idx] = false;

			for (int r = 1; r <= removableIndices.size(); r++) {
				// 每一轮移除 r 个单字母目录（按 removableIndices 顺序）
				for (int i = 0; i < r; i++) {
					removed[removableIndices.get(i)] = true;
				}
				// 构造新的名字
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < n; i++) {
					if (removed[i]) continue;
					if (sb.length() > 0) sb.append('/');
					sb.append(parts[i]);
				}
				String candidate = sb.toString();
				File candidateFile = new File(destRoot, candidate);
				String candCanonical = candidateFile.getCanonicalPath();
				if (candCanonical.length() <= maxLen) {
					// 成功找到一个可接受的缩短结果
					System.err.println("Adjusted entry name to avoid long path: " + entryName + " -> " + candidate);
					return candidate;
				}
			}

			// 尝试另一种策略：如果上面按顺序不能满足，尝试从右向左移除单字母目录（保留顶层）
			for (int start = removableIndices.size() - 1; start >= 0; start--) {
				boolean[] removed2 = new boolean[n];
				for (int i = start; i < removableIndices.size(); i++) {
					removed2[removableIndices.get(i)] = true;
				}
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < n; i++) {
					if (removed2[i]) continue;
					if (sb.length() > 0) sb.append('/');
					sb.append(parts[i]);
				}
				String candidate = sb.toString();
				File candidateFile = new File(destRoot, candidate);
				String candCanonical = candidateFile.getCanonicalPath();
				if (candCanonical.length() <= maxLen) {
					System.err.println("Adjusted entry name to avoid long path: " + entryName + " -> " + candidate);
					return candidate;
				}
			}

			// 若仍然无法满足，返回 null（表示跳过）
			return null;
		} catch (IOException e) {
			// 如果在规范化过程中出错，放弃缩短尝试
			return null;
		}
	}

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
