package com.operit.zpaq;

import android.content.Context;

public class ZPAQNative {
    public interface ProgressListener {
        void onProgress(long processedBytes, long totalBytes, String currentEntry);
    }

    public static class ArchiveEntry {
        public final String path;
        public final String comment;
        public final long size;
        public final long packedSize;
        public final String timeText;
        public final String attrText;
        public final String statusText;

        public ArchiveEntry(String path, String comment) {
            this(path, comment, 0L, 0L, "", "", "");
        }

        public ArchiveEntry(String path, String comment, long size, long packedSize,
                            String timeText, String attrText, String statusText) {
            this.path = path;
            this.comment = comment;
            this.size = size;
            this.packedSize = packedSize;
            this.timeText = timeText == null ? "" : timeText;
            this.attrText = attrText == null ? "" : attrText;
            this.statusText = statusText == null ? "" : statusText;
        }
    }

    public static class ArchiveVersion {
        public final String value;
        public final String label;
        public final long packedSize;
        public final int updates;
        public final int deletes;

        public ArchiveVersion(String value, String label, long packedSize, int updates, int deletes) {
            this.value = value;
            this.label = label;
            this.packedSize = packedSize;
            this.updates = updates;
            this.deletes = deletes;
        }
    }

    public static class CommandResult {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final String raw;

        public CommandResult(int exitCode, String stdout, String stderr, String raw) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.raw = raw;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }

        public String combinedText() {
            StringBuilder sb = new StringBuilder();
            if (stdout != null && !stdout.isEmpty()) sb.append(stdout.trim());
            if (stderr != null && !stderr.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(stderr.trim());
            }
            return sb.toString().trim();
        }

