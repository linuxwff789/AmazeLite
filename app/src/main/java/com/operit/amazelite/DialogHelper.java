package com.operit.amazelite;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.TextView;

public class DialogHelper {
    public static int dp(Context ctx, int v) {
        return Math.round(ctx.getResources().getDisplayMetrics().density * v);
    }
    public static AlertDialog showMaterialMessageDialog(Context ctx, String title, CharSequence msg, String pos) {
        LinearLayout c = createDialogContainer(ctx);
        c.addView(createDialogMessageView(ctx, msg));
        return createBaseDialog(ctx, title, c).setPositiveButton(pos, null).create();
    }
    public static AlertDialog.Builder createBaseDialog(Context ctx, String title, View v) {
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        if (title != null) b.setTitle(title);
        if (v != null) b.setView(v);
        return b;
    }
    public static LinearLayout createDialogContainer(Context ctx) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(ctx,20), dp(ctx,8), dp(ctx,20), dp(ctx,8));
        return c;
    }
    public static TextView createDialogMessageView(Context ctx, CharSequence t) {
        TextView tv = new TextView(ctx);
        tv.setText(t); tv.setTextColor(0xFF666666); tv.setTextSize(14);
        tv.setLineSpacing(dp(ctx,4),1);
        tv.setPadding(0, dp(ctx,8), 0, dp(ctx,8)); return tv;
    }
    public static EditText createDialogEditText(Context ctx, String hint, String val) {
        EditText et = new EditText(ctx);
        et.setHint(hint); if (val != null) et.setText(val);
        et.setSelection(et.getText().length()); et.setSingleLine();
        et.setTextColor(0xFF333333); et.setHintTextColor(0xFFAAAAAA);
        et.setBackgroundResource(android.R.drawable.editbox_background);
        et.setPadding(dp(ctx,8), dp(ctx,4), dp(ctx,8), dp(ctx,4)); return et;
    }
    public static Button createDialogActionButton(Context ctx, String text, int tc, int fc) {
        Button b = new Button(ctx);
        b.setText(text); b.setTextColor(tc); b.setTextSize(14);
        b.setPadding(dp(ctx,16), dp(ctx,6), dp(ctx,16), dp(ctx,6));
        b.setBackgroundColor(fc); b.setAllCaps(false);
        b.setMinHeight(0); b.setMinimumHeight(0); return b;
    }
    public static LinearLayout createDialogButtonRow(Context ctx) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(0, dp(ctx,4), 0, 0); return r;
    }
    public static void styleDialogButtons(AlertDialog d) {
        try { if (d == null) return;
            Button p = d.getButton(AlertDialog.BUTTON_POSITIVE);
            Button n = d.getButton(AlertDialog.BUTTON_NEGATIVE);
            Button u = d.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (p != null) { p.setTextColor(0xFF1565C0); p.setTextSize(14); }
            if (n != null) { n.setTextColor(0xFF999999); n.setTextSize(14); }
            if (u != null) { u.setTextColor(0xFF999999); u.setTextSize(14); }
        } catch (Exception ignored) {}
    }
    public static AlertDialog createProgressPanel(Context ctx, String title, ProgressBar pb, TextView... tvs) {
        LinearLayout c = createDialogContainer(ctx); c.addView(pb);
        for (TextView tv : tvs) if (tv != null) c.addView(tv);
        return createBaseDialog(ctx, title, c).setCancelable(false).create();
    }
    public static CheckBox createDialogCheckBox(Context ctx, String text, boolean checked) {
        CheckBox cb = new CheckBox(ctx);
        cb.setText(text); cb.setChecked(checked);
        cb.setTextColor(0xFF333333); cb.setTextSize(14); return cb;
    }
    public static LinearLayout.LayoutParams createWeightedButtonLayoutParams(Context ctx, boolean first) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(ctx,36), 1);
        p.setMargins(first ? 0 : dp(ctx,4), 0, 0, 0); return p;
    }
    public static TextView createDialogSectionLabel(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text); tv.setTextColor(0xFF1565C0); tv.setTextSize(13);
        tv.setPadding(0, dp(ctx,8), 0, dp(ctx,2)); return tv;
    }
    public static TextView createDialogValueText(Context ctx, String text, int gravity) {
        TextView tv = new TextView(ctx);
        tv.setText(text); tv.setTextColor(0xFF555555); tv.setTextSize(14);
        tv.setGravity(gravity); tv.setPadding(0, dp(ctx,2), 0, dp(ctx,2)); return tv;
    }
    public static RadioButton createDialogRadioButton(Context ctx, String text, int id) {
        RadioButton rb = new RadioButton(ctx);
        rb.setText(text); rb.setId(id); rb.setTextSize(14); rb.setTextColor(0xFF333333); return rb;
    }
    public static SeekBar createDialogSeekBar(Context ctx, int max, int progress) {
        SeekBar sb = new SeekBar(ctx); sb.setMax(max); sb.setProgress(progress); return sb;
    }
    public static ProgressBar createHorizontalProgressBar(Context ctx, int max) {
        ProgressBar pb = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(max); pb.setProgress(0);
        pb.setPadding(0, dp(ctx,4), 0, dp(ctx,4)); return pb;
    }
}
