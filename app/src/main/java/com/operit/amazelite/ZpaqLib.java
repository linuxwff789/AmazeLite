package com.operit.amazelite;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

public class ZpaqLib {
    static {
        System.loadLibrary("zpaq");
    }

    private static native byte[] nativeCompress(byte[] input);
    private static native byte[] nativeDecompress(byte[] compressed);

    public static String compress(String[] files, String outputPath) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(65536);
            for (String f : files) {
                File file = new File(f);
                if (!file.exists()) continue;
                byte[] nameBytes = file.getName().getBytes("UTF-8");
                writeInt32(baos, nameBytes.length);
                baos.write(nameBytes);
                writeInt64(baos, file.length());
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = fis.read(buf)) != -1) baos.write(buf, 0, n);
                }
            }
            writeInt32(baos, 0);
            byte[] input = baos.toByteArray();
            byte[] compressed = nativeCompress(input);
            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                fos.write(compressed);
            }
            return "OK:" + compressed.length + " -> " + outputPath;
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            return "ERR:" + sw.toString().substring(0, Math.min(sw.toString().length(), 200));
        }
    }

    public static String decompress(String archivePath, String outputDir) {
        try {
            File arcFile = new File(archivePath);
            if (!arcFile.exists()) return "ERR:文件不存在";
            byte[] compressed = new byte[(int) arcFile.length()];
            try (FileInputStream fis = new FileInputStream(arcFile)) {
                fis.read(compressed);
            }
            byte[] data = nativeDecompress(compressed);
            if (data == null || data.length == 0) return "ERR:解压结果为空";
            int pos = 0, count = 0;
            while (pos + 4 <= data.length) {
                int nameLen = readInt32(data, pos); pos += 4;
                if (nameLen == 0) break;
                String name = new String(data, pos, nameLen, "UTF-8");
                pos += nameLen;
                long contentLen = readInt64(data, pos); pos += 8;
                if (pos + contentLen > data.length) break;
                File outFile = new File(outputDir, name);
                outFile.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    fos.write(data, pos, (int) contentLen);
                }
                pos += (int) contentLen;
                count++;
            }
            return "OK:解压完成 " + count + " 个文件";
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            return "ERR:" + sw.toString().substring(0, Math.min(sw.toString().length(), 200));
        }
    }

    private static void writeInt32(ByteArrayOutputStream baos, int v) {
        baos.write((v >> 24) & 0xFF); baos.write((v >> 16) & 0xFF);
        baos.write((v >> 8) & 0xFF);  baos.write(v & 0xFF);
    }

    private static void writeInt64(ByteArrayOutputStream baos, long v) {
        writeInt32(baos, (int)(v >> 32)); writeInt32(baos, (int)v);
    }

    private static int readInt32(byte[] data, int pos) {
        return ((data[pos] & 0xFF) << 24) | ((data[pos+1] & 0xFF) << 16)
             | ((data[pos+2] & 0xFF) << 8)  | (data[pos+3] & 0xFF);
    }

    private static long readInt64(byte[] data, int pos) {
        return ((long)readInt32(data, pos) << 32) | (readInt32(data, pos+4) & 0xFFFFFFFFL);
    }
}