        public String errorMessage() {
            String text = combinedText();
            return text.isEmpty() ? ("EXIT=" + exitCode) : text;
        }
    }

    private static final java.util.regex.Pattern LIST_LINE_PATTERN = java.util.regex.Pattern.compile(
            "^([\\-+=#^?])\\s+(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})\\s+([0-9]+)\\s+(.+)$");
    private static final java.util.regex.Pattern FLEXIBLE_PREFIX_PATTERN = java.util.regex.Pattern.compile(
            "^([\\-+=#^?])\\s+(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})\\s+([0-9]+)\\s+(.+)$");
    private static final java.util.regex.Pattern ATTR_PATTERN = java.util.regex.Pattern.compile("^[d\\-]?[0-9]{3,4}$");
    private static final java.util.regex.Pattern PACKED_SIZE_PATTERN = java.util.regex.Pattern.compile("(?:^|\\s)([0-9]+)(?:\\s+->\\s*([0-9]+))?(?:\\s|$)");

    private static volatile ProgressListener progressListener;

    public static native String compressFiles(String[] paths, String outputPath, int level);
    public static native String decompressFiles(String archivePath, String outputDir);
    public static native String listEntries(String archivePath);
    public static native String getVersion();
    public static native String runCommand(String[] args);
    public static native String deleteVersionNative(String archivePath, String untilValue);
    public static native String addToArchiveNative(String archivePath, String[] inputPaths, int level, int threads);

    public static CommandResult deleteVersion(String archivePath, int versionToDelete) {
        // 删除版本N：保留到N-1（含N-1）
        if (versionToDelete <= 1) {
            return new CommandResult(1, "", "不能删除版本1", "");
        }
        String untilTarget = String.valueOf(versionToDelete - 1);
        String raw = deleteVersionNative(archivePath, untilTarget);
        return parseCommandResult(raw);
    }

    static {
        System.loadLibrary("zpaq715_fixed");
    }

    public static String compressFiles(Context ctx, String[] paths, String outputPath, int level) {
        return compressFiles(paths, outputPath, level);
    }

    public static void setProgressListener(ProgressListener listener) {
        progressListener = listener;
    }

    public static void clearProgressListener() {
        progressListener = null;
    }

    public static void onNativeProgress(long processedBytes, long totalBytes, String currentEntry) {
        ProgressListener listener = progressListener;
        if (listener != null) {
            listener.onProgress(processedBytes, totalBytes, currentEntry);
        }
    }

    public static CommandResult runCommandParsed(String... args) {
        String raw = runCommand(args);
        return parseCommandResult(raw);
    }

    public static CommandResult listArchive(String archivePath) {
        return runCommandParsed("zpaq", "list", archivePath, "-summary", "-1");
    }

    public static CommandResult listArchiveVersion(String archivePath, String untilValue) {
    if (untilValue == null || untilValue.trim().isEmpty()) {
        return runCommandParsed("zpaq", "list", archivePath, "-all", "4", "-summary", "-1");
    }
    return runCommandParsed("zpaq", "list", archivePath, "-all", "4", "-summary", "-1", "-until", untilValue.trim());
}

    public static CommandResult listArchiveAllVersions(String archivePath) {
        // 用 -all 4 输出版本目录行（含时间、更新/删除计数），解析后去掉版本前缀
        return runCommandParsed("zpaq", "list", archivePath, "-all", "4", "-summary", "-1");
    }

    public static CommandResult extractArchive(String archivePath, String outputDir) {
        return runCommandParsed("zpaq", "extract", archivePath, "-to", outputDir);
    }

    public static CommandResult extractArchiveVersion(String archivePath, String outputDir, String untilValue) {
        if (untilValue == null || untilValue.trim().isEmpty()) return extractArchive(archivePath, outputDir);
        return runCommandParsed("zpaq", "extract", archivePath, "-to", outputDir, "-until", untilValue.trim());
    }

    public static CommandResult extractArchiveEntry(String archivePath, String entryPath, String outputDir, String untilValue) {
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add("zpaq");
        args.add("extract");
        args.add(archivePath);
        if (entryPath != null && !entryPath.trim().isEmpty()) args.add(entryPath);
        args.add("-to");
        args.add(outputDir);
        if (untilValue != null && !untilValue.trim().isEmpty()) {
            args.add("-until");
            args.add(untilValue.trim());
        }
        return runCommandParsed(args.toArray(new String[0]));
    }

    public static CommandResult extractSingleEntry(String archivePath, String entryPath, String outputDir, String untilValue) {
        // 使用 -only 按文件名精确匹配，不会受路径格式（前导/、版本前缀）影响
        // entryPath 只需要文件名即可（如 backup.zip），用通配符 */文件名 来匹配任何路径下的文件
        String fileName = entryPath;
        int lastSlash = entryPath.lastIndexOf('/');
        if (lastSlash >= 0) fileName = entryPath.substring(lastSlash + 1);
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add("zpaq");
        args.add("extract");
        args.add(archivePath);
        args.add("-to");
        args.add(outputDir);
        args.add("-only");
        args.add("*/" + fileName);
        args.add("-force");
        if (untilValue != null && !untilValue.trim().isEmpty()) {
            args.add("-until");
            args.add(untilValue.trim());
        }
        return runCommandParsed(args.toArray(new String[0]));
    }

    public static CommandResult addToArchive(String archivePath, String[] inputPaths, int level, int threads) {
        String raw = addToArchiveNative(archivePath, inputPaths, level, threads);
        return parseCommandResult(raw);
    }

    public static CommandResult parseCommandResult(String raw) {
        if (raw == null) return new CommandResult(2, "", "null result", "");
        int exitCode = 2;
        String stdout = "";
        String stderr = "";
        int stdoutMarker = raw.indexOf("\nSTDOUT:\n");
        if (raw.startsWith("EXIT=") && stdoutMarker > 0) {
            try {
                exitCode = Integer.parseInt(raw.substring(5, stdoutMarker).trim());
            } catch (Exception ignored) {
            }
            int stderrMarker = raw.indexOf("\nSTDERR:\n", stdoutMarker + 9);
            if (stderrMarker >= 0) {
                stdout = raw.substring(stdoutMarker + 9, stderrMarker);
                stderr = raw.substring(stderrMarker + 10);
            } else {
                stdout = raw.substring(stdoutMarker + 9);
            }
        } else {
            stderr = raw;
        }
        return new CommandResult(exitCode, stdout, stderr, raw);
    }

    public static java.util.List<ArchiveEntry> parseOfficialListOutput(String raw) {
        CommandResult result = parseCommandResult(raw);
        return parseOfficialListOutput(result);
    }

    public static java.util.List<ArchiveEntry> parseOfficialListOutput(CommandResult result) {
        java.util.List<ArchiveEntry> entries = new java.util.ArrayList<>();
        if (result == null || !result.isSuccess() || result.stdout == null || result.stdout.isEmpty()) return entries;
        for (String line : result.stdout.split("\n")) {
            ArchiveEntry entry = parseOfficialEntryLine(line);
            if (entry != null) entries.add(entry);
        }
        return entries;
    }

    private static ArchiveEntry parseOfficialEntryLine(String line) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return null;

        // 跳过摘要/版本/统计等非文件行
        if (looksLikeSummaryLine(trimmed)) return null;

        // 统计摘要行：以 " -" 或 "  ->" 开头的非文件统计行
        if (trimmed.startsWith("-0") || trimmed.startsWith("-0.")
                || trimmed.startsWith("-0,") || trimmed.startsWith("- .")
                || trimmed.startsWith("->") || trimmed.matches("^\\s*-\\s*\\d+\\.")
                || trimmed.matches("^\\s*-\\s*[0-9.,]+ MB")
                || trimmed.matches("^\\s*-\\s*[0-9.,]+ KB")
                || trimmed.matches("^\\s*-\\s*[0-9.,]+ bytes")
                || trimmed.contains("fragments have unknown size")
                || trimmed.contains("MB after dedupe")
                || trimmed.contains("MB compressed")
                || trimmed.contains("MB shown")
                || trimmed.contains("frags) after dedupe")) return null;

        // 第一优先级：精确格式匹配（状态 + 日期时间 + 大小 + 属性 + 路径 + 碎片ID）
        ArchiveEntry parsed = parseWithPattern(trimmed, LIST_LINE_PATTERN);
        if (parsed != null) return parsed;

        // 第二优先级：宽松格式回退 —— 从行首提取第一个可见字符为状态，后面按空格划分为字段
        int firstSpace = trimmed.indexOf(' ');
        if (firstSpace > 0 && "-+=#^?".indexOf(trimmed.charAt(0)) >= 0) {
            // 尝试用固定列宽旧逻辑回退
            return parseLegacyEntryLine(trimmed);
        }

        return null;
    }

    private static ArchiveEntry parseWithPattern(String line, java.util.regex.Pattern pattern) {
        java.util.regex.Matcher matcher = pattern.matcher(line);
        if (!matcher.matches()) return null;
        String statusText = matcher.group(1);
        String timeText = matcher.group(2).trim();
        long size = parseLongSafe(matcher.group(3));
        String rest = matcher.group(4).trim();
        return buildArchiveEntry(statusText, timeText, size, rest);
    }

    private static ArchiveEntry parseLegacyEntryLine(String line) {
        if (line.length() < 24) return null;
        char statusChar = line.charAt(0);
        if ("-+=#^?".indexOf(statusChar) < 0) return null;

        String timeText = safeSubstring(line, 2, 21).trim();
        String sizeText = safeSubstring(line, 21, 34).trim();
        String rest = safeSubstring(line, 34, line.length()).trim();
        if (rest.isEmpty()) {
            int firstSizeIndex = findStandaloneNumberAfterPrefix(line, 20);
            if (firstSizeIndex > 0) {
                int sizeEnd = firstSizeIndex;
                while (sizeEnd < line.length() && Character.isDigit(line.charAt(sizeEnd))) sizeEnd++;
                sizeText = line.substring(firstSizeIndex, sizeEnd).trim();
                rest = line.substring(sizeEnd).trim();
                timeText = line.substring(1, firstSizeIndex).replaceFirst("^\\s+", "").trim();
            }
        }
        if (rest.isEmpty()) return null;
        return buildArchiveEntry(String.valueOf(statusChar), timeText, parseLongSafe(sizeText), rest);
    }

    private static ArchiveEntry buildArchiveEntry(String statusText, String timeText, long size, String rest) {
        if (rest == null) return null;
        rest = rest.trim();
        if (rest.isEmpty()) return null;

        String attrText = "";
        String pathAndExtra = rest;
        int firstSpace = rest.indexOf(' ');
        if (firstSpace > 0) {
            String maybeAttr = rest.substring(0, firstSpace).trim();
            if (ATTR_PATTERN.matcher(maybeAttr).matches() && !maybeAttr.contains("/")) {
                attrText = maybeAttr;
                pathAndExtra = rest.substring(firstSpace + 1).trim();
            }
        }
        if (pathAndExtra.isEmpty()) return null;

        String path = pathAndExtra;
        String extra = "";
        int markerIndex = findPathSuffixStart(pathAndExtra);
        if (markerIndex >= 0) {
            path = pathAndExtra.substring(0, markerIndex).trim();
            extra = pathAndExtra.substring(markerIndex).trim();
        }
        if (path.isEmpty() || looksLikeSummaryLine(path)) return null;

    // 如果路径以 4位数字 + "/" 开头（版本目录前缀），去掉它
    int firstSlash = path.indexOf('/');
    if (firstSlash > 0 && firstSlash <= 4) {
        String possibleVersion = path.substring(0, firstSlash);
        if (possibleVersion.length() <= 4 && possibleVersion.matches("\\d{1,4}")) {
            path = path.substring(firstSlash + 1);
            while (path.startsWith("/")) path = path.substring(1);
        }
    }

    long packedSize = parsePackedSizeFromLine(extra);
    String comment = "size=" + size + " time=" + timeText + " attr=" + attrText + " status=" + statusText;
    if (!extra.isEmpty()) comment += " extra=" + extra;
    return new ArchiveEntry(path, comment, size, packedSize, timeText, attrText, statusText);
}

