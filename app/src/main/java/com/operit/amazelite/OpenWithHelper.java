package com.operit.amazelite;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.CheckBox;
import android.widget.Button;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class OpenWithHelper {

    private static final String PREFS_NAME = "amazelite_open_with";

    private final MainActivity activity;

    public OpenWithHelper(MainActivity activity) {
        this.activity = activity;
    }

    // ========== 文件类型判断 ==========

    public static boolean isTextFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log")
            || lower.endsWith(".java") || lower.endsWith(".py") || lower.endsWith(".js")
            || lower.endsWith(".json") || lower.endsWith(".xml") || lower.endsWith(".html")
            || lower.endsWith(".htm") || lower.endsWith(".css") || lower.endsWith(".c")
            || lower.endsWith(".cpp") || lower.endsWith(".h") || lower.endsWith(".hpp")
            || lower.endsWith(".cs") || lower.endsWith(".go") || lower.endsWith(".rs")
            || lower.endsWith(".rb") || lower.endsWith(".php") || lower.endsWith(".sh")
            || lower.endsWith(".bash") || lower.endsWith(".zsh") || lower.endsWith(".bat")
            || lower.endsWith(".ps1") || lower.endsWith(".sql") || lower.endsWith(".yaml")
            || lower.endsWith(".yml") || lower.endsWith(".toml") || lower.endsWith(".ini")
            || lower.endsWith(".cfg") || lower.endsWith(".conf") || lower.endsWith(".properties")
            || lower.endsWith(".gradle") || lower.endsWith(".cmake") || lower.endsWith(".makefile")
            || lower.endsWith(".csv") || lower.endsWith(".tsv")
            || lower.endsWith(".kt") || lower.endsWith(".swift") || lower.endsWith(".dart")
            || lower.endsWith(".lua") || lower.endsWith(".pl") || lower.endsWith(".r")
            || lower.endsWith(".tex") || lower.endsWith(".diff") || lower.endsWith(".patch");
    }

    public static boolean isImageFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
            || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp")
            || lower.endsWith(".svg");
    }

    public static boolean canViewInternally(String name) {
        return isTextFile(name) || isImageFile(name);
    }

    public static String getFileExt(String name) {
        if (name == null || !name.contains(".")) return "";
        return name.substring(name.lastIndexOf(".")).toLowerCase();
    }

    // ========== MIME 类型（补全版） ==========

    public static String getMimeTypeFull(String name) {
        final AlertDialog[] dialogHolder = new AlertDialog[1];
        String ext = getFileExt(name);
        switch (ext) {
            // 图片
            case ".jpg": case ".jpeg": return "image/jpeg";
            case ".png": return "image/png";
            case ".gif": return "image/gif";
            case ".bmp": return "image/bmp";
            case ".webp": return "image/webp";
            case ".svg": return "image/svg+xml";
            // 视频
            case ".mp4": return "video/mp4";
            case ".mkv": return "video/x-matroska";
            case ".avi": return "video/x-msvideo";
            case ".mov": return "video/quicktime";
            case ".wmv": return "video/x-ms-wmv";
            case ".flv": case ".flc": return "video/x-flv";
            case ".3gp": return "video/3gpp";
            // 音频
            case ".mp3": return "audio/mpeg";
            case ".wav": return "audio/wav";
            case ".flac": return "audio/flac";
            case ".aac": return "audio/aac";
            case ".ogg": return "audio/ogg";
            case ".wma": return "audio/x-ms-wma";
            case ".m4a": return "audio/mp4";
            // 文档
            case ".pdf": return "application/pdf";
            case ".doc": return "application/msword";
            case ".docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".xls": return "application/vnd.ms-excel";
            case ".xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case ".ppt": return "application/vnd.ms-powerpoint";
            case ".pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            // 网页
            case ".html": case ".htm": return "text/html";
            // 文本
            case ".txt": return "text/plain";
            case ".csv": return "text/csv";
            case ".json": return "application/json";
            case ".xml": return "application/xml";
            case ".yaml": case ".yml": return "text/yaml";
            case ".md": return "text/markdown";
            // 压缩
            case ".zip": return "application/zip";
            case ".7z": return "application/x-7z-compressed";
            case ".rar": return "application/vnd.rar";
            case ".zpaq": return "application/x-zpaq";
            case ".tar": return "application/x-tar";
            case ".gz": case ".gzip": return "application/gzip";
            case ".bz2": return "application/x-bzip2";
            case ".xz": return "application/x-xz";
            // 安装包
            case ".apk": return "application/vnd.android.package-archive";
            default: return "*/*";
        }
    }

    // ========== 记住选择 ==========

    private SharedPreferences getPrefs() {
        return activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getRememberedChoice(String ext) {
        return getPrefs().getString(ext, null);
    }

    public void saveRememberedChoice(String ext, String value) {
        getPrefs().edit().putString(ext, value).apply();
    }

    public void clearRememberedChoice(String ext) {
        getPrefs().edit().remove(ext).apply();
    }

    public void clearAllRemembered() {
        getPrefs().edit().clear().apply();
    }

    // ========== 查询可打开的应用（多方式合并去重） ==========

    public List<ResolveInfo> queryAppsForFile(String path, String mime) {
        PackageManager pm = activity.getPackageManager();
        String myPkg = activity.getPackageName();
        java.util.LinkedHashMap<String, ResolveInfo> strongMatches = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, ResolveInfo> weakMatches = new java.util.LinkedHashMap<>();

        // 方式1: content:// + mime（最可靠）
        try {
            File file = new File(path);
            Uri uri = FileProvider.getUriForFile(activity, myPkg + ".fileprovider", file);
            Intent i1 = new Intent(Intent.ACTION_VIEW);
            i1.setDataAndType(uri, mime);
            i1.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            List<ResolveInfo> list = pm.queryIntentActivities(i1, 0);
            for (ResolveInfo ri : list) {
                if (ri.activityInfo == null || myPkg.equals(ri.activityInfo.packageName)) continue;
                strongMatches.put(ri.activityInfo.packageName + "/" + ri.activityInfo.name, ri);
            }
        } catch (Exception ignored) {}

        // 方式2: file:// + mime（兼容一些旧应用）
        try {
            Intent i2 = new Intent(Intent.ACTION_VIEW);
            i2.setDataAndType(Uri.fromFile(new File(path)), mime);
            i2.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            List<ResolveInfo> list = pm.queryIntentActivities(i2, 0);
            for (ResolveInfo ri : list) {
                if (ri.activityInfo == null || myPkg.equals(ri.activityInfo.packageName)) continue;
                String key = ri.activityInfo.packageName + "/" + ri.activityInfo.name;
                if (!strongMatches.containsKey(key)) strongMatches.put(key, ri);
            }
        } catch (Exception ignored) {}

        // 方式3: 仅 mime，不带 data（弱匹配，只有前两种没查到时才补充）
        try {
            Intent i3 = new Intent(Intent.ACTION_VIEW);
            i3.setType(mime);
            List<ResolveInfo> list = pm.queryIntentActivities(i3, 0);
            for (ResolveInfo ri : list) {
                if (ri.activityInfo == null || myPkg.equals(ri.activityInfo.packageName)) continue;
                String key = ri.activityInfo.packageName + "/" + ri.activityInfo.name;
                if (!strongMatches.containsKey(key)) weakMatches.put(key, ri);
            }
        } catch (Exception ignored) {}

        List<ResolveInfo> result = new ArrayList<>(strongMatches.values());
        if (result.isEmpty()) {
            result.addAll(weakMatches.values());
        }
        return result;
}
    // ========== 用指定应用打开文件 ==========

    public void openWithApp(String path, String mime, ResolveInfo ri) {
        try {
            File file = new File(path);
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
            intent.setDataAndType(uri, mime);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (Exception e) {
            activity.showToast("无法打开: " + e.getMessage());
        }
    }

    // ========== 用系统默认打开（fallback） ==========

    public void openWithSystem(String path, String mime) {
        try {
            File file = new File(path);
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (Exception e) {
            activity.showToast("无法打开此文件");
        }
    }

    // ========== MD风格：应用选择器对话框 ==========

    public void showAppChooserDialog(String path, String name) {
        String mime = getMimeTypeFull(name);
        final AlertDialog[] dialogHolder = new AlertDialog[1];
        String ext = getFileExt(name);
        List<ResolveInfo> apps = queryAppsForFile(path, mime);
        boolean canInternal = canViewInternally(name);
        String remembered = getRememberedChoice(ext);

        LinearLayout container = activity.createDialogContainer();

        // 标题说明
        TextView tvHint = new TextView(activity);
        tvHint.setText("选择打开方式");
        tvHint.setTextSize(13f);
        tvHint.setTextColor(0xFF78909C);
        tvHint.setPadding(0, activity.dp(4), 0, activity.dp(8));
        container.addView(tvHint);

        // 已记住提示
        if (remembered != null) {
            TextView tvR = new TextView(activity);
            tvR.setText("已记住: " + getRememberedLabel(remembered));
            tvR.setTextSize(12f);
            tvR.setTextColor(0xFF1565C0);
            tvR.setPadding(0, 0, 0, activity.dp(8));
            container.addView(tvR);
        }

        // 分隔线
        View divider = new View(activity);
        divider.setBackgroundColor(0xFFE0E0E0);
        container.addView(divider, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(1)));
        // “记住选择”选项放在列表上方，避免被长列表挤到屏幕外
        CheckBox cbRemember = new CheckBox(activity);
        cbRemember.setText("记住此后缀的选择");
        cbRemember.setTextColor(0xFF37474F);
        cbRemember.setTextSize(14f);
        cbRemember.setPadding(0, activity.dp(8), 0, activity.dp(8));
        container.addView(cbRemember);

        // ScrollView 包裹应用列表
        ScrollView scroll = new ScrollView(activity);
        scroll.setPadding(0, activity.dp(4), 0, activity.dp(4));
        LinearLayout listContainer = new LinearLayout(activity);
        listContainer.setOrientation(LinearLayout.VERTICAL);

        // AmazeLite 内置查看选项
        if (canInternal) {
            View internalItem = createAppItem(
                "AmazeLite 内置查看",
                isTextFile(name) ? "文本查看器" : "图片查看器",
                null,
                v -> {
                    if (cbRemember.isChecked()) rememberChoice(ext, "internal");
                    viewInAmazeLite(path, name);
                    dialogHolder[0].dismiss();
                },
                remembered != null && remembered.equals("internal")
            );
            listContainer.addView(internalItem);
        }

        // 已安装的应用列表
        for (ResolveInfo ri : apps) {
            Drawable icon = null;
            try { icon = ri.activityInfo.loadIcon(activity.getPackageManager()); } catch (Exception ignored) {}
            String pkgAct = ri.activityInfo.packageName + "/" + ri.activityInfo.name;
            View appItem = createAppItem(
                ri.activityInfo.loadLabel(activity.getPackageManager()).toString(),
                ri.activityInfo.packageName,
                icon,
                v -> {
                    if (cbRemember.isChecked()) rememberChoice(ext, pkgAct);
                    openWithApp(path, mime, ri);
                    dialogHolder[0].dismiss();
                },
                remembered != null && remembered.equals(pkgAct)
            );
            listContainer.addView(appItem);
        }

        // 系统默认选项（始终显示在最后）
        View systemItem = createAppItem(
            "系统默认",
            "使用系统默认应用打开",
            activity.getPackageManager().getDefaultActivityIcon(),
            v -> {
                if (cbRemember.isChecked()) clearRememberedChoice(ext);
                openWithSystem(path, mime);
                dialogHolder[0].dismiss();
            },
            false
        );
        listContainer.addView(systemItem);

        scroll.addView(listContainer);
        container.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(320)));


        // 底部按钮行
        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(0, activity.dp(12), 0, 0);

        // 清除记忆按钮
        if (remembered != null) {
            Button btnClear = new Button(activity);
            btnClear.setText("清除记忆");
            btnClear.setTextColor(0xFFE53935);
            btnClear.setTextSize(13f);
            btnClear.setAllCaps(false);
            btnClear.setBackground(null);
            btnClear.setMinHeight(0);
            btnClear.setMinimumHeight(0);
            btnClear.setPadding(activity.dp(8), activity.dp(4), activity.dp(8), activity.dp(4));
            btnClear.setOnClickListener(v -> {
                clearRememberedChoice(ext);
                activity.showToast("已清除记忆");
                dialogHolder[0].dismiss();
            });
            btnRow.addView(btnClear);
        }

        // 取消按钮
        Button btnCancel = new Button(activity);
        btnCancel.setText("取消");
        btnCancel.setTextColor(0xFF546E7A);
        btnCancel.setTextSize(14f);
        btnCancel.setAllCaps(false);
        btnCancel.setBackground(null);
        btnCancel.setMinHeight(0);
        btnCancel.setMinimumHeight(0);
        btnCancel.setPadding(activity.dp(12), activity.dp(4), activity.dp(12), activity.dp(4));
        btnCancel.setOnClickListener(v -> dialogHolder[0].dismiss());
        btnRow.addView(btnCancel);

        container.addView(btnRow, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        dialogHolder[0] = activity.createBaseDialog("打开方式", container)
            .setCancelable(true)
            .create();


        // 点击应用时处理记住逻辑
        // 因为 onClick 在 createAppItem 里直接执行了，我们需要一个包装来处理记住
        // 改用另一种方式：给每个 item 的 tag 存一个 Runnable，实际点击时先处理记住再执行

        dialogHolder[0].show();
        activity.styleDialogButtons(dialogHolder[0]);
    }

    // MD风格列表项
    private View createAppItem(String label, String subLabel, Drawable icon,
                               View.OnClickListener onClick, boolean isCurrent) {
        LinearLayout item = new LinearLayout(activity);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(activity.dp(4), activity.dp(10), activity.dp(4), activity.dp(10));

        // Ripple 背景
        try {
            android.util.TypedValue tv = new android.util.TypedValue();
            if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    item.setForeground(activity.getDrawable(tv.resourceId));
                } else {
                    item.setBackgroundResource(tv.resourceId);
                }
            }
        } catch (Exception ignored) {}
        item.setClickable(true);
        item.setFocusable(true);

        // 图标
        ImageView ivIcon = new ImageView(activity);
        int iconSize = activity.dp(40);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.setMarginEnd(activity.dp(12));
        ivIcon.setLayoutParams(iconLp);
        if (icon != null) {
            ivIcon.setImageDrawable(icon);
        } else {
            // AmazeLite 内置图标用首字母+色块
            ivIcon.setImageResource(android.R.drawable.ic_menu_view);
        }
        item.addView(ivIcon);

        // 文本区
        LinearLayout textArea = new LinearLayout(activity);
        textArea.setOrientation(LinearLayout.VERTICAL);
        textArea.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvLabel = new TextView(activity);
        tvLabel.setText(label);
        tvLabel.setTextSize(15f);
        tvLabel.setTextColor(isCurrent ? 0xFF1565C0 : 0xFF212121);
        tvLabel.setTypeface(null, isCurrent ? Typeface.BOLD : Typeface.NORMAL);
        textArea.addView(tvLabel);

        TextView tvSub = new TextView(activity);
        tvSub.setText(subLabel);
        tvSub.setTextSize(12f);
        tvSub.setTextColor(0xFF78909C);
        textArea.addView(tvSub);

        item.addView(textArea);

        // 已选中标记
        if (isCurrent) {
            ImageView ivCheck = new ImageView(activity);
            ivCheck.setImageResource(android.R.drawable.checkbox_on_background);
            ivCheck.setLayoutParams(new LinearLayout.LayoutParams(
                activity.dp(24), activity.dp(24)));
            // 实际设备可能没有 checkbox_on_background，改用文本
        }
        if (isCurrent) {
            TextView tvCheck = new TextView(activity);
            tvCheck.setText("✓");
            tvCheck.setTextSize(18f);
            tvCheck.setTextColor(0xFF1565C0);
            tvCheck.setTypeface(null, Typeface.BOLD);
            tvCheck.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(
                activity.dp(32), activity.dp(32));
            tvCheck.setLayoutParams(checkLp);
            item.addView(tvCheck);
        }

        // 点击事件
        item.setOnClickListener(onClick);

        // 分隔线
        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(item);
        View sep = new View(activity);
        sep.setBackgroundColor(0xFFEEEEEE);
        wrapper.addView(sep, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(1)));

        return wrapper;
    }

    // ========== 获取记住选择的标签 ==========

    private void rememberChoice(String ext, String value) {
        if (ext == null || ext.isEmpty()) return;
        saveRememberedChoice(ext, value);
    }

    public String getRememberedLabel(String value) {
        if (value == null) return "";
        if (value.equals("internal")) return "AmazeLite 内置查看";
        try {
            String[] parts = value.split("/");
            if (parts.length >= 2) {
                PackageManager pm = activity.getPackageManager();
                Intent i = new Intent();
                i.setComponent(new ComponentName(parts[0], parts[1]));
                ResolveInfo ri = pm.resolveActivity(i, 0);
                if (ri != null) return ri.activityInfo.loadLabel(pm).toString();
            }
        } catch (Exception ignored) {}
        return value;
    }

    // ========== AmazeLite 内置查看器 ==========

    public void viewInAmazeLite(String path, String name) {
        if (isTextFile(name)) {
            showTextFileDialog(path, name);
        } else if (isImageFile(name)) {
            showImageDialog(path, name);
        }
    }

    private void showTextFileDialog(String path, String name) {
        LinearLayout container = activity.createDialogContainer();

        // 读取文件（限制512KB）
        final int MAX_SIZE = 512 * 1024;
        String content;
        try {
            File f = new File(path);
            long len = f.length();
            int readLen = (int) Math.min(len, MAX_SIZE);
            byte[] buf = new byte[readLen];
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            int read = fis.read(buf);
            fis.close();
            content = new String(buf, 0, read, "UTF-8");
            if (len > MAX_SIZE) content += "\n--- 文件过大，已截断 (" + len + " bytes) ---";
        } catch (Exception e) {
            content = "读取失败: " + e.getMessage();
        }

        // 文件信息头
        TextView tvTitle = new TextView(activity);
        tvTitle.setText(name);
        tvTitle.setTextSize(12f);
        tvTitle.setTextColor(0xFF78909C);
        container.addView(tvTitle);

        // ScrollView + TextView
        ScrollView scroll = new ScrollView(activity);
        scroll.setPadding(0, activity.dp(8), 0, 0);
        TextView tvContent = new TextView(activity);
        tvContent.setText(content);
        tvContent.setTextSize(13f);
        tvContent.setTextColor(0xFF263238);
        tvContent.setTypeface(Typeface.MONOSPACE);
        tvContent.setLineSpacing(activity.dp(2), 1.0f);
        scroll.addView(tvContent);
        container.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(400)));

        AlertDialog dialog = activity.createBaseDialog("文本查看", container)
            .setNegativeButton("关闭", null)
            .create();
        dialog.show();
        activity.styleDialogButtons(dialog);
    }

    private void showImageDialog(String path, String name) {
        LinearLayout container = activity.createDialogContainer();

        // 加载图片
        ImageView iv = new ImageView(activity);
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);
            int scale = 1;
            if (opts.outWidth > 2048 || opts.outHeight > 2048) {
                scale = Math.max(opts.outWidth, opts.outHeight) / 2048 + 1;
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = scale;
            Bitmap bmp = BitmapFactory.decodeFile(path, opts);
            if (bmp != null) {
                iv.setImageBitmap(bmp);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setAdjustViewBounds(true);
            } else {
                iv.setImageResource(android.R.drawable.ic_menu_gallery);
            }
        } catch (Exception e) {
            iv.setImageResource(android.R.drawable.ic_menu_report_image);
        }
        iv.setMaxHeight(activity.dp(400));
        container.addView(iv, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 尺寸信息
        try {
            BitmapFactory.Options opts2 = new BitmapFactory.Options();
            opts2.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts2);
            if (opts2.outWidth > 0) {
                TextView tvSize = new TextView(activity);
                tvSize.setText(opts2.outWidth + " × " + opts2.outHeight);
                tvSize.setTextSize(12f);
                tvSize.setTextColor(0xFF78909C);
                tvSize.setGravity(Gravity.CENTER);
                tvSize.setPadding(0, activity.dp(4), 0, 0);
                container.addView(tvSize);
            }
        } catch (Exception ignored) {}

        AlertDialog dialog = activity.createBaseDialog(name, container)
            .setNegativeButton("关闭", null)
            .create();
        dialog.show();
        activity.styleDialogButtons(dialog);
    }
}
