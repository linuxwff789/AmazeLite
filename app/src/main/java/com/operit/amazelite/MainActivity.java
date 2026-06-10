package com.operit.amazelite;
import com.operit.zpaq.ZPAQNative;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {

    private ListView listView, lvSuggest;
    private android.widget.ArrayAdapter<String> suggestAdapter;
    private Button btnBack, btnForward, btnGo, btnGrant;
    private Button btnCut, btnCopy, btnPaste, btnDelete, btnRename, btnNewFolder, btnInfo;
    private Button btnDetail, btnSort;
    private EditText etPath;
    private FileAdapter adapter;
    private List<FileItem> fileList = new ArrayList<>();
    private String currentPath = "/storage/emulated/0";
    private java.util.Stack<String> backStack = new java.util.Stack<>();
    private java.util.Stack<String> forwardStack = new java.util.Stack<>();
    private Set<String> selectedPaths = new HashSet<>();
    private List<String> clipboardPaths = new ArrayList<>();
    private boolean clipboardIsCut = false;
    private boolean detailMode = false;
    private boolean archivePreviewFullDetails = false;
    private boolean zpaqVersionSortAscending = false; // 版本列表排序：false=降序(最新在上)
    private String archivePreviewZpaqUntil = "";
    private java.util.List<ZPAQNative.ArchiveVersion> archivePreviewZpaqVersions = new ArrayList<>();
    private java.util.concurrent.atomic.AtomicBoolean alreadyHandled = new java.util.concurrent.atomic.AtomicBoolean(false);
    private int sortMode = 0; // 0=名称升序, 1=名称降序, 2=大小升序, 3=大小降序, 4=日期升序, 5=日期降序
    private String[] sortNames = {"名称↑","名称↓","大小↑","大小↓","日期↑","日期↓"};
    private boolean hasRoot = false;
    private android.text.TextWatcher pathWatcher;

    private static final int REQUEST_MANAGE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
            listView = findViewById(R.id.listView);
            lvSuggest = findViewById(R.id.lvSuggest);
            suggestAdapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new java.util.ArrayList<>());
            lvSuggest.setAdapter(suggestAdapter);
            lvSuggest.setVisibility(android.view.View.GONE);
            btnBack = findViewById(R.id.btnBack);
            btnForward = findViewById(R.id.btnForward);
            btnGo = findViewById(R.id.btnGo);
            btnGrant = findViewById(R.id.btnGrant);
            btnCut = findViewById(R.id.btnCut);
            btnCopy = findViewById(R.id.btnCopy);
            btnPaste = findViewById(R.id.btnPaste);
            btnDelete = findViewById(R.id.btnDelete);
            btnRename = findViewById(R.id.btnRename);
            btnNewFolder = findViewById(R.id.btnNewFolder);
            btnInfo = findViewById(R.id.btnInfo);
            btnDetail = findViewById(R.id.btnDetail);
            btnSort = findViewById(R.id.btnSort);
            etPath = findViewById(R.id.etPath);

            adapter = new FileAdapter();
            listView.setAdapter(adapter);

            new Thread(() -> {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
                    int ret = p.waitFor();
                    hasRoot = (ret == 0);
                } catch (Exception e) { hasRoot = false; }
                runOnUiThread(() -> navigateTo(currentPath));
            }).start();

            btnGrant.setOnClickListener(v -> {
                if (Build.VERSION.SDK_INT >= 30) {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(i, REQUEST_MANAGE);
                }
            });

            listView.setOnTouchListener((v, event) -> {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    lvSuggest.setVisibility(android.view.View.GONE);
                }
                return false;
            });
            listView.setOnItemClickListener((p, v, pos, id) -> {
        FileItem item = fileList.get(pos);
        // 如果已选中，取消选中
        if (item.selected) {
            item.selected = false;
            selectedPaths.remove(item.path);
            adapter.notifyDataSetChanged();
            updateButtonState();
            return;
        }
        // 没有选中任何文件时，目录进目录，文件打开
        if (selectedPaths.isEmpty()) {
            if (item.isDir) {
                backStack.push(currentPath);
                forwardStack.clear();
                navigateTo(item.path);
            } else {
                openFile(item);
            }
        } else {
            // 有选中文件时，点击选中/取消选中
            item.selected = true;
            selectedPaths.add(item.path);
            adapter.notifyDataSetChanged();
            updateButtonState();
        }
    });

            listView.setOnItemLongClickListener((p, v, pos, id) -> {
                FileItem item = fileList.get(pos);
                String p2 = item.path;
                if (selectedPaths.contains(p2)) {
                    selectedPaths.remove(p2);
                    item.selected = false;
                } else {
                    selectedPaths.add(p2);
                    item.selected = true;
                }
                adapter.notifyDataSetChanged();
                updateButtonState();
                showContextMenu(listView, item);
                return true;
            });

            btnBack.setOnClickListener(v -> {
                if (!backStack.isEmpty()) {
                    forwardStack.push(currentPath);
                    navigateTo(backStack.pop());
                } else showToast("已在根目录");
            });
            btnForward.setOnClickListener(v -> {
                if (!forwardStack.isEmpty()) {
                    backStack.push(currentPath);
                    navigateTo(forwardStack.pop());
                } else showToast("已到末尾");
            });
            btnCut.setOnClickListener(v -> {
                if (!selectedPaths.isEmpty()) {
                    clipboardPaths = new ArrayList<>(selectedPaths);
                    clipboardIsCut = true;
                    showToast("已剪切 " + clipboardPaths.size() + " 项");
                } else showToast("请先选择文件");
            });
            btnCopy.setOnClickListener(v -> {
                if (!selectedPaths.isEmpty()) {
                    clipboardPaths = new ArrayList<>(selectedPaths);
                    clipboardIsCut = false;
                    showToast("已复制 " + clipboardPaths.size() + " 项");
                } else showToast("请先选择文件");
            });
            btnPaste.setOnClickListener(v -> pasteFiles());
            btnDelete.setOnClickListener(v -> deleteFiles());
            btnRename.setOnClickListener(v -> showRenameDialog());
            btnNewFolder.setOnClickListener(v -> showNewFolderDialog());
            btnInfo.setOnClickListener(v -> showFileInfo());

            btnDetail.setOnClickListener(v -> {
                detailMode = !detailMode;
                btnDetail.setText(detailMode ? "详细:开" : "详细:关");
                adapter.notifyDataSetChanged();
            });
            btnSort.setOnClickListener(v -> {
                sortMode = (sortMode + 1) % sortNames.length;
                btnSort.setText("排序:" + sortNames[sortMode]);
                sortFiles();
                adapter.notifyDataSetChanged();
            });

            pathWatcher = new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                public void afterTextChanged(android.text.Editable s) {
                    String input = s.toString().trim();
                    if (input.isEmpty()) {
                        lvSuggest.setVisibility(android.view.View.GONE);
                        return;
                    }
                    // 找到输入路径的父目录部分
                    String dirPart = input;
                    String prefix = "";
                    int lastSlash = input.lastIndexOf('/');
                    if (lastSlash >= 0) {
                        dirPart = input.substring(0, lastSlash);
                        if (dirPart.isEmpty()) dirPart = "/";
                        prefix = input.substring(lastSlash + 1).toLowerCase();
                    }
                    // 扫描父目录匹配子项
                    java.util.ArrayList<String> matches = new java.util.ArrayList<>();
                    File parent = new File(dirPart);
                    if (parent.isDirectory()) {
                        File[] children = parent.listFiles();
                        if (children != null) {
                            for (File child : children) {
                                if (child.isDirectory()) {
                                    String name = child.getName();
                                    if (name.toLowerCase().startsWith(prefix)) {
                                        String fullPath = input.substring(0, input.length() - prefix.length()) + name + "/";
                                        matches.add(fullPath);
                                    }
                                }
                            }
                        }
                    }
                    if (matches.isEmpty()) {
                        lvSuggest.setVisibility(android.view.View.GONE);
                    } else {
                        suggestAdapter.clear();
                        suggestAdapter.addAll(matches);
                        suggestAdapter.notifyDataSetChanged();
                        lvSuggest.setVisibility(android.view.View.VISIBLE);
                    }
                }
            };
            etPath.addTextChangedListener(pathWatcher);
            etPath.setOnEditorActionListener((v, actionId, event) -> {
                String p = etPath.getText().toString().trim();
                if (!p.isEmpty()) {
                    backStack.push(currentPath);
                    forwardStack.clear();
                    navigateTo(p);
                }
                return true;
            });
            lvSuggest.setOnItemClickListener((p, v, pos, id) -> {
                String sel = suggestAdapter.getItem(pos);
                if (sel != null) {
                    etPath.setText(sel);
                    lvSuggest.setVisibility(android.view.View.GONE);
                    backStack.push(currentPath);
                    forwardStack.clear();
                    navigateTo(sel);
                }
            });
            btnGo.setOnClickListener(v -> {
                etPath.requestFocus();
                CharSequence cs = etPath.getText();
                String p = (cs != null ? cs.toString() : "").trim();
                if (p.isEmpty()) {
                    showToast("请输入路径");
                } else {
                    lvSuggest.setVisibility(android.view.View.GONE);
                    backStack.push(currentPath);
                    forwardStack.clear();
                    navigateTo(p);
                }
            });
            updateButtonState();
        } catch (Exception e) {
            showToast("启动错误: " + e.getMessage());
        }
    }

    private AlertDialog showMaterialMessageDialog(String title, CharSequence message, String positiveText) {
        LinearLayout container = createDialogContainer();
        if (message != null && message.length() > 0) {
            container.addView(createDialogMessageView(message));
        }
        AlertDialog dialog = createBaseDialog(title, container)
            .setPositiveButton(positiveText == null ? "确定" : positiveText, null)
            .create();
        dialog.show();
        styleDialogButtons(dialog);
        return dialog;
    }

    private AlertDialog.Builder createBaseDialog(String title, View content) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        if (content != null) builder.setView(content);
        return builder;
    }

    private LinearLayout createDialogContainer() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        layout.setPadding(pad, dp(12), pad, dp(4));
        return layout;
    }

    private TextView createDialogMessageView(CharSequence text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14f);
        tv.setTextColor(0xFF263238);
        tv.setLineSpacing(dp(2), 1.05f);
        return tv;
    }

    private EditText createDialogEditText(String hint, String value) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(value == null ? "" : value);
        et.setTextSize(15f);
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF8FAFC);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0xFFD7DEE8);
        et.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        et.setLayoutParams(lp);
        return et;
    }

    private Button createDialogActionButton(String text, int textColor, int fillColor) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(textColor);
        btn.setAllCaps(false);
        btn.setTypeface(null, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fillColor);
        bg.setCornerRadius(dp(18));
        btn.setBackground(bg);
        btn.setMinHeight(dp(40));
        btn.setPadding(dp(18), dp(10), dp(18), dp(10));
        return btn;
    }

    private LinearLayout createDialogButtonRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        row.setLayoutParams(lp);
        return row;
    }

    private void styleDialogButtons(AlertDialog dialog) {
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (positive != null) {
            positive.setTextColor(0xFF1565C0);
            positive.setAllCaps(false);
        }
        if (negative != null) {
            negative.setTextColor(0xFF546E7A);
            negative.setAllCaps(false);
        }
        if (neutral != null) {
            neutral.setTextColor(0xFF546E7A);
            neutral.setAllCaps(false);
        }
    }

    private AlertDialog createProgressPanel(String title, ProgressBar progressBar, TextView... textViews) {
        LinearLayout layout = createDialogContainer();
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(20)));
        layout.addView(progressBar);
        for (TextView textView : textViews) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            textView.setLayoutParams(lp);
            textView.setTextColor(0xFF455A64);
            layout.addView(textView);
        }
        return createBaseDialog(title, layout).setCancelable(false).create();
    }

    private CheckBox createDialogCheckBox(String text, boolean checked) {
        CheckBox cb = new CheckBox(this);
        cb.setText(text);
        cb.setChecked(checked);
        cb.setTextColor(0xFF263238);
        cb.setPadding(0, 0, 0, dp(8));
        return cb;
    }

    private LinearLayout.LayoutParams createWeightedButtonLayoutParams(boolean first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (!first) lp.leftMargin = dp(10);
        return lp;
    }

    private String formatArchiveDialogTitle(String title, int count) {
        return title + " (" + count + " 项)";
    }

    private String formatSelectionSummary() {
        return "已选中 " + selectedPaths.size() + " 项";
    }

    private String getArchiveEntrySummary(ArchiveNode node) {
        return archivePreviewFullDetails ? node.fullDetailText : node.detailText;
    }

    private String getArchiveFileDialogSummary(ArchiveNode node) {
        String summary = getArchiveEntrySummary(node);
        return summary == null ? "" : summary;
    }

    private void showArchiveFileExtractMenu(String arcPath, ArchiveNode selectedNode) {
        String entryPath = selectedNode.fullPath;
        String fileName = new File(entryPath).getName();
        String summary = getArchiveFileDialogSummary(selectedNode);
        LinearLayout fileDialogContainer = createDialogContainer();
        fileDialogContainer.addView(createDialogMessageView(summary.isEmpty() ? "无可显示的元数据" : summary));
        AlertDialog.Builder eb = createBaseDialog(fileName, fileDialogContainer);
        eb.setItems(new String[]{"提取到原始路径", "提取到当前目录"}, (e, wi) -> {
            String outDir;
            if (wi == 0) {
                // 提取到原始路径：取 entryPath 的父目录
                File entryFile = new File("/" + entryPath);
                outDir = entryFile.getParent() != null ? entryFile.getParent() : currentPath;
            } else {
                outDir = currentPath;
            }
            new File(outDir).mkdirs();
            final String extractDir = outDir;
            new Thread(() -> {
                try {
                    if (arcPath.toLowerCase().endsWith(".zip")) {
                        extractOneFromZip(arcPath, entryPath, extractDir);
                    } else if (arcPath.toLowerCase().endsWith(".zpaq")) {
                        extractOneFromZpaq(arcPath, entryPath, extractDir);
                    } else {
                        execSilent("7z x \"" + arcPath + "\" \"" + entryPath + "\" -o\"" + extractDir + "\" -y");
                    }
                    runOnUiThread(() -> {
                        showToast("提取完成: " + fileName);
                        navigateTo(currentPath);
                    });
                } catch (Exception ex) {
                    runOnUiThread(() -> showToast("提取失败: " + ex.getMessage()));
                }
            }).start();
        });
        eb.setNegativeButton("取消", null);
        AlertDialog fileDialog = eb.create();
        fileDialog.show();
        styleDialogButtons(fileDialog);
    }

    private String nonNull(String value) {
        return value == null ? "" : value;
    }

    private String buildDialogTitleWithName(String prefix, String name) {
        return prefix + name;
    }

    private void showMaterialInfoDialog(String title, CharSequence message) {
        showMaterialMessageDialog(title, message, "确定");
    }

    private TextView createDialogSectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(0xFF37474F);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView createDialogValueText(String text, int gravity) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setTextColor(0xFF546E7A);
        tv.setGravity(gravity);
        return tv;
    }

    private android.widget.RadioButton createDialogRadioButton(String text, int id) {
        android.widget.RadioButton rb = new android.widget.RadioButton(this);
        rb.setText(text);
        rb.setId(id);
        rb.setTextColor(0xFF263238);
        return rb;
    }

    private android.widget.SeekBar createDialogSeekBar(int max, int progress) {
        android.widget.SeekBar seekBar = new android.widget.SeekBar(this);
        seekBar.setMax(max);
        seekBar.setProgress(progress);
        return seekBar;
    }

    private ProgressBar createHorizontalProgressBar(int max) {
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(max);
        return pb;
    }

    private void addActionButtonsToDialogContainer(LinearLayout container, Button... buttons) {
        LinearLayout row = createDialogButtonRow();
        for (int i = 0; i < buttons.length; i++) {
            Button button = buttons[i];
            button.setLayoutParams(createWeightedButtonLayoutParams(i == 0));
            row.addView(button);
        }
        container.addView(row);
    }

    private AlertDialog showStyledDialog(String title, View content, boolean cancelable) {
        AlertDialog dialog = createBaseDialog(title, content).setCancelable(cancelable).create();
        dialog.show();
        styleDialogButtons(dialog);
        return dialog;
    }

    private AlertDialog createProgressPanelWithAction(String title, ProgressBar progressBar, Button actionButton, TextView... textViews) {
        LinearLayout layout = createDialogContainer();
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(20)));
        layout.addView(progressBar);
        for (TextView textView : textViews) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            textView.setLayoutParams(lp);
            textView.setTextColor(0xFF455A64);
            layout.addView(textView);
        }
        if (actionButton != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(16);
            actionButton.setLayoutParams(lp);
            layout.addView(actionButton);
        }
        return createBaseDialog(title, layout).setCancelable(false).create();
    }

    private String buildProcessingText(long processed, long total) {
        return "已处理 " + Formatter.formatFileSize(this, processed) + " / " + Formatter.formatFileSize(this, total);
    }

    private String buildArchiveProgressTitle(String prefix, String name) {
        return prefix + ": " + name;
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }

    private String getSingleSelectedPath() {
        if (selectedPaths.size() != 1) {
            showToast("请先选中一个文件");
            return null;
        }
        return selectedPaths.iterator().next();
    }

    private void showContextMenu(View anchor, FileItem item) {

        String[] items = {"创建副本", "重命名", "删除", "压缩ZIP", "解压到当前", "解压到同名目录", "详情", "复制路径"};
        LinearLayout container = createDialogContainer();
        container.addView(createDialogMessageView(formatSelectionSummary() + "\n目标: " + item.name));
        AlertDialog.Builder b = createBaseDialog(item.name, container);
        b.setItems(items, (d, which) -> {
            switch (which) {
                case 0: copyFile(item.path); break;
                case 1: showRenameDialog(item.path); break;
                case 2: confirmDeleteSingle(item.path); break;
                case 3:
                    selectedPaths.clear(); selectedPaths.add(item.path); zipSelected(); break;
                case 4:
                    selectedPaths.clear(); selectedPaths.add(item.path); extractArchive(item.path, currentPath); break;
                case 5:
                    selectedPaths.clear(); selectedPaths.add(item.path);
                    String dirName = item.path.replaceAll("\\.(zip|7z|rar|zpaq)$", "");
                    extractArchive(item.path, dirName); break;
                case 6: showItemInfo(item); break;
                case 7:
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("path", item.path));
                    showToast("路径已复制"); break;
            }
        });
        AlertDialog dialog = b.create();
        dialog.show();
        styleDialogButtons(dialog);
    }

    private void showRenameDialog(String path) {
        File f = new File(path);
        LinearLayout container = createDialogContainer();
        EditText et = createDialogEditText("输入新名称", f.getName());
        container.addView(et);
        AlertDialog dialog = createBaseDialog("重命名", container)
            .setPositiveButton("确定", (d, w) -> {
                String newName = et.getText().toString().trim();
                if (!newName.isEmpty()) {
                    File nf = new File(f.getParent(), newName);
                    if (f.renameTo(nf)) showToast("重命名成功");
                    else showToast("重命名失败");
                    navigateTo(currentPath);
                }
            })
            .setNegativeButton("取消", null)
            .create();
        dialog.show();
        styleDialogButtons(dialog);
    }

    private void copyFile(String path) {
        final ProgressBar pb = createHorizontalProgressBar(1000);
        final TextView tvStat = createDialogValueText("正在统计...", Gravity.CENTER_HORIZONTAL);
        final AlertDialog progressDialog = createProgressPanel("创建副本", pb, tvStat);
        progressDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "取消", (d, w) -> progressDialog.dismiss());
        progressDialog.show();

        new Thread(() -> {
            File src = new File(path);
            if (!src.exists()) {
                runOnUiThread(() -> { progressDialog.dismiss(); showToast("文件不存在"); });
                return;
            }
            // 生成副本文件名
            String name = src.getName();
            String baseName, ext;
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                baseName = name.substring(0, dot);
                ext = name.substring(dot);
            } else {
                baseName = name;
                ext = "";
            }
            File dest = new File(src.getParent(), baseName + "_副本" + ext);
            int counter = 1;
            while (dest.exists()) {
                dest = new File(src.getParent(), baseName + "_副本(" + counter + ")" + ext);
                counter++;
            }

            // 先统计总文件数和总字节数
            java.util.List<File> allFiles = new java.util.ArrayList<>();
            long totalBytes = 0;
            if (src.isDirectory()) {
                countFiles(src, allFiles);
                for (File f : allFiles) totalBytes += f.length();
            } else {
                allFiles.add(src);
                totalBytes = src.length();
            }
            final int totalCount = Math.max(allFiles.size(), 1);
            final long totalB = Math.max(totalBytes, 1L);
            final long startTime = System.currentTimeMillis();

            runOnUiThread(() -> {
                pb.setMax(1000);
                tvStat.setText("0/" + totalCount + "  0%");
            });

            final boolean[] cancelled = {false};
            progressDialog.setOnDismissListener(d -> cancelled[0] = true);
            java.util.concurrent.atomic.AtomicInteger doneCount = new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.concurrent.atomic.AtomicLong doneBytes = new java.util.concurrent.atomic.AtomicLong(0);

            try {
                if (src.isDirectory()) {
                    copyDirWithProgress(src, dest, doneCount, doneBytes, totalCount, totalB, startTime, pb, tvStat, cancelled);
                } else {
                    copyFileWithProgress(src, dest, doneCount, doneBytes, totalCount, totalB, startTime, pb, tvStat);
                }
                final File finalDest = dest;
                if (!cancelled[0]) {
                    runOnUiThread(() -> {
                        progressDialog.dismiss();
                        showToast("已创建副本: " + finalDest.getName());
                        navigateTo(currentPath);
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> { progressDialog.dismiss(); showToast("创建副本失败: " + e.getMessage()); });
            }
        }).start();
    }

    private void copyDirWithProgress(File src, File dest, java.util.concurrent.atomic.AtomicInteger doneCount,
                                      java.util.concurrent.atomic.AtomicLong doneBytes, int totalCount, long totalBytes,
                                      long startTime, ProgressBar pb, TextView tvStat, boolean[] cancelled) throws Exception {
        if (cancelled[0]) return;
        dest.mkdirs();
        File[] children = src.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (cancelled[0]) return;
            File destChild = new File(dest, child.getName());
            if (child.isDirectory()) {
                copyDirWithProgress(child, destChild, doneCount, doneBytes, totalCount, totalBytes, startTime, pb, tvStat, cancelled);
            } else {
                copyFileWithProgress(child, destChild, doneCount, doneBytes, totalCount, totalBytes, startTime, pb, tvStat);
            }
        }
    }

    private boolean copyDir(File src, File dest) {
        if (!dest.mkdirs()) return false;
        File[] children = src.listFiles();
        if (children == null) return true;
        for (File child : children) {
            File destChild = new File(dest, child.getName());
            if (child.isDirectory()) {
                if (!copyDir(child, destChild)) return false;
            } else {
                try {
                    java.io.FileInputStream in = new java.io.FileInputStream(child);
                    java.io.FileOutputStream out = new java.io.FileOutputStream(destChild);
                    byte[] buf = new byte[65536];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                    in.close();
                    out.close();
                } catch (Exception e) {
                    return false;
                }
            }
        }
        return true;
    }

    private void confirmDeleteSingle(String path) {
        AlertDialog dialog = createBaseDialog("确认删除", createDialogMessageView("确定删除 " + new File(path).getName() + "？"))
            .setPositiveButton("删除", (d, w) -> {
                final ProgressBar pb = createHorizontalProgressBar(1000);
                final TextView tvStat = createDialogValueText("正在删除...", Gravity.CENTER_HORIZONTAL);
                final AlertDialog progressDialog = createProgressPanel("删除中", pb, tvStat);
                progressDialog.show();
                new Thread(() -> {
                    java.util.List<java.io.File> allFiles = new java.util.ArrayList<>();
                    countFiles(new File(path), allFiles);
                    final int totalCount = Math.max(allFiles.size(), 1);
                    runOnUiThread(() -> {
                        pb.setMax(1000);
                        tvStat.setText("0/" + totalCount);
                    });
                    java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);
                    final boolean[] cancelled = {false};
                    progressDialog.setOnDismissListener(dis -> cancelled[0] = true);
                    rmRecursiveWithProgress(new File(path), done, totalCount, pb, tvStat);
                    runOnUiThread(() -> {
                        progressDialog.dismiss();
                        showToast("删除成功");
                        navigateTo(currentPath);
                    });
                }).start();
            })
            .setNegativeButton("取消", null)
            .create();
        dialog.show();
        styleDialogButtons(dialog);
    }

    private boolean rmRecursive(File f) {
        if (f.isDirectory()) {
            File[] sub = f.listFiles();
            if (sub != null) for (File c : sub) rmRecursive(c);
        }
        return f.delete();
    }

    private void showItemInfo(FileItem item) {
        File f = new File(item.path);
        StringBuilder sb = new StringBuilder();
        sb.append("名称: ").append(item.name).append("\n");
        sb.append("路径: ").append(item.path).append("\n");
        sb.append("大小: ").append(item.size).append(" 字节\n");
        if (detailMode) {
            sb.append("修改: ").append(item.mTime).append("\n");
            sb.append("权限: ").append(item.perm).append("\n");
            sb.append("可读: ").append(f.canRead()).append("\n");
            sb.append("可写: ").append(f.canWrite()).append("\n");
            sb.append("隐藏: ").append(f.isHidden());
        }
        showMaterialInfoDialog("文件详情", sb.toString());
    }

    private void zipSelected() {
        if (selectedPaths.isEmpty()) { showToast("请先选择文件"); return; }
        LinearLayout layout = createDialogContainer();
        String defaultName = selectedPaths.size() == 1 ?
            new File(selectedPaths.iterator().next()).getName() : "archive";

        layout.addView(createDialogSectionLabel(formatSelectionSummary()));
        final EditText etName = createDialogEditText("文件名（不含扩展名）", defaultName);
        layout.addView(etName);

        layout.addView(createDialogSectionLabel("压缩格式"));
        android.widget.RadioGroup rgFormat = new android.widget.RadioGroup(this);
        rgFormat.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.RadioButton rbZip = createDialogRadioButton("ZIP", 1001);
        android.widget.RadioButton rbZpaq = createDialogRadioButton("ZPAQ", 1002);
        rgFormat.addView(rbZip);
        rgFormat.addView(rbZpaq);
        rgFormat.check(1001);
        layout.addView(rgFormat);

        final LinearLayout zpaqPanel = createDialogContainer();
        zpaqPanel.setPadding(0, dp(8), 0, 0);
        zpaqPanel.setVisibility(View.GONE);
        zpaqPanel.addView(createDialogSectionLabel("ZPAQ 参数"));
        zpaqPanel.addView(createDialogValueText("引擎版本: " + ZPAQNative.getVersion(), Gravity.START));

        final TextView tvLevelLabel = createDialogValueText("压缩级别 (0=最快 .. 5=最好)", Gravity.START);
        zpaqPanel.addView(tvLevelLabel);
        final android.widget.SeekBar sbLevel = createDialogSeekBar(5, 1);
        zpaqPanel.addView(sbLevel);
        final TextView tvLevelVal = createDialogValueText("级别 1", Gravity.CENTER_HORIZONTAL);
        zpaqPanel.addView(tvLevelVal);

        final TextView tvThreadLabel = createDialogValueText("线程数", Gravity.START);
        zpaqPanel.addView(tvThreadLabel);
        final android.widget.SeekBar sbThread = createDialogSeekBar(16, 8);
        zpaqPanel.addView(sbThread);
        final TextView tvThreadVal = createDialogValueText("8 线程", Gravity.CENTER_HORIZONTAL);
        zpaqPanel.addView(tvThreadVal);

        sbLevel.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                tvLevelVal.setText("级别 " + progress);
            }
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        sbThread.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                tvThreadVal.setText(Math.max(1, progress) + " 线程");
            }
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        layout.addView(zpaqPanel);

        rgFormat.setOnCheckedChangeListener((group, checkedId) -> {
            zpaqPanel.setVisibility(checkedId == 1002 ? View.VISIBLE : View.GONE);
        });

        Button btnStart = createDialogActionButton("开始压缩", 0xFFFFFFFF, 0xFF1565C0);
        Button btnCancelInline = createDialogActionButton("取消", 0xFF455A64, 0xFFE3EDF7);
        addActionButtonsToDialogContainer(layout, btnCancelInline, btnStart);

        final AlertDialog configDialog = showStyledDialog("压缩选项", layout, true);

        btnCancelInline.setOnClickListener(v -> configDialog.dismiss());
        btnStart.setOnClickListener(v -> {
            String baseName = etName.getText().toString().trim();
            if (baseName.isEmpty()) { showToast("请输入文件名"); return; }
            boolean isZpaq = rgFormat.getCheckedRadioButtonId() == 1002;
            String ext = isZpaq ? ".zpaq" : ".zip";
            final String finalZip = new File(currentPath, baseName + ext).getAbsolutePath();

            configDialog.dismiss();

            final ProgressBar pb = createHorizontalProgressBar(1000);
            final TextView tvStat = createDialogValueText("准备中...", Gravity.CENTER_HORIZONTAL);
            final TextView tvDetail = createDialogValueText("等待开始...", Gravity.CENTER_HORIZONTAL);
            final AlertDialog progressDialog = createProgressPanel(buildArchiveProgressTitle("压缩中", baseName + ext), pb, tvStat, tvDetail);
            progressDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "取消", (dialog, which) -> {
                dialog.dismiss();
            });
            progressDialog.show();

            final boolean[] cancelled = {false};

            new Thread(() -> {
                try {
                    if (isZpaq) {
                        final int level = sbLevel.getProgress();
                        final int threads = Math.max(1, sbThread.getProgress());
                        java.util.List<String> pathArgs = new java.util.ArrayList<>();
                        int totalFiles = 0;
                        long totalBytes = 0L;
                        for (String sp : selectedPaths) {
                            File sf = new File(sp);
                            if (sf.exists()) {
                                pathArgs.add(sf.getAbsolutePath());
                                totalFiles += countLeafFiles(sf);
                                totalBytes += computeTotalBytes(sf);
                            }
                        }
                        if (pathArgs.isEmpty()) {
                            throw new RuntimeException("没有可压缩的文件");
                        }
                        final int finalTotalFiles = Math.max(totalFiles, 1);
                        final long finalTotalBytes = Math.max(totalBytes, 1L);
                        final long startTime = System.currentTimeMillis();
                        runOnUiThread(() -> {
                            pb.setMax(1000);
                            pb.setProgress(0);
                            tvStat.setText("0.0% (0/" + finalTotalFiles + ")");
                            tvDetail.setText(buildProcessingText(0L, finalTotalBytes));
                        });

                        ZPAQNative.setProgressListener((processedBytes, nativeTotalBytes, currentEntry) -> {
                            // processedBytes 是百分数 0~100，nativeTotalBytes=100
                            long percent = Math.max(0L, Math.min(100L, processedBytes));
                            double fraction = percent / 100.0;
                            int progress = (int) Math.round(fraction * 1000.0);
                            long actualProcessedBytes = Math.round(fraction * (double) finalTotalBytes);
                            int doneFiles = Math.min(finalTotalFiles, (int) Math.round(fraction * finalTotalFiles));
                            long now = System.currentTimeMillis();
                            long elapsedMs = Math.max(1L, now - startTime);
                            double speed = elapsedMs > 0 ? actualProcessedBytes * 1000.0 / elapsedMs : 0.0;
                            long remainingBytes = Math.max(0L, finalTotalBytes - actualProcessedBytes);
                            long etaMs = speed <= 1e-6 ? 0L : (long) (remainingBytes / speed * 1000.0);
                            String entryLabel = (currentEntry == null || currentEntry.isEmpty()) ? "准备中" : currentEntry;
                            String statText = String.format(java.util.Locale.US, "%.1f%% (%d/%d)", fraction * 100.0, Math.min(doneFiles, finalTotalFiles), finalTotalFiles);
                            String detailText = "当前: " + entryLabel + "\n" + buildProcessingText(actualProcessedBytes, finalTotalBytes)
                                    + "  速度 " + humanSpeed(speed)
                                    + "  剩余 " + formatDuration(etaMs);
                            runOnUiThread(() -> {
                                if (!isFinishing()) {
                                    pb.setProgress(progress);
                                    tvStat.setText(statText);
                                    tvDetail.setText(detailText);
                                }
                            });
                        });

                        Log.i("AmazeLite", "ZPAQ add start: output=" + finalZip + ", level=" + level + ", threads=" + threads + ", args=" + pathArgs.size() + ", files=" + finalTotalFiles + ", bytes=" + finalTotalBytes);
                        ZPAQNative.CommandResult commandResult = ZPAQNative.addToArchive(finalZip, pathArgs.toArray(new String[0]), level, threads);
                        if (cancelled[0]) return; // 已取消，忽略结果
                        String result = commandResult.raw;
                        if (result != null) {
                            Log.i("AmazeLite", "ZPAQ result: " + result.replace("\n", " | "));
                            try {
                                File debugFile = new File(getExternalFilesDir(null), "zpaq_last_result.txt");
                                FileOutputStream debugOut = new FileOutputStream(debugFile);
                                debugOut.write(result.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                debugOut.close();
                            } catch (Exception ignored) {}
                        }
                        if (result == null) {
                            throw new RuntimeException("ZPAQ 压缩失败: 返回为空");
                        }
                        if (!commandResult.isSuccess()) {
                            throw new RuntimeException(commandResult.errorMessage());
                        }
                        File finalFile = new File(finalZip);
                        if (!finalFile.exists() || finalFile.length() == 0) {
                            throw new RuntimeException("ZPAQ 压缩失败: 未生成输出文件\n" + result);
                        }
                        runOnUiThread(() -> {
                            pb.setProgress(1000);
                            tvStat.setText("100.0% (" + finalTotalFiles + "/" + finalTotalFiles + ")");
                            tvDetail.setText("完成");
                        });
                    } else {
                        java.util.List<File> allFiles = new java.util.ArrayList<>();
                        for (String sp : selectedPaths) {
                            File sf = new File(sp);
                            countFiles(sf, allFiles);
                        }
                        final int totalFiles = allFiles.size();
                        runOnUiThread(() -> { pb.setMax(totalFiles); tvStat.setText("0/" + totalFiles); });

                        FileOutputStream fos = new FileOutputStream(finalZip);
                        ZipOutputStream zos = new ZipOutputStream(fos);
                        byte[] buf = new byte[8192];
                        final int[] done = {0};
                        for (String sp : selectedPaths) {
                            File sf = new File(sp);
                            addToZipProgress(sf, sf.getName(), zos, buf, done, totalFiles, pb, tvStat);
                        }
                        zos.finish();
                        zos.close();
                    }
                    ZPAQNative.clearProgressListener();
                    runOnUiThread(() -> {
                        progressDialog.dismiss();
                        showToast("压缩完成: " + finalZip);
                        selectedPaths.clear();
                        navigateTo(currentPath);
                    });
                } catch (Exception e) {
                    ZPAQNative.clearProgressListener();
                    runOnUiThread(() -> {
                        progressDialog.dismiss();
                        showToast("压缩失败: " + e.getMessage());
                    });
                }
            }).start();
        });
    }

    private void countFiles(File f, java.util.List<File> list) {
        if (f.isDirectory()) {
            File[] sub = f.listFiles();
            if (sub != null) for (File c : sub) countFiles(c, list);
        } else {
            list.add(f);
        }
    }

    private int countLeafFiles(File f) {
        if (!f.exists()) return 0;
        if (f.isFile()) return 1;
        File[] sub = f.listFiles();
        if (sub == null || sub.length == 0) return 0;
        int total = 0;
        for (File c : sub) total += countLeafFiles(c);
        return total;
    }

    private long computeTotalBytes(File f) {
        if (!f.exists()) return 0L;
        if (f.isFile()) return f.length();
        long total = 0L;
        File[] sub = f.listFiles();
        if (sub != null) for (File c : sub) total += computeTotalBytes(c);
        return total;
    }

    private int estimateDoneFiles(java.util.List<String> paths, long processedBytes) {
        if (processedBytes <= 0L) return 0;
        long accumulated = 0L;
        int done = 0;
        for (String path : paths) {
            File file = new File(path);
            accumulated += computeTotalBytes(file);
            if (processedBytes >= accumulated) {
                done += Math.max(1, countLeafFiles(file));
            } else {
                break;
            }
        }
        return done;
    }

    private String humanSpeed(double bytesPerSecond) {
        long safe = Math.max(0L, Math.round(bytesPerSecond));
        return Formatter.formatFileSize(this, safe) + "/s";
    }

    private String formatDuration(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        if (hours > 0) return String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, secs);
        return String.format(java.util.Locale.US, "%02d:%02d", minutes, secs);
    }

    private void addToZipProgress(File f, String entryName, ZipOutputStream zos, byte[] buf,
            final int[] done, final int total, final ProgressBar pb, final TextView tvStat) throws Exception {
        if (f.isDirectory()) {
            zos.putNextEntry(new ZipEntry(entryName + "/"));
            zos.closeEntry();
            File[] sub = f.listFiles();
            if (sub != null) for (File c : sub) addToZipProgress(c, entryName + "/" + c.getName(), zos, buf, done, total, pb, tvStat);
        } else {
            FileInputStream fis = new FileInputStream(f);
            zos.putNextEntry(new ZipEntry(entryName));
            byte[] b = new byte[8192];
            int len;
            while ((len = fis.read(b)) > 0) zos.write(b, 0, len);
            zos.closeEntry();
            fis.close();
            done[0]++;
            runOnUiThread(() -> {
                pb.setProgress(done[0]);
                tvStat.setText(done[0] + "/" + total);
            });
        }
    }

    private void addToZip(File f, String entryName, ZipOutputStream zos, byte[] buf) throws Exception {
        if (f.isDirectory()) {
            zos.putNextEntry(new ZipEntry(entryName + "/"));
            zos.closeEntry();
            File[] sub = f.listFiles();
            if (sub != null) for (File c : sub) addToZip(c, entryName + "/" + c.getName(), zos, buf);
        } else {
            FileInputStream fis = new FileInputStream(f);
            zos.putNextEntry(new ZipEntry(entryName));
            int len;
            while ((len = fis.read(buf)) > 0) zos.write(buf, 0, len);
            zos.closeEntry();
            fis.close();
        }
    }

    private void extractArchive(String archivePath, String outputDir) {
        File archive = new File(archivePath);
        if (!archive.exists()) { showToast("文件不存在"); return; }
        new File(outputDir).mkdirs();
        String name = archive.getName().toLowerCase();
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        alreadyHandled.set(false);

        final ProgressBar pb = createHorizontalProgressBar(100);
        final TextView tvMsg = createDialogValueText("正在解压: " + archive.getName(), Gravity.CENTER_HORIZONTAL);
        final TextView tvStat = createDialogValueText("0%", Gravity.CENTER_HORIZONTAL);
        Button cancelBtn = createDialogActionButton("中止", 0xFFFFFFFF, 0xFFC62828);
        AlertDialog dialog = createProgressPanelWithAction("解压中", pb, cancelBtn, tvMsg, tvStat);
        dialog.show();

        final Thread workThread = new Thread(() -> {
            try {
                if (name.endsWith(".zip")) {
                    extractZip(archivePath, outputDir, pb, tvStat, cancelled);
                } else if (name.endsWith(".zpaq")) {
                    Log.i("AmazeLite", "ZPAQ extract start: archive=" + archivePath + ", outputDir=" + outputDir + ", until=" + archivePreviewZpaqUntil);
                    ZPAQNative.CommandResult commandResult = ZPAQNative.extractArchiveVersion(archivePath, outputDir, archivePreviewZpaqUntil);
                    if (commandResult == null) {
                        throw new RuntimeException("ZPAQ 解压返回为空");
                    }
                    if (!commandResult.isSuccess()) {
                        throw new RuntimeException(commandResult.errorMessage());
                    }
                    runOnUiThread(() -> {
                        pb.setProgress(100);
                        tvStat.setText("100%");
                    });
                } else {
                    execSilent("7z x " + archivePath + " -o" + outputDir + " -y");
                }
                runOnUiThread(() -> {
                    if (alreadyHandled.get()) return;
                    alreadyHandled.set(true);
                    try { dialog.dismiss(); } catch (Exception ignored) {}
                    if (cancelled.get()) {
                        deleteDirectory(new File(outputDir));
                        showToast("解压已中止");
                    } else {
                        showToast("解压完成");
                    }
                    if (!isFinishing()) navigateTo(currentPath);
                });
            } catch (Exception e) {
                if (!cancelled.get()) {
                    final String errMsg = e.getMessage();
                    runOnUiThread(() -> {
                        if (alreadyHandled.get()) return;
                        alreadyHandled.set(true);
                        try { dialog.dismiss(); } catch (Exception ignored) {}
                        showToast("解压失败: " + errMsg);
                    });
                }
            }
        });
        cancelBtn.setOnClickListener(v -> {
            cancelled.set(true);
            workThread.interrupt();
            runOnUiThread(() -> {
                try { dialog.dismiss(); } catch (Exception ignored) {}
                deleteDirectory(new File(outputDir));
                showToast("解压已中止");
                if (!isFinishing()) navigateTo(currentPath);
            });
        });
        workThread.start();
    }
    private void extractZip(String zipPath, String outDir, ProgressBar pb, TextView tvStat, java.util.concurrent.atomic.AtomicBoolean cancelled) throws Exception {
        File dir = new File(outDir);
        dir.mkdirs();
        ZipInputStream countZis = new ZipInputStream(new FileInputStream(zipPath));
        int totalEntries = 0;
        while (countZis.getNextEntry() != null) { totalEntries++; countZis.closeEntry(); }
        countZis.close();
        if (totalEntries == 0) totalEntries = 1;
        ZipInputStream zis = new ZipInputStream(new FileInputStream(zipPath));
        byte[] buf = new byte[8192];
        ZipEntry entry;
        int done = 0;
        while ((entry = zis.getNextEntry()) != null) {
            if (cancelled.get()) {
                if (Thread.interrupted()) {}
                zis.closeEntry(); zis.close();
                deleteDirectory(new File(outDir));
                return;
            }
            File f = new File(outDir, entry.getName());
            if (entry.isDirectory()) {
                f.mkdirs();
            } else {
                f.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(f);
                int len;
                while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                fos.close();
            }
            zis.closeEntry();
            done++;
            final int progress = done * 100 / totalEntries;
            final int p = progress;
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    pb.setProgress(p);
                    tvStat.setText(p + "%");
                }
            });
        }
        zis.close();
    }
    private void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDirectory(f);
                f.delete();
            }
        }
        dir.delete();
    }

    private String execSilent(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            p.waitFor();
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = br.readLine()) != null) sb.append(l).append("\n");
            br.close();
            return sb.toString().trim();
        } catch (Exception e) { return ""; }
    }


    @Override
    public void onBackPressed() {
        if (!backStack.isEmpty()) {
            forwardStack.push(currentPath);
            navigateTo(backStack.pop());
        } else {
            super.onBackPressed();
        }
    }

    private void navigateTo(String path) {
        if (lvSuggest != null) lvSuggest.setVisibility(android.view.View.GONE);
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            btnGrant.setVisibility(View.GONE);
        } else {
            btnGrant.setVisibility(View.VISIBLE);
        }
        currentPath = path;
        if (pathWatcher != null) etPath.removeTextChangedListener(pathWatcher);
        etPath.setText(path);
        if (pathWatcher != null) etPath.addTextChangedListener(pathWatcher);
        fileList.clear();
        selectedPaths.clear();
        final String dirPath = path;
        new Thread(() -> {
            try {
                // 先用 File API（有 MANAGE_EXTERNAL_STORAGE 权限时可靠）
                File d = new File(dirPath);
                File[] fs = d.listFiles();
                if (fs != null && fs.length > 0) {
                    for (File sf : fs) {
                        fileList.add(new FileItem(sf));
                    }
                    sortFiles();
                    runOnUiThread(() -> {
                        adapter.notifyDataSetChanged();
                        if (fileList.isEmpty()) showToast("目录为空");
                        updateButtonState();
                    });
                    return;
                }
                // 备用：root stat
                String statResult = "";
                if (hasRoot) {
                    statResult = execRoot("stat -c '%A %s %Y %n' " + dirPath + "/* 2>/dev/null; stat -c '%A %s %Y %n' " + dirPath + "/.* 2>/dev/null");
                }
                if (!statResult.isEmpty()) {
                    parseStatOutput(statResult, dirPath);
                }
                runOnUiThread(() -> {
                    adapter.notifyDataSetChanged();
                    if (fileList.isEmpty()) showToast("目录为空");
                    updateButtonState();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    showToast("访问失败: " + e.getMessage());
                    updateButtonState();
                });
            }
        }).start();
    }

    private String execRoot(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.waitFor();
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = br.readLine()) != null) sb.append(l).append("\n");
            br.close();
            return sb.toString().trim();
        } catch (Exception e) { return ""; }
    }

    private void parseList(String output, String basePath) {
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                FileItem item = new FileItem();
                String[] parts = line.split("\\s+", 5);
                if (parts.length >= 5 && parts[0].length() == 10) {
                    item.perm = parts[0];
                    item.size = Long.parseLong(parts[1]);
                    item.mTime = parts[2] + " " + parts[3];
                    String fullName = parts[4];
                    if (fullName.startsWith("'") && fullName.endsWith("'"))
                        fullName = fullName.substring(1, fullName.length()-1);
                    if (fullName.contains(" -> ")) fullName = fullName.split(" -> ")[0];
                    String name = new File(fullName).getName();
                    item.name = name;
                    item.path = basePath + "/" + name;
                    item.isDir = item.perm.startsWith("d");
                    item.selected = false;
                } else {
                    item = parseLsLine(line, basePath);
                }
                if (item != null && item.name != null && !item.name.equals(".") && !item.name.equals(".."))
                    fileList.add(item);
            } catch (Exception ignored) {}
        }
        sortFiles();
    }

    private FileItem parseLsLine(String line, String basePath) {
        String[] parts = line.split("\\s+");
        if (parts.length < 2) return null;
        String name = parts[parts.length-1];
        if (name.equals(".") || name.equals("..")) return null;
        FileItem item = new FileItem();
        item.name = name;
        item.path = basePath + "/" + name;
        item.isDir = parts[0].startsWith("d");
        item.perm = parts[0];
        item.size = 0;
        item.mTime = "";
        item.selected = false;
        return item;
    }

    private void sortFiles() {
        Collections.sort(fileList, (a, b) -> {
            if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
            int cmp;
            switch (sortMode) {
                case 1: cmp = b.name.compareToIgnoreCase(a.name); break;
                case 2: cmp = Long.compare(a.size, b.size); break;
                case 3: cmp = Long.compare(b.size, a.size); break;
                case 4: cmp = a.mTime.compareTo(b.mTime); break;
                case 5: cmp = b.mTime.compareTo(a.mTime); break;
                default: cmp = a.name.compareToIgnoreCase(b.name); break;
            }
            return cmp;
        });
    }

    private void openFile(FileItem item) {
        String name = item.name.toLowerCase();
        // 压缩包点开预览内容列表
        if (name.endsWith(".zip") || name.endsWith(".7z") || name.endsWith(".rar") || name.endsWith(".zpaq")) {
            previewArchive(item.path);
            return;
        }
        String mime = getMimeType(item.name);
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            File file = new File(item.path);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            intent.setDataAndType(uri, mime);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            showToast("无法打开此文件");
        }
    }
    class ArchiveNode {
        String name, fullPath;
        boolean isDir;
        long size;
        long packedSize;
        String timeText;
        String kindText;
        String permsText;
        String ownerText;
        String rawComment;
        String detailText;
        String fullDetailText;
        ArchiveNode(String name, String fullPath, boolean isDir, long size, long packedSize,
                    String timeText, String kindText, String permsText, String ownerText,
                    String rawComment, String detailText, String fullDetailText) {
            this.name = name;
            this.fullPath = fullPath;
            this.isDir = isDir;
            this.size = size;
            this.packedSize = packedSize;
            this.timeText = timeText == null ? "" : timeText;
            this.kindText = kindText == null ? "" : kindText;
            this.permsText = permsText == null ? "" : permsText;
            this.ownerText = ownerText == null ? "" : ownerText;
            this.rawComment = rawComment == null ? "" : rawComment;
            this.detailText = detailText == null ? "" : detailText;
            this.fullDetailText = fullDetailText == null ? "" : fullDetailText;
        }
    }


    private void previewArchive(String path) {
        final String arcPath = path;
        ProgressBar progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        TextView tvMessage = createDialogMessageView("正在读取文件列表...");
        AlertDialog loadingDialog = createProgressPanel("读取压缩包", progressBar, tvMessage);
        loadingDialog.show();
        new Thread(() -> {
            List<ArchiveNode> allEntries = new ArrayList<>();
            try {
                allEntries = readArchiveEntries(arcPath);
            } catch (Exception e) {
                Log.e("AmazeLite", "Preview archive failed: " + arcPath, e);
            }
            final List<ArchiveNode> entries = allEntries;
            runOnUiThread(() -> {
                loadingDialog.dismiss();
                if (entries.isEmpty()) {
                    showToast("无法读取压缩包");
                    return;
                }
                showArchiveDir(arcPath, entries, "", null, "压缩包: " + new File(arcPath).getName());
            });
        }).start();
    }

    private List<ArchiveNode> readArchiveEntries(String arcPath) throws Exception {
        String lowerPath = arcPath.toLowerCase();
        List<ArchiveNode> entries = new ArrayList<>();
        if (lowerPath.endsWith(".zip")) {
            ZipInputStream zis = new ZipInputStream(new FileInputStream(arcPath));
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String en = entry.getName();
                boolean isDir = entry.isDirectory();
                long size = entry.getSize() >= 0 ? entry.getSize() : 0L;
                long packedSize = entry.getCompressedSize() >= 0 ? entry.getCompressedSize() : 0L;
                String timeText = formatArchiveTime(entry.getTime());
                String kind = "ZIP";
                String detail = buildArchiveDetail(size, packedSize, timeText, kind, "", "", "");
                String fullDetail = buildArchiveFullDetail(size, packedSize, timeText, kind, "", "", "",
                    "method=" + entry.getMethod());
                entries.add(new ArchiveNode(
                    new File(en).getName(), en, isDir, size, packedSize, timeText, kind, "", "", "", detail, fullDetail));
                zis.closeEntry();
            }
            zis.close();
            return entries;
        }
        if (lowerPath.endsWith(".zpaq")) {
        archivePreviewZpaqVersions = new ArrayList<>();
        // 先获取版本列表（使用 -all 4 可以列出版本目录）
        ZPAQNative.CommandResult versionsResult = ZPAQNative.listArchiveAllVersions(arcPath);
        if (versionsResult != null && versionsResult.isSuccess()) {
            archivePreviewZpaqVersions = ZPAQNative.parseVersionList(versionsResult);
        }
        // 再获取当前版本的文件列表
        ZPAQNative.CommandResult result = ZPAQNative.listArchiveVersion(arcPath, archivePreviewZpaqUntil);
        // DEBUG: 把原始 zpaq list 输出保存到可读位置，方便排查解析问题
        try {
            File debugFile = new File("/sdcard/Download/zpaq_list_debug.txt");
            String debugContent = "exitCode=" + (result == null ? "-1" : result.exitCode) + "\n"
                    + "=== STDERR ===\n" + (result == null ? "" : result.stderr)
                    + "\n=== STDOUT ===\n" + (result == null ? "" : result.stdout)
                    + "\n=== RAW ===\n" + (result == null ? "" : result.raw);
            FileOutputStream debugOut = new FileOutputStream(debugFile);
            debugOut.write(debugContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            debugOut.close();
        } catch (Exception ignored) {}
        if (result == null || !result.isSuccess()) {
            throw new RuntimeException(result == null ? "读取归档失败" : result.errorMessage());
        }
        java.util.List<com.operit.zpaq.ZPAQNative.ArchiveEntry> parsedEntries = ZPAQNative.parseOfficialListOutput(result);
            for (com.operit.zpaq.ZPAQNative.ArchiveEntry raw : parsedEntries) {
                String en = raw.path;
                boolean isDir = en.endsWith("/");
                long size = raw.size > 0 ? raw.size : parseZpaqSize(raw.comment);
                long packedSize = raw.packedSize;
                String timeText = raw.timeText.isEmpty() ? parseZpaqTime(raw.comment) : raw.timeText;
                String perms = raw.attrText.isEmpty() ? parseZpaqMode(raw.comment) : raw.attrText;
                String owner = parseZpaqOwner(raw.comment);
                String kind = archivePreviewZpaqUntil == null || archivePreviewZpaqUntil.isEmpty() ? "ZPAQ" : "ZPAQ v" + archivePreviewZpaqUntil;
                String detail = buildArchiveDetail(size, packedSize, timeText, kind, perms, owner, raw.comment);
                String fullDetail = buildArchiveFullDetail(size, packedSize, timeText, kind, perms, owner, raw.comment, raw.comment);
                entries.add(new ArchiveNode(
                    new File(en).getName(), en, isDir, size, packedSize, timeText, kind, perms, owner, raw.comment, detail, fullDetail));
            }
            return entries;
        }

        String out = execSilent("7z l -slt \"" + arcPath + "\" 2>/dev/null");
        if (!out.isEmpty()) {
            entries.addAll(parse7zEntries(out));
        }
        return entries;
    }

    private String buildArchiveDetail(long size, long packedSize, String timeText, String kind,
                                      String permsText, String ownerText, String rawComment) {
        java.util.List<String> parts = new ArrayList<>();
        parts.add(buildArchiveSizeSummary(size, packedSize));
        if (timeText != null && !timeText.isEmpty()) parts.add(timeText);
        if (kind != null && !kind.isEmpty()) parts.add(kind);
        if (archivePreviewFullDetails) {
            if (permsText != null && !permsText.isEmpty()) parts.add(permsText);
            if (ownerText != null && !ownerText.isEmpty()) parts.add(ownerText);
        }
        return android.text.TextUtils.join("  ·  ", parts);
    }

    private String buildArchiveFullDetail(long size, long packedSize, String timeText, String kind,
                                          String permsText, String ownerText, String rawComment, String extra) {
        java.util.List<String> parts = new ArrayList<>();
        parts.add("原始大小 " + formatArchiveSizeWithBytes(size));
        parts.add("压缩后大小 " + formatArchiveSizeWithBytes(packedSize));
        if (timeText != null && !timeText.isEmpty()) parts.add("时间 " + timeText);
        if (kind != null && !kind.isEmpty()) parts.add("类型 " + kind);
        if (permsText != null && !permsText.isEmpty()) parts.add("权限 " + permsText);
        if (ownerText != null && !ownerText.isEmpty()) parts.add("属主 " + ownerText);
        if (extra != null && !extra.isEmpty()) parts.add(extra);
        if (rawComment != null && !rawComment.isEmpty() && (extra == null || !rawComment.equals(extra))) {
            parts.add("注释 " + rawComment);
        }
        return android.text.TextUtils.join("\n", parts);
    }

    private String buildArchiveSizeSummary(long size, long packedSize) {
    if (packedSize <= 0) return "原始 " + formatArchiveSizeShort(size) + "  (压缩后大小未知)";
    return "原始 " + formatArchiveSizeShort(size) + "  →  压缩后 " + formatArchiveSizeShort(packedSize);
}

    private String formatArchiveSizeShort(long size) {
        return size > 0 ? Formatter.formatFileSize(this, size) : "未知";
    }

    private String formatArchiveSizeWithBytes(long size) {
        if (size <= 0) return "未知";
        return Formatter.formatFileSize(this, size) + " (" + size + " B)";
    }

    private List<ArchiveNode> parse7zEntries(String out) {
        List<ArchiveNode> entries = new ArrayList<>();
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
        for (String line : out.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                ArchiveNode node = build7zNode(fields);
                if (node != null) entries.add(node);
                fields.clear();
                continue;
            }
            int index = line.indexOf(" = ");
            if (index <= 0) continue;
            fields.put(line.substring(0, index).trim(), line.substring(index + 3).trim());
        }
        ArchiveNode node = build7zNode(fields);
        if (node != null) entries.add(node);
        return entries;
    }

    private ArchiveNode build7zNode(java.util.Map<String, String> fields) {
        if (fields.isEmpty()) return null;
        String path = fields.get("Path");
        if (path == null || path.isEmpty()) return null;
        boolean isDir = "D".equalsIgnoreCase(fields.get("Attributes")) || path.endsWith("/");
        long size = parseLongOrZero(fields.get("Size"));
        long packedSize = parseLongOrZero(fields.get("Packed Size"));
        String timeText = fields.containsKey("Modified") ? fields.get("Modified") : "";
        String perms = fields.getOrDefault("Attributes", "");
        String owner = fields.getOrDefault("User", "");
        String kind = fields.containsKey("Folder") && "-".equals(fields.get("Folder")) ? "7Z/RAR" : "7Z/RAR";
        String extra = "";
        if (fields.containsKey("CRC")) extra += "CRC=" + fields.get("CRC");
        if (fields.containsKey("Method")) extra += (extra.isEmpty() ? "" : "  ") + "method=" + fields.get("Method");
        String detail = buildArchiveDetail(size, packedSize, timeText, kind, perms, owner, "");
        String fullDetail = buildArchiveFullDetail(size, packedSize, timeText, kind, perms, owner, "", extra);
        return new ArchiveNode(new File(path).getName(), path, isDir, size, packedSize, timeText, kind, perms, owner, "", detail, fullDetail);
    }

    private long parseLongOrZero(String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private String formatArchiveTime(long millis) {
        if (millis <= 0L) return "";
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US);
        return sdf.format(new java.util.Date(millis));
    }

    private String parseZpaqTime(String comment) {
        if (comment == null) return "";
        String value = comment.trim();
        int space = value.indexOf(' ');
        if (space >= 0 && space + 1 < value.length()) {
            String extra = value.substring(space + 1).trim();
            if (extra.matches("\\d{14}.*")) {
                String digits = extra.substring(0, 14);
                try {
                    java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US);
                    java.text.SimpleDateFormat out = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US);
                    return out.format(in.parse(digits));
                } catch (Exception ignored) {}
            }
        }
        return "";
    }

    private long parseZpaqSize(String comment) {
        if (comment == null) return 0L;
        String value = comment.trim();
        if (value.isEmpty()) return 0L;
        int space = value.indexOf(' ');
        if (space > 0) value = value.substring(0, space);
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return 0L;
        }
    }

    private String parseZpaqMode(String comment) {
        String value = extractZpaqField(comment, "mode=");
        if (value.isEmpty()) return "";
        return value;
    }

    private String parseZpaqOwner(String comment) {
        String uid = extractZpaqField(comment, "uid=");
        String gid = extractZpaqField(comment, "gid=");
        if (uid.isEmpty() && gid.isEmpty()) return "";
        if (gid.isEmpty()) return "uid=" + uid;
        if (uid.isEmpty()) return "gid=" + gid;
        return "uid=" + uid + ", gid=" + gid;
    }

    private String extractZpaqField(String comment, String key) {
        if (comment == null || key == null || key.isEmpty()) return "";
        int index = comment.indexOf(key);
        if (index < 0) return "";
        int start = index + key.length();
        int end = comment.indexOf(' ', start);
        if (end < 0) end = comment.length();
        if (start >= end) return "";
        return comment.substring(start, end).trim();
    }

    class ArchivePreviewAdapter extends BaseAdapter {
        private final List<ArchiveNode> items;

        ArchivePreviewAdapter(List<ArchiveNode> items) {
            this.items = items;
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(14), dp(12), dp(14), dp(12));

                TextView title = new TextView(MainActivity.this);
                title.setId(android.R.id.text1);
                title.setTextSize(15f);
                title.setTypeface(null, Typeface.BOLD);
                title.setTextColor(0xFF1F2933);

                TextView subtitle = new TextView(MainActivity.this);
                subtitle.setId(android.R.id.text2);
                subtitle.setTextSize(12f);
                subtitle.setTextColor(0xFF607D8B);
                subtitle.setPadding(0, dp(4), 0, 0);
                subtitle.setLineSpacing(dp(1), 1.0f);
                subtitle.setMaxLines(3);

                row.addView(title);
                row.addView(subtitle);
                convertView = row;
            }

            ArchiveNode node = items.get(position);
            TextView title = convertView.findViewById(android.R.id.text1);
            TextView subtitle = convertView.findViewById(android.R.id.text2);
            title.setText((node.isDir ? "📁 " : "📄 ") + node.name);

            String subtitleText;
            if (node.isDir) {
                subtitleText = archivePreviewFullDetails && !nonNull(node.kindText).isEmpty()
                    ? node.kindText
                    : "目录";
            } else {
                subtitleText = archivePreviewFullDetails ? buildArchivePreviewListSubtitle(node) : nonNull(node.detailText);
            }
            subtitle.setText(subtitleText.isEmpty() ? "-" : subtitleText);
            return convertView;
        }
    }

    private String buildArchivePreviewListSubtitle(ArchiveNode node) {
        java.util.List<String> lines = new ArrayList<>();
        if (node.detailText != null && !node.detailText.isEmpty()) lines.add(node.detailText);
        java.util.List<String> extra = new ArrayList<>();
        if (node.packedSize > 0) extra.add("压缩后 " + Formatter.formatFileSize(this, node.packedSize));
        if (node.permsText != null && !node.permsText.isEmpty()) extra.add(node.permsText);
        if (node.ownerText != null && !node.ownerText.isEmpty()) extra.add(node.ownerText);
        if (node.rawComment != null && !node.rawComment.isEmpty()) extra.add("注释已保存");
        if (!extra.isEmpty()) lines.add(android.text.TextUtils.join("  ·  ", extra));
        return android.text.TextUtils.join("\n", lines);
    }

    private String buildZpaqVersionButtonText() {
        if (archivePreviewZpaqUntil == null || archivePreviewZpaqUntil.isEmpty()) {
            if (archivePreviewZpaqVersions != null && !archivePreviewZpaqVersions.isEmpty()) {
                return "版本: " + archivePreviewZpaqVersions.get(archivePreviewZpaqVersions.size() - 1).value;
            }
            return "版本: 最新";
        }
        return "版本: " + archivePreviewZpaqUntil;
    }

    private void showZpaqVersionChooser(String arcPath) {
        java.util.List<String> rawLabels = new ArrayList<>();
        java.util.List<String> rawValues = new ArrayList<>();
        if (archivePreviewZpaqVersions != null && !archivePreviewZpaqVersions.isEmpty()) {
            for (ZPAQNative.ArchiveVersion version : archivePreviewZpaqVersions) {
                rawLabels.add(version.label);
                rawValues.add(version.value);
            }
        } else {
            rawLabels.add("无版本信息");
            rawValues.add("");
        }
        // 根据排序方向展示列表
        int total = rawLabels.size();
        java.util.List<String> labels = new ArrayList<>();
        java.util.List<String> values = new ArrayList<>();
        if (zpaqVersionSortAscending) {
            for (int i = 0; i < total; i++) { labels.add(rawLabels.get(i)); values.add(rawValues.get(i)); }
        } else {
            for (int i = total - 1; i >= 0; i--) { labels.add(rawLabels.get(i)); values.add(rawValues.get(i)); }
        }
        // 默认选中排序后的第一个（最新/最旧）
        final int[] checked = {0};
        String target = archivePreviewZpaqUntil == null ? "" : archivePreviewZpaqUntil;
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).equals(target)) { checked[0] = i; break; }
        }

        String msg = "共 " + total + " 个版本";
        CheckBox cbSort = createDialogCheckBox("正序排列", zpaqVersionSortAscending);
        LinearLayout container = createDialogContainer();
        container.addView(createDialogMessageView(msg));
        container.addView(cbSort);

        // 用独立 ListView 替代 setSingleChoiceItems 以支持长按
        ListView listView = new ListView(this);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_single_choice, labels) {
            @Override
            public android.view.View getView(int position, android.view.View convertView, android.view.ViewGroup parent) {
                android.view.View v = super.getView(position, convertView, parent);
                v.setBackgroundColor(position == checked[0] ? 0x330000FF : 0x00000000);
                return v;
            }
        };
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        listView.setItemChecked(checked[0], true);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(240));
        listParams.topMargin = dp(4);
        listView.setLayoutParams(listParams);
        container.addView(listView);

        final String[] finalValues = values.toArray(new String[0]);
        listView.setOnItemClickListener((parent, view, which, id) -> {
            checked[0] = which;
            archivePreviewZpaqUntil = finalValues[which];
            // 刷新选中高亮
            for (int i = 0; i < listView.getChildCount(); i++) {
                android.view.View v = listView.getChildAt(i);
                v.setBackgroundColor(i == which ? 0x330000FF : 0x00000000);
            }
        });
        listView.setOnItemLongClickListener((parent, view, which, id) -> {
            final String chosenVersion = finalValues[which];
            String[] ops = {"解压此版本", "删除此版本"};
            AlertDialog actionDialog = createBaseDialog("版本 " + chosenVersion + " 操作", null)
                    .setItems(ops, (d, wi) -> {
                        if (wi == 0) {
                            // 解压
                            String outDir = currentPath + "/zpaq_v" + chosenVersion;
                            new File(outDir).mkdirs();
                            new Thread(() -> {
                                try {
                                    ZPAQNative.CommandResult extractResult = ZPAQNative.extractArchiveVersion(arcPath, outDir, chosenVersion);
                                    runOnUiThread(() -> {
                                        if (extractResult != null && extractResult.isSuccess()) {
                                            showToast("解压完成: " + outDir);
                                        } else {
                                            showToast("解压失败: " + (extractResult == null ? "无返回" : extractResult.errorMessage()));
                                        }
                                    });
                                 } catch (Exception e) {
                                    runOnUiThread(() -> showToast("解压异常: " + e.getMessage()));
                                }
                            }).start();
                        } else if (wi == 1) {
                            // 删除版本
                            final android.app.ProgressDialog progressDialog = new android.app.ProgressDialog(MainActivity.this);
                            progressDialog.setMessage("正在删除版本 " + chosenVersion + " ...");
                            progressDialog.setCancelable(true);
                            progressDialog.setButton(android.app.ProgressDialog.BUTTON_NEGATIVE, "取消", (di, w) -> {
                                progressDialog.dismiss();
                            });
                            progressDialog.show();
                            new Thread(() -> {
                                // zpaq add -until X 保留到版本X(含X)，要删版本N需要传N-1
                                final int v;
                                try {
                                    v = Integer.parseInt(chosenVersion);
                                    if (v <= 1) {
                                        runOnUiThread(() -> { progressDialog.dismiss(); showToast("不能删除最早的版本"); });
                                        return;
                                    }
                                } catch (NumberFormatException e) {
                                    runOnUiThread(() -> { progressDialog.dismiss(); showToast("无效版本号"); });
                                    return;
                                }
                                try {
                                    runOnUiThread(() -> progressDialog.setMessage("正在删除版本 " + chosenVersion + " ..."));
                                    ZPAQNative.CommandResult deleteResult = ZPAQNative.deleteVersion(arcPath, v);
                                    if (progressDialog.isShowing()) {
                                        runOnUiThread(() -> progressDialog.setMessage("写入完成，正在验证..."));
                                    } else {
                                        return; // 已取消
                                    }
                                    ZPAQNative.CommandResult verifyResult = ZPAQNative.listArchive(arcPath);
                                    String debugInfo = "=== DELETE ===\nexitCode=" + (deleteResult != null ? deleteResult.exitCode : -1) + 
                                        "\n=== STDERR ===\n" + (deleteResult != null ? deleteResult.stderr : "") +
                                        "\n=== STDOUT ===\n" + (deleteResult != null ? deleteResult.stdout : "") +
                                        "\n=== VERIFY ===\nexitCode=" + (verifyResult != null ? verifyResult.exitCode : -1) +
                                        "\n=== STDERR ===\n" + (verifyResult != null ? verifyResult.stderr : "") +
                                        "\n=== STDOUT ===\n" + (verifyResult != null ? verifyResult.stdout : "");
                                    try {
                                        java.io.FileWriter fw = new java.io.FileWriter("/sdcard/Download/zpaq_delete_debug.txt");
                                        fw.write(debugInfo);
                                        fw.close();
                                    } catch (Exception ignored) {}
                                    final String errMsg = deleteResult != null ? deleteResult.stderr : "";
                                    runOnUiThread(() -> {
                                        progressDialog.dismiss();
                                        if (deleteResult != null && deleteResult.isSuccess() && verifyResult != null && verifyResult.isSuccess()) {
                                            showToast("版本 " + chosenVersion + " 已删除");
                                            previewArchive(arcPath);
                                        } else {
                                            String failReason = "";
                                            if (deleteResult == null || !deleteResult.isSuccess()) failReason = "删除命令失败: " + (deleteResult == null ? "" : deleteResult.stderr);
                                            else if (verifyResult == null || !verifyResult.isSuccess()) failReason = "验证失败: " + (verifyResult == null ? "" : verifyResult.stderr);
                                            showToast("删除失败: " + failReason);
                                        }
                                    });
                                } catch (Exception e) {
                                    runOnUiThread(() -> { progressDialog.dismiss(); showToast("删除异常: " + e.getMessage()); });
                                }
                            }).start();
                        }
                        d.dismiss();
                    })
                    .setNegativeButton("取消", null)
                    .create();
            actionDialog.show();
            styleDialogButtons(actionDialog);
            return true;
        });

        AlertDialog dialog = createBaseDialog("选择 ZPAQ 版本", container)
                .setNeutralButton("关闭", (d, w) -> d.dismiss())
                .create();
        dialog.show();
        styleDialogButtons(dialog);
        cbSort.setOnCheckedChangeListener((buttonView, isChecked) -> {
            zpaqVersionSortAscending = isChecked;
            dialog.dismiss();
            showZpaqVersionChooser(arcPath);
        });
    }
    private void showArchiveDir(final String arcPath, final List<ArchiveNode> allEntries,
                                final String prefix, final String parentPrefix, String title) {
        List<ArchiveNode> currentLevel = new ArrayList<>();
        java.util.LinkedHashMap<String, ArchiveNode> currentLevelMap = new java.util.LinkedHashMap<>();
        String p = prefix;
        if (p.endsWith("/")) p = p.substring(0, p.length()-1);
        final String basePrefix = p;
        for (ArchiveNode n : allEntries) {
            if (!basePrefix.isEmpty()) {
                String normalizedPrefix = basePrefix + "/";
                if (!(n.fullPath.equals(basePrefix) || n.fullPath.startsWith(normalizedPrefix))) continue;
            }
            String remaining;
            try {
                if (basePrefix.isEmpty()) {
                    remaining = n.fullPath;
                } else if (n.fullPath.equals(basePrefix)) {
                    remaining = "";
                } else {
                    remaining = n.fullPath.substring(basePrefix.length());
                }
            } catch (Exception e) { continue; }
            if (remaining.isEmpty()) continue;
            if (remaining.startsWith("/")) remaining = remaining.substring(1);
            if (remaining.isEmpty()) continue;
            int slash = remaining.indexOf('/');
            boolean isDirectChild = slash < 0 || slash == remaining.length() - 1;
            String childName = isDirectChild ? remaining.replaceAll("/$", "") : remaining.substring(0, slash);
            if (childName.isEmpty()) continue;
            boolean isSubDir = !isDirectChild || n.isDir;
            String nodePath = basePrefix.isEmpty() ? childName : basePrefix + "/" + childName;
            ArchiveNode existing = currentLevelMap.get(nodePath);
            if (existing == null || (!isSubDir && existing.isDir)) {
                ArchiveNode node = new ArchiveNode(childName, nodePath,
                    isSubDir, isSubDir ? 0 : n.size, isSubDir ? 0 : n.packedSize,
                    isSubDir ? "" : n.timeText, n.kindText,
                    isSubDir ? "" : n.permsText, isSubDir ? "" : n.ownerText,
                    isSubDir ? "" : n.rawComment, isSubDir ? "" : n.detailText,
                    isSubDir ? "" : n.fullDetailText);
                currentLevelMap.put(nodePath, node);
            }
        }
        currentLevel.addAll(currentLevelMap.values());

        Collections.sort(currentLevel, (a, b) -> {
            if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });


        CheckBox cb = createDialogCheckBox("显示完整信息", archivePreviewFullDetails);
        LinearLayout container = createDialogContainer();
        container.addView(cb);
        if (arcPath.toLowerCase().endsWith(".zpaq")) {
            Button versionBtn = createDialogActionButton(buildZpaqVersionButtonText(), 0xFF1565C0, 0xFFE3F2FD);
            versionBtn.setOnClickListener(v -> showZpaqVersionChooser(arcPath));
            container.addView(versionBtn);
        }

        ListView listView = new ListView(this);
        listView.setDividerHeight(0);
        listView.setAdapter(new ArchivePreviewAdapter(currentLevel));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(360));
        listParams.topMargin = dp(8);
        listView.setLayoutParams(listParams);
        container.addView(listView);

        AlertDialog.Builder b = createBaseDialog(formatArchiveDialogTitle(title, currentLevel.size()), container);
        final AlertDialog[] dialogRef = new AlertDialog[1];
        cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
            archivePreviewFullDetails = isChecked;
            if (dialogRef[0] != null && dialogRef[0].isShowing()) dialogRef[0].dismiss();
            showArchiveDir(arcPath, allEntries, prefix, parentPrefix, title);
        });

        listView.setOnItemClickListener((parent, view, which, id) -> {
            ArchiveNode selectedNode = currentLevel.get(which);
            if (selectedNode.isDir) {
                showArchiveDir(arcPath, allEntries, selectedNode.fullPath, basePrefix,
                    "压缩包: " + new File(arcPath).getName() + " / " + selectedNode.fullPath);
                return;
            }
            // 点击文件：解压到临时目录后用系统应用打开
            try {
                String entryPath = selectedNode.fullPath;
                String mime = getMimeType(entryPath);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                String tmpDir = getCacheDir() + "/archive_preview/";
                new File(tmpDir).mkdirs();
                // zpaq 不支持按单文件模式筛选，提取整个归档到临时目录再找
                if (arcPath.toLowerCase().endsWith(".zip")) {
                    extractOneFromZip(arcPath, entryPath, tmpDir);
                } else if (arcPath.toLowerCase().endsWith(".zpaq")) {
                    // 使用归档内完整路径精确提取单个文件
                    String exactPath = "/" + entryPath.replaceAll("^\\d{4}/", "");
                    android.util.Log.d("AmazeLite", "extractSingle: arc=" + arcPath + " path=" + exactPath + " out=" + tmpDir + " until=" + archivePreviewZpaqUntil);
                    ZPAQNative.CommandResult result = ZPAQNative.extractSingleEntry(arcPath, exactPath, tmpDir, archivePreviewZpaqUntil);
                    if (result == null || !result.isSuccess()) {
                        String msg = result == null ? "解压失败" : ("解压失败: " + result.errorMessage().replace("\n", " "));
                        showToast(msg);
                        android.util.Log.e("AmazeLite", "extractSingleEntry failed: arc=" + arcPath + " path=" + exactPath
                            + " exitCode=" + (result != null ? result.exitCode : "")
                            + " stderr=" + (result != null ? result.stderr : ""));
                        return;
                    }
                } else {
                    execSilent("7z x \"" + arcPath + "\" \"" + entryPath + "\" -o\"" + tmpDir + "\" -y");
                }
                // 在 tmpDir 下搜索目标文件（zpaq 提取后路径为 storage/emulated/0/yuedumd/文件名）
                // 去掉版本前缀和第一级目录，取文件相对路径
                String searchPath = entryPath.replaceAll("^\\d{4}/", "");
                File actualFile = new File(tmpDir, searchPath);
                if (!actualFile.exists()) {
                    actualFile = new File(tmpDir, new File(entryPath).getName());
                }
                // 最后尝试：遍历 tmpDir 下所有文件，找文件名匹配的
                if (!actualFile.exists()) {
                    java.io.File[] allFiles = new java.io.File(tmpDir).listFiles();
                    if (allFiles != null) {
                        String targetName = new File(entryPath).getName();
                        for (java.io.File f : allFiles) {
                            if (f.getName().equals(targetName)) {
                                actualFile = f;
                                break;
                            }
                        }
                    }
                }
                if (!actualFile.exists()) {
                    showToast("文件提取失败");
                    return;
                }
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", actualFile);
                intent.setDataAndType(uri, mime);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
            } catch (Exception ex) {
                showToast("无法打开此文件");
            }
        });

        listView.setOnItemLongClickListener((parent, view, which, id) -> {
            ArchiveNode selectedNode = currentLevel.get(which);
            if (selectedNode.isDir) return true;
            showArchiveFileExtractMenu(arcPath, selectedNode);
            return true;
        });

        b.setNegativeButton(parentPrefix != null ? "返回上级" : "关闭", (d, w) -> {
            if (parentPrefix != null) {
                showArchiveDir(arcPath, allEntries, parentPrefix, null, "压缩包: " + new File(arcPath).getName());
            }
        });
        b.setNeutralButton("退出", (d, w) -> d.dismiss());
        AlertDialog dialog = b.create();
        dialog.show();
        styleDialogButtons(dialog);
        dialogRef[0] = dialog;
    }

    private void extractOneFromZip(String zipPath, String entryName, String outDir) throws Exception {
        ZipInputStream zis = new ZipInputStream(new FileInputStream(zipPath));
        byte[] buf = new byte[8192];
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.getName().equals(entryName)) {
                File f = new File(outDir, new File(entryName).getName());
                f.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(f);
                int len;
                while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                fos.close();
                zis.closeEntry();
                break;
            }
            zis.closeEntry();
        }
        zis.close();
    }

    private void extractOneFromZpaq(String archivePath, String entryName, String outDir) throws Exception {
        // 使用归档内完整路径精确提取单个文件
        String exactPath = "/" + entryName.replaceAll("^\\d{4}/", "");
        ZPAQNative.CommandResult result = ZPAQNative.extractSingleEntry(archivePath, exactPath, outDir, archivePreviewZpaqUntil);
        if (result == null) throw new RuntimeException("ZPAQ 解压返回为空");
        if (!result.isSuccess()) throw new RuntimeException(result.errorMessage());
    }

    private String getMimeType(String name) {
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase() : "";
        switch (ext) {
            case ".jpg": case ".jpeg": return "image/jpeg";
            case ".png": return "image/png";
            case ".gif": return "image/gif";
            case ".mp4": return "video/mp4";
            case ".mkv": return "video/x-matroska";
            case ".mp3": return "audio/mpeg";
            case ".wav": return "audio/wav";
            case ".flac": return "audio/flac";
            case ".pdf": return "application/pdf";
            case ".html": case ".htm": return "text/html";
            case ".txt": return "text/plain";
            case ".apk": return "application/vnd.android.package-archive";
            case ".zip": return "application/zip";
                        case ".zpaq": return "application/x-zpaq";
            case ".7z": return "application/x-7z-compressed";
            case ".rar": return "application/vnd.rar";
            default: return "*/*";
        }
    }

    private void pasteFiles() {
        if (clipboardPaths.isEmpty()) { showToast("剪贴板为空"); return; }
        final ProgressBar pb = createHorizontalProgressBar(1000);
        final TextView tvStat = createDialogValueText("正在统计文件...", Gravity.CENTER_HORIZONTAL);
        final AlertDialog progressDialog = createProgressPanel("粘贴中", pb, tvStat);
        progressDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "取消", (d, w) -> progressDialog.dismiss());
        progressDialog.show();

        new Thread(() -> {
            // 先统计总文件数
            java.util.List<File> allFiles = new java.util.ArrayList<>();
            long totalBytes = 0;
            for (String src : clipboardPaths) {
                File f = new File(src);
                if (f.isDirectory()) {
                    java.util.ArrayList<File> dirFiles = new java.util.ArrayList<>();
                    countFiles(f, dirFiles);
                    allFiles.addAll(dirFiles);
                    for (File df : dirFiles) totalBytes += df.length();
                } else {
                    allFiles.add(f);
                    totalBytes += f.length();
                }
            }
            final int totalCount = allFiles.size();
            final long totalB = Math.max(totalBytes, 1L);
            final long startTime = System.currentTimeMillis();

            runOnUiThread(() -> {
                pb.setMax(1000);
                tvStat.setText("0/" + totalCount + "  0%");
            });

            final boolean[] cancelled = {false};
            progressDialog.setOnDismissListener(d -> cancelled[0] = true);

            java.util.concurrent.atomic.AtomicInteger doneCount = new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.concurrent.atomic.AtomicLong doneBytes = new java.util.concurrent.atomic.AtomicLong(0);

            for (String src : clipboardPaths) {
                if (cancelled[0]) break;
                File f = new File(src);
                File dest = new File(currentPath, f.getName());
                try {
                    if (clipboardIsCut) {
                        f.renameTo(dest);
                        doneCount.addAndGet(1);
                    } else {
                        copyFileWithProgress(f, dest, doneCount, doneBytes, totalCount, totalB, startTime, pb, tvStat);
                    }
                    // 剪切模式下如果 renameTo 成功，文件已移走，不需要再删除
                } catch (Exception e) {
                    runOnUiThread(() -> showToast("操作失败: " + e.getMessage()));
                    return;
                }
            }
            clipboardPaths.clear();
            runOnUiThread(() -> {
                progressDialog.dismiss();
                showToast("粘贴完成");
                navigateTo(currentPath);
            });
        }).start();
    }

    private void copyFileWithProgress(File src, File dest, java.util.concurrent.atomic.AtomicInteger doneCount,
                                       java.util.concurrent.atomic.AtomicLong doneBytes, int totalCount, long totalBytes,
                                       long startTime, ProgressBar pb, TextView tvStat) throws Exception {
        if (src.isDirectory()) {
            dest.mkdirs();
            File[] sub = src.listFiles();
            if (sub != null) for (File c : sub) {
                copyFileWithProgress(c, new File(dest, c.getName()), doneCount, doneBytes, totalCount, totalBytes, startTime, pb, tvStat);
            }
        } else {
            java.io.FileInputStream fis = new java.io.FileInputStream(src);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(dest);
            byte[] buf = new byte[65536];
            int len;
            long fileDone = 0;
            long fileSize = src.length();
            while ((len = fis.read(buf)) > 0) {
                fos.write(buf, 0, len);
                fileDone += len;
                doneBytes.addAndGet(len);
                if (fileSize > 0 && fileDone % (65536 * 4) == 0) {
                    long db = doneBytes.get();
                    double frac = Math.min(1.0, (double) db / (double) totalBytes);
                    int progress = (int) Math.round(frac * 1000.0);
                    int files = Math.min(doneCount.get() + 1, totalCount);
                    long now = System.currentTimeMillis();
                    long elapsed = Math.max(1, now - startTime);
                    double speed = db * 1000.0 / elapsed;
                    long eta = speed > 1e-6 ? (long) ((totalBytes - db) / speed * 1000.0) : 0;
                    String etaText = eta > 0 ? String.format(java.util.Locale.US, " 剩余 %d秒", eta / 1000) : "";
                    String stat = String.format(java.util.Locale.US, "%d/%d  %.1f%% %s", files, totalCount, frac * 100.0, etaText);
                    runOnUiThread(() -> {
                        if (!isFinishing()) {
                            pb.setProgress(progress);
                            tvStat.setText(stat);
                        }
                    });
                }
            }
            fis.close();
            fos.close();
            doneCount.incrementAndGet();
        }
    }

    private void copyFile(File src, File dest) throws Exception {
        if (src.isDirectory()) {
            dest.mkdirs();
            File[] sub = src.listFiles();
            if (sub != null) for (File c : sub) copyFile(c, new File(dest, c.getName()));
        } else {
            FileInputStream fis = new FileInputStream(src);
            FileOutputStream fos = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) fos.write(buf, 0, len);
            fis.close();
            fos.close();
        }
    }

    private void deleteFiles() {
        if (selectedPaths.isEmpty()) { showToast("请先选择文件"); return; }
        AlertDialog dialog = createBaseDialog("确认删除", createDialogMessageView("确定删除选中的 " + selectedPaths.size() + " 项？"))
            .setPositiveButton("删除", (d, w) -> {
                final ProgressBar pb = createHorizontalProgressBar(1000);
                final TextView tvStat = createDialogValueText("正在删除...", Gravity.CENTER_HORIZONTAL);
                final AlertDialog progressDialog = createProgressPanel("删除中", pb, tvStat);
                progressDialog.show();
                new Thread(() -> {
                    // 先统计总文件数
                    java.util.List<java.io.File> allFiles = new java.util.ArrayList<>();
                    for (String p : selectedPaths) countFiles(new File(p), allFiles);
                    final int totalCount = Math.max(allFiles.size(), 1);
                    runOnUiThread(() -> {
                        pb.setMax(1000);
                        tvStat.setText("0/" + totalCount);
                    });
                    final boolean[] cancelled = {false};
                    progressDialog.setOnDismissListener(dis -> cancelled[0] = true);
                    java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);
                    for (String p : selectedPaths) {
                        if (cancelled[0]) break;
                        rmRecursiveWithProgress(new File(p), done, totalCount, pb, tvStat);
                    }
                    selectedPaths.clear();
                    runOnUiThread(() -> {
                        progressDialog.dismiss();
                        showToast("删除完成");
                        navigateTo(currentPath);
                    });
                }).start();
            })
            .setNegativeButton("取消", null)
            .create();
        dialog.show();
        styleDialogButtons(dialog);
    }

    private void rmRecursiveWithProgress(File f, java.util.concurrent.atomic.AtomicInteger done, int total, ProgressBar pb, TextView tvStat) {
        if (f.isDirectory()) {
            File[] sub = f.listFiles();
            if (sub != null) for (File c : sub) rmRecursiveWithProgress(c, done, total, pb, tvStat);
        }
        f.delete();
        int c = done.incrementAndGet();
        if (c % 10 == 0 || c == total) {
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    pb.setProgress((int) Math.round((double) c / total * 1000.0));
                    tvStat.setText(c + "/" + total);
                }
            });
        }
    }

    private void showRenameDialog() {
        if (selectedPaths.size() != 1) { showToast("请选中一个文件"); return; }
        String path = selectedPaths.iterator().next();
        showRenameDialog(path);
    }

    private void showNewFolderDialog() {
        LinearLayout container = createDialogContainer();
        EditText et = createDialogEditText("文件夹名称", "");
        container.addView(et);
        AlertDialog dialog = createBaseDialog("新建文件夹", container)
            .setPositiveButton("创建", (d, w) -> {
                String name = et.getText().toString().trim();
                if (!name.isEmpty()) {
                    File f = new File(currentPath, name);
                    if (f.mkdirs()) showToast("创建成功");
                    else showToast("创建失败");
                    navigateTo(currentPath);
                }
            })
            .setNegativeButton("取消", null)
            .create();
        dialog.show();
        styleDialogButtons(dialog);
    }

    private void showFileInfo() {
        if (selectedPaths.isEmpty()) { showToast("请先选择文件"); return; }
        File f = new File(selectedPaths.iterator().next());
        StringBuilder sb = new StringBuilder();
        sb.append("路径: ").append(f.getAbsolutePath()).append("\n");
        sb.append("大小: ").append(Formatter.formatFileSize(this, f.length())).append("\n");
        sb.append("可读: ").append(f.canRead()).append("\n");
        sb.append("可写: ").append(f.canWrite());
        if (selectedPaths.size() > 1) sb.append("\n\n共选中 ").append(selectedPaths.size()).append(" 项");
        showMaterialInfoDialog("信息", sb.toString());
    }

    private void updateButtonState() {
        boolean hasSel = !selectedPaths.isEmpty();
        btnCut.setEnabled(hasSel);
        btnCopy.setEnabled(hasSel);
        btnDelete.setEnabled(hasSel);
        btnRename.setEnabled(hasSel);
        btnInfo.setEnabled(hasSel);
        btnPaste.setEnabled(!clipboardPaths.isEmpty());

    }

    private void parseStatOutput(String output, String basePath) {
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                // stat 输出: drwxr-xr-x 4096 1234567890 'FileName'
                // 文件名可能被单引号包裹，也可能不带
                String perm = line.substring(0, 10);
                String rest = line.substring(11).trim();
                // 分割出大小和时间戳（前两个字段）
                int firstSpace = rest.indexOf(' ');
                if (firstSpace < 0) continue;
                String sizeStr = rest.substring(0, firstSpace);
                rest = rest.substring(firstSpace + 1).trim();
                int secondSpace = rest.indexOf(' ');
                if (secondSpace < 0) continue;
                String timestamp = rest.substring(0, secondSpace);
                String namePart = rest.substring(secondSpace + 1).trim();
                // 去掉单引号
                if (namePart.startsWith("'") && namePart.endsWith("'"))
                    namePart = namePart.substring(1, namePart.length() - 1);
                // 去掉 " -> xxx" 符号链接
                int arrow = namePart.indexOf(" -> ");
                if (arrow > 0) namePart = namePart.substring(0, arrow);
                // 只取文件名部分
                String name = new File(namePart).getName();
                if (name.equals(".") || name.equals("..")) continue;
                long size = 0;
                try { size = Long.parseLong(sizeStr); } catch (Exception ignored) {}
                // 时间戳转为可读格式
                String mTime = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(Long.parseLong(timestamp) * 1000));
                FileItem item = new FileItem(basePath + "/" + name, name, perm.startsWith("d"), size, mTime, perm);
                fileList.add(item);
            } catch (Exception ignored) {}
        }
        sortFiles();
    }

    private void showToast(String msg) {
    android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    handler.post(() -> {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
    });
}

    class FileItem {
        String path, name, mTime, perm;
        long size;
        boolean isDir;
        boolean selected;

        FileItem() {}

        FileItem(String path, String name, boolean isDir, long size, String mTime, String perm) {
            this.path = path; this.name = name; this.isDir = isDir;
            this.size = size; this.mTime = mTime; this.perm = perm;
            this.selected = false;
        }

        FileItem(File f) {
            this.path = f.getAbsolutePath();
            this.name = f.getName();
            this.isDir = f.isDirectory();
            this.size = f.isDirectory() ? 0 : f.length();
            this.mTime = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(f.lastModified()));
            this.perm = f.canRead() ? (f.canWrite() ? "rw" : "r") : "-";
            this.selected = false;
        }
    }

    class FileAdapter extends BaseAdapter {
        @Override public int getCount() { return fileList.size(); }
        @Override public Object getItem(int i) { return fileList.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View v, ViewGroup p) {
            if (v == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                int pad = dp(14);
                row.setPadding(pad, dp(12), pad, dp(12));
                TextView title = new TextView(MainActivity.this);
                title.setId(android.R.id.text1);
                title.setTextSize(16f);
                title.setTextColor(getResources().getColor(android.R.color.black));
                title.setTypeface(null, android.graphics.Typeface.BOLD);
                TextView subtitle = new TextView(MainActivity.this);
                subtitle.setId(android.R.id.text2);
                subtitle.setTextSize(12f);
                subtitle.setTextColor(getResources().getColor(android.R.color.darker_gray));
                subtitle.setPadding(0, dp(4), 0, 0);
                row.addView(title);
                row.addView(subtitle);
                v = row;
            }
            FileItem item = fileList.get(i);
            TextView tv1 = v.findViewById(android.R.id.text1);
            TextView tv2 = v.findViewById(android.R.id.text2);
            tv1.setText(item.isDir ? "📁 " + item.name : "📄 " + item.name);
            if (item.selected) {
                v.setBackgroundColor(0xFFE3F2FD);
            } else {
                v.setBackgroundColor(0x00000000);
            }
            String sub;
            if (item.isDir) {
                sub = "目录";
                if (detailMode && item.mTime != null && !item.mTime.isEmpty()) {
                    sub += "  ·  " + item.mTime;
                }
            } else {
                sub = Formatter.formatFileSize(MainActivity.this, item.size);
                if (detailMode && item.mTime != null && !item.mTime.isEmpty()) {
                    sub += "  ·  " + item.mTime + "  ·  " + item.perm;
                }
            }
            if (!item.isDir && !detailMode && item.mTime != null && !item.mTime.isEmpty()) {
                sub += "  ·  " + item.mTime;
            }
            tv2.setText(sub);
            return v;
        }
    }
}
