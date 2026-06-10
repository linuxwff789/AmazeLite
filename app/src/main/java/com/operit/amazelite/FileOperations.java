package com.operit.amazelite;

import android.content.Context;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class FileOperations {

    public static boolean rmRecursive(File f) {
        if (f == null || !f.exists()) return true;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) rmRecursive(c);
        }
        return f.delete();
    }

    public static void rmRecursiveWithProgress(File f, AtomicInteger done, int total, ProgressBar pb, TextView tvStat) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) rmRecursiveWithProgress(c, done, total, pb, tvStat);
        } else {
            f.delete();
            int d = done.incrementAndGet();
            if (tvStat != null && pb != null && (d % 10 == 0 || d >= total)) {
                final int progress = Math.min(1000, (int)((long)d * 1000 / Math.max(1, total)));
                final String text = d + "/" + total + " 已删除";
                android.app.Activity activity = (android.app.Activity) pb.getContext();
                activity.runOnUiThread(() -> { pb.setProgress(progress); tvStat.setText(text); });
            }
        }
        if (f.exists()) f.delete();
    }

    public static int countLeafFiles(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return 1;
        int count = 0;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) count += countLeafFiles(c);
        return count;
    }

    public static long computeTotalBytes(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long total = 0;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) total += computeTotalBytes(c);
        return total;
    }

    public static int estimateDoneFiles(List<String> paths, long processedBytes) {
        long fullTotal = 0;
        for (String p : paths) fullTotal += computeTotalBytes(new File(p));
        if (fullTotal <= 0) return 0;
        return (int) ((long) paths.size() * processedBytes / fullTotal);
    }

    public static void copyFileWithProgress(File src, File dest, AtomicInteger doneCount,
                                              long totalBytes, AtomicLong processedBytes,
                                              int totalFiles, ProgressBar pb, TextView tvStat,
                                              AtomicBoolean cancelled) throws Exception {
        if (cancelled != null && cancelled.get()) return;
        if (dest.exists()) dest.delete();
        FileInputStream fis = new FileInputStream(src);
        FileOutputStream fos = new FileOutputStream(dest);
        byte[] buf = new byte[32768];
        int len;
        long fileSize = src.length();
        long fileDone = 0;
        long lastUpdate = System.currentTimeMillis();
        while ((len = fis.read(buf)) > 0) {
            if (cancelled != null && cancelled.get()) { fis.close(); fos.close(); return; }
            fos.write(buf, 0, len);
            fileDone += len;
            processedBytes.addAndGet(len);
            long now = System.currentTimeMillis();
            if (now - lastUpdate > 100) {
                lastUpdate = now;
                long p = processedBytes.get();
                int progress = (int) Math.min(1000, p * 1000 / Math.max(1, totalBytes));
                int filesDone = doneCount.get() + (fileDone >= fileSize ? 1 : 0);
                long eta = 0;
                long speed = 0;
                if (p > 0 && now > 0) {
                    long elapsed = now - 0; // approx
                    speed = p * 1000 / Math.max(1, elapsed);
                    long remaining = totalBytes - p;
                    eta = speed > 0 ? remaining * 1000 / speed : 0;
                }
                final int fProgress = progress;
                final String statText = filesDone + "/" + totalFiles + " " + String.format("%.1f", p * 100.0 / totalBytes) + "%";
                final String detailText = humanSpeed(speed) + " 剩余 " + formatDuration(eta);
                android.app.Activity activity = (android.app.Activity) pb.getContext();
                activity.runOnUiThread(() -> { pb.setProgress(fProgress); tvStat.setText(statText + "  " + detailText); });
            }
        }
        fis.close();
        fos.close();
        doneCount.incrementAndGet();
    }

    public static void copyFile(File src, File dest) throws Exception {
        if (dest.exists()) dest.delete();
        FileInputStream fis = new FileInputStream(src);
        FileOutputStream fos = new FileOutputStream(dest);
        byte[] buf = new byte[32768];
        int len;
        while ((len = fis.read(buf)) > 0) fos.write(buf, 0, len);
        fis.close();
        fos.close();
    }

    public static boolean copyDir(File src, File dest) {
        if (!src.exists() || !src.isDirectory()) return false;
        if (!dest.exists()) dest.mkdirs();
        File[] children = src.listFiles();
        if (children == null) return true;
        for (File c : children) {
            if (c.isDirectory()) {
                if (!copyDir(c, new File(dest, c.getName()))) return false;
            } else {
                try { copyFile(c, new File(dest, c.getName())); } catch (Exception e) { return false; }
            }
        }
        return true;
    }

    public static void copyDirWithProgress(File src, File dest, AtomicInteger doneCount,
                                             long totalBytes, AtomicLong processedBytes,
                                             int totalFiles, ProgressBar pb, TextView tvStat,
                                             AtomicBoolean cancelled) throws Exception {
        if (!dest.exists()) dest.mkdirs();
        File[] children = src.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (cancelled != null && cancelled.get()) return;
            if (c.isDirectory()) {
                copyDirWithProgress(c, new File(dest, c.getName()), doneCount, totalBytes, processedBytes, totalFiles, pb, tvStat, cancelled);
            } else {
                copyFileWithProgress(c, new File(dest, c.getName()), doneCount, totalBytes, processedBytes, totalFiles, pb, tvStat, cancelled);
            }
        }
    }

    public static String humanSpeed(double bytesPerSecond) {
        if (bytesPerSecond < 1024) return String.format("%.0f B/s", bytesPerSecond);
        if (bytesPerSecond < 1048576) return String.format("%.1f KB/s", bytesPerSecond / 1024);
        return String.format("%.1f MB/s", bytesPerSecond / 1048576);
    }

    public static String formatDuration(long millis) {
        if (millis < 0) return "";
        long sec = millis / 1000;
        if (sec < 60) return sec + "秒";
        long min = sec / 60; sec %= 60;
        return min + "分" + sec + "秒";
    }

    public static void countFiles(File f, List<File> list) {
        if (f == null || !f.exists()) return;
        if (f.isFile()) { list.add(f); return; }
        File[] children = f.listFiles();
        if (children != null) for (File c : children) countFiles(c, list);
    }
}
