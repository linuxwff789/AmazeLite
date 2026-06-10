package com.operit.amazelite;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class ZpaqTest {
    public static String runZpaq(String cmdLineWithArgs) {
        try {
            // 手动解析参数（保留引号内的空格）
            List<String> args = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean inQuote = false;
            for (char c : cmdLineWithArgs.toCharArray()) {
                if (c == '"') { inQuote = !inQuote; }
                else if (c == ' ' && !inQuote) {
                    if (current.length() > 0) {
                        args.add(current.toString());
                        current = new StringBuilder();
                    }
                } else {
                    current.append(c);
                }
            }
            if (current.length() > 0) args.add(current.toString());
            
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int ret = p.waitFor();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder out = new StringBuilder();
            out.append("[exit=").append(ret).append("] ");
            String l;
            while ((l = br.readLine()) != null) {
                out.append(l).append("\n");
            }
            br.close();
            return out.toString();
        } catch (Exception e) {
            return "EXCEPTION: " + e.getMessage();
        }
    }
}