private static long parsePackedSizeFromLine(String extra) {
    // zpaq -all 4 输出行末尾是碎片索引（如 "869-890"），不是压缩后大小
    // 真实的压缩后大小只在版本摘要行（"-> 28874215"）中
    // 单文件没有压缩后大小信息，返回 0（未知）
    return 0L;
}

    private static boolean looksLikeSummaryLine(String path) {
        String lower = path.toLowerCase(java.util.Locale.US);
        return lower.startsWith("zpaq v")
                || lower.startsWith("creating ")
                || lower.startsWith("updating ")
                || lower.startsWith("scanned ")
                || lower.startsWith("added ")
                || lower.startsWith("compared ")
                || lower.startsWith("memory ")
                || lower.startsWith("version ")
                || lower.contains(" seconds ");
    }

    private static int findPathSuffixStart(String value) {
        if (value == null || value.isEmpty()) return -1;
        int versionExtraIndex = value.indexOf(" +");
        String versionPath = versionExtraIndex >= 0 ? value.substring(0, versionExtraIndex).trim() : "";
        if (versionExtraIndex >= 0 && versionPath.matches("\\d{4}/?")) return versionExtraIndex;
        int fragIndex = indexOfFragmentSuffix(value);
        if (fragIndex >= 0) return fragIndex;
        return -1;
    }

    private static int indexOfFragmentSuffix(String value) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\s+\\d+(?:[- ]\\d+)*(?:\\s+->\\s*\\d+)?$").matcher(value);
        return matcher.find() ? matcher.start() : -1;
    }

    private static long parsePackedSize(String extra) {
        if (extra == null || extra.isEmpty()) return 0L;
        java.util.regex.Matcher arrowMatcher = java.util.regex.Pattern.compile("->\\s*([0-9]+)").matcher(extra);
        if (arrowMatcher.find()) return parseLongSafe(arrowMatcher.group(1));
        java.util.regex.Matcher matcher = PACKED_SIZE_PATTERN.matcher(extra);
        long lastNumber = 0L;
        while (matcher.find()) {
            if (matcher.group(2) != null && !matcher.group(2).isEmpty()) {
                return parseLongSafe(matcher.group(2));
            }
            lastNumber = parseLongSafe(matcher.group(1));
        }
        return lastNumber;
    }

    private static int findStandaloneNumberAfterPrefix(String line, int minIndex) {
        for (int i = Math.max(0, minIndex); i < line.length(); i++) {
            char ch = line.charAt(i);
            if (!Character.isDigit(ch)) continue;
            if (i > 0 && !Character.isWhitespace(line.charAt(i - 1))) continue;
            int end = i;
            while (end < line.length() && Character.isDigit(line.charAt(end))) end++;
            if (end < line.length() && Character.isWhitespace(line.charAt(end))) return i;
            i = end;
        }
        return -1;
    }

    private static String safeSubstring(String value, int start, int end) {
        int safeStart = Math.max(0, Math.min(start, value.length()));
        int safeEnd = Math.max(safeStart, Math.min(end, value.length()));
        return value.substring(safeStart, safeEnd);
    }

    private static long parseLongSafe(String value) {
        if (value == null) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    public static java.util.List<ArchiveVersion> parseVersionList(CommandResult result) {
        java.util.List<ArchiveVersion> versions = new java.util.ArrayList<>();
        if (result == null || result.stdout == null || result.stdout.isEmpty()) return versions;

        // 方法1：直接从 stdout 原始行中匹配版本目录行（避免 buildArchiveEntry 的路径剥离干扰）
        boolean hasVersionDir = false;
        String[] stdoutLines = result.stdout == null ? new String[0] : result.stdout.split("\n");
        for (String line : stdoutLines) {
            // 匹配版本目录行：- 日期 大小 000N/ [+-]N [-+]N -> 大小
            java.util.regex.Matcher vm = java.util.regex.Pattern.compile(
                    "^[\\-+=#^?]\\s+(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})\\s+[0-9]+\\s+(\\d{4})/\\s*$").matcher(line);
            if (!vm.find()) {
                // 宽松匹配：- 日期 大小 000N/ +
                vm = java.util.regex.Pattern.compile(
                        "^[\\-+=#^?]\\s+(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})\\s+[0-9]+\\s+(\\d{4})/").matcher(line);
                if (!vm.find()) continue;
            }
            String dt = vm.group(1).trim();
            int number = Integer.parseInt(vm.group(2));
            if (number <= 0) continue;
            hasVersionDir = true;
            int updates = 0, deletes = 0;
            java.util.regex.Matcher um = java.util.regex.Pattern.compile("\\+(\\d+)").matcher(line);
            if (um.find()) updates = Integer.parseInt(um.group(1));
            java.util.regex.Matcher dm = java.util.regex.Pattern.compile("\\-(\\d+)").matcher(line);
            if (dm.find()) deletes = Integer.parseInt(dm.group(1));
            String label = "版本 " + number + "  " + dt + "  更新 " + updates + " 删除 " + deletes;
            versions.add(new ArchiveVersion(String.valueOf(number), label, 0L, updates, deletes));
        }

        // 方法2：如果方法1找到了版本目录行但数量不完整（< totalVersions），从文件路径前缀补全缺失的版本
        if (hasVersionDir) {
            // 找出所有不存在的版本号
            java.util.Set<Integer> existingVersions = new java.util.HashSet<>();
            for (ArchiveVersion v : versions) existingVersions.add(Integer.parseInt(v.value));
            // 从摘要行获取总版本数
            int totalVersions = 0;
            for (String line : stdoutLines) {
                java.util.regex.Matcher vm = java.util.regex.Pattern.compile("(\\d+)\\s+versions").matcher(line);
                if (vm.find()) { totalVersions = Integer.parseInt(vm.group(1)); break; }
            }
            if (totalVersions > existingVersions.size()) {
                // 从文件路径前缀中提取所有出现的版本号
                int maxFoundFromFiles = 0;
                for (String line : stdoutLines) {
                    java.util.regex.Matcher vm = java.util.regex.Pattern.compile(
                            "^(?:[\\-+=#^?]\\s+(?:\\S+\\s+){2,3})?(\\d{4})/").matcher(line);
                    if (vm.find()) {
                        int vn = Integer.parseInt(vm.group(1));
                        if (vn <= 0 || existingVersions.contains(vn)) continue;
                        existingVersions.add(vn);
                        maxFoundFromFiles = Math.max(maxFoundFromFiles, vn);
                        versions.add(new ArchiveVersion(String.valueOf(vn), "版本 " + vn, 0L, 0, 0));
                    }
                }
                // 补齐缺失的版本号（含空骨架）：从现有最大版本号+1 到 totalVersions
                int currentMax = 0;
                for (Integer v : existingVersions) currentMax = Math.max(currentMax, v);
                for (int vn = currentMax + 1; vn <= totalVersions; vn++) {
                    if (existingVersions.contains(vn)) continue;
                    existingVersions.add(vn);
                    versions.add(new ArchiveVersion(String.valueOf(vn),
                            "版本 " + vn + "  （已删除/空版本）", 0L, 0, 0));
                }
            }
        } else {
            // 方法3（回退）：从摘要行提取总版本数，从文件路径前缀去重
            int totalVersions = 0;
            for (String line : stdoutLines) {
                java.util.regex.Matcher vm = java.util.regex.Pattern.compile("(\\d+)\\s+versions").matcher(line);
                if (vm.find()) { totalVersions = Integer.parseInt(vm.group(1)); break; }
            }
            if (totalVersions > 0) {
                java.util.Set<Integer> foundVersions = new java.util.HashSet<>();
                for (String line : stdoutLines) {
                    java.util.regex.Matcher vm = java.util.regex.Pattern.compile(
                            "^(?:[\\-+=#^?]\\s+(?:\\S+\\s+){2,3})?(\\d{4})/").matcher(line);
                    if (vm.find()) {
                        int vn = Integer.parseInt(vm.group(1));
                        if (vn <= 0 || foundVersions.contains(vn)) continue;
                        foundVersions.add(vn);
                        // 从同一个文件行尝试提取日期（这是文件的mtime，不是版本创建时间，但唯一可用）
                        java.util.regex.Matcher dm = java.util.regex.Pattern.compile(
                                "^[\\-+=#^?]\\s+(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})\\s+").matcher(line);
                        String dt = dm.find() ? dm.group(1).trim() : "";
                        String label = "版本 " + vn + (dt.isEmpty() ? "" : "  " + dt);
                        versions.add(new ArchiveVersion(String.valueOf(vn), label, 0L, 0, 0));
                    }
                }
            }
        }

        // 按版本号排序
        versions.sort((a, b) -> Integer.compare(
            Integer.parseInt(a.value), Integer.parseInt(b.value)));
        return versions;
    }

    private static int parseIntAfter(String value, String marker) {
        if (value == null || marker == null) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(marker) + "([0-9]+)").matcher(value);
        if (!matcher.find()) return 0;
        return (int) parseLongSafe(matcher.group(1));
    }

    public static java.util.List<String> parseVersions(CommandResult result) {
        java.util.List<String> versions = new java.util.ArrayList<>();
        for (ArchiveVersion version : parseVersionList(result)) versions.add(version.value);
        if (versions.isEmpty() && result != null && result.stdout != null) {
            for (String line : result.stdout.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("Version ")) versions.add(trimmed.substring("Version ".length()).trim());
            }
        }
        return versions;
    }

    public static java.util.List<ArchiveEntry> parseEntryList(String raw) {
        java.util.List<ArchiveEntry> entries = new java.util.ArrayList<>();
        if (raw == null || raw.isEmpty() || raw.startsWith("ERROR")) return entries;
        for (String line : raw.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\t", 2);
            String path = parts[0];
            String comment = parts.length > 1 ? parts[1] : "";
            entries.add(new ArchiveEntry(path, comment));
        }
        return entries;
    }
}
