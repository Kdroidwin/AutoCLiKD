package com.autoclikd;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {
    static final String PREFS = "autoclikd_settings";
    private static final String KEY_THEME = "theme";
    private static final String KEY_TARGET_PACKAGE = "target_package";
    private static final String KEY_TARGET_LABEL = "target_label";
    private static final String KEY_POPUP_ENABLED = "popup_enabled";
    private static final String KEY_HIDE_ON_LEAVE = "hide_on_leave";
    private static final String KEY_SHOW_POSITION_BUTTON = "show_position_button";
    private static final String KEY_INTERVAL = "interval_ms";
    private static final String KEY_JITTER = "jitter_enabled";

    private android.content.SharedPreferences prefs;
    private boolean lightTheme;
    private TextView targetValue;
    private TextView overlayStatus;
    private TextView accessibilityStatus;
    private TextView pointStatus;
    private TextView intervalValue;
    private SeekBar intervalSeekBar;
    private Switch popupSwitch;
    private Switch hideSwitch;
    private Switch positionSwitch;
    private Switch jitterSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        lightTheme = resolveLightTheme();
        setTheme(lightTheme ? R.style.Theme_AutoCliKD_Light : R.style.Theme_AutoCliKD_Dark);
        super.onCreate(savedInstanceState);
        styleSystemBars();
        buildScreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AutoClickAccessibilityService.onHostActivityResumed();
        if (overlayStatus != null) {
            updatePermissionStatus();
            updatePointLabel();
        }
    }

    private boolean resolveLightTheme() {
        String theme = prefs.getString(KEY_THEME, "amoled");
        if ("light".equals(theme)) return true;
        if ("system".equals(theme)) {
            int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return night == Configuration.UI_MODE_NIGHT_NO;
        }
        return false;
    }

    private void styleSystemBars() {
        Window window = getWindow();
        int background = lightTheme ? Color.WHITE : Color.BLACK;
        window.setStatusBarColor(background);
        window.setNavigationBarColor(background);
        if (Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) window.setStatusBarContrastEnforced(false);
        int flags = lightTheme
                ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                : 0;
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private int color(String darkHex, String lightHex) {
        return Color.parseColor(lightTheme ? lightHex : darkHex);
    }

    private void buildScreen() {
        int bg = color("#000000", "#F5F5F5");
        int text = color("#FFFFFF", "#171717");
        int muted = color("#A5A5A5", "#646464");
        int surface = color("#101010", "#FFFFFF");
        int outline = color("#292929", "#E4E4E4");
        int accent = color("#FFFFFF", "#111111");

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(20), dp(22), dp(36));
        scroll.addView(page);

        TextView eyebrow = text("ACCESSIBILITY AUTOMATION", 11, muted, true);
        eyebrow.setLetterSpacing(0.12f);
        page.addView(eyebrow);
        TextView title = text("AutoCLiKD", 32, text, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(5);
        page.addView(title, titleParams);
        TextView description = text("アプリを開いたら、画面上のメニューから自動連打", 14, muted, false);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(-1, -2);
        descParams.topMargin = dp(4);
        descParams.bottomMargin = dp(22);
        page.addView(description, descParams);

        LinearLayout appCard = card(surface, outline);
        addSectionHeader(appCard, "対象アプリ", "連打メニューを表示するアプリを選択");
        LinearLayout targetRow = new LinearLayout(this);
        targetRow.setGravity(Gravity.CENTER_VERTICAL);
        targetRow.setPadding(dp(14), dp(13), dp(14), dp(13));
        targetRow.setBackground(rounded(color("#191919", "#F6F6F6"), dp(14), 0));
        targetValue = text("アプリを選択してください", 15, text, true);
        targetRow.addView(targetValue, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = text("›", 27, muted, false);
        targetRow.addView(arrow);
        targetRow.setOnClickListener(v -> chooseTargetApp());
        appCard.addView(targetRow);
        popupSwitch = addSwitchRow(appCard, "起動時に連打メニューを表示", "選択したアプリを開くとポップアップを表示", true, KEY_POPUP_ENABLED);
        hideSwitch = addSwitchRow(appCard, "アプリを離れたらメニューを隠す", "初期設定では、対象アプリ以外に移ると自動で閉じます", true, KEY_HIDE_ON_LEAVE);
        positionSwitch = addSwitchRow(appCard, "位置ボタンを表示", "オフなら連打ボタンだけを表示（初期設定）", false, KEY_SHOW_POSITION_BUTTON);
        addCard(page, appCard);

        LinearLayout permissions = card(surface, outline);
        addSectionHeader(permissions, "必要な許可", "Android の設定画面で有効にしてください");
        overlayStatus = text("重ね表示: 確認中", 14, muted, false);
        permissions.addView(overlayStatus);
        Button overlayButton = button("重ね表示を許可", accent, lightTheme ? Color.WHITE : Color.BLACK);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, dp(48));
        actionParams.topMargin = dp(10);
        permissions.addView(overlayButton, actionParams);
        overlayButton.setOnClickListener(v -> openOverlaySettings());

        accessibilityStatus = text("ユーザー補助: 確認中", 14, muted, false);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(16);
        permissions.addView(accessibilityStatus, statusParams);
        Button accessibilityButton = button("ユーザー補助サービスを有効化", accent, lightTheme ? Color.WHITE : Color.BLACK);
        LinearLayout.LayoutParams accessibilityParams = new LinearLayout.LayoutParams(-1, dp(48));
        accessibilityParams.topMargin = dp(10);
        permissions.addView(accessibilityButton, accessibilityParams);
        accessibilityButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        addCard(page, permissions);

        LinearLayout tapping = card(surface, outline);
        addSectionHeader(tapping, "連打設定", "対象アプリで使うタップ位置と間隔");
        pointStatus = text("タップ位置: 画面中央（初期値）", 14, muted, false);
        tapping.addView(pointStatus);
        Button pointButton = button("タップ位置を選ぶ", accent, lightTheme ? Color.WHITE : Color.BLACK);
        LinearLayout.LayoutParams pointParams = new LinearLayout.LayoutParams(-1, dp(48));
        pointParams.topMargin = dp(10);
        tapping.addView(pointButton, pointParams);
        pointButton.setOnClickListener(v -> chooseTapPosition());

        LinearLayout intervalHeader = new LinearLayout(this);
        intervalHeader.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams intervalHeaderParams = new LinearLayout.LayoutParams(-1, -2);
        intervalHeaderParams.topMargin = dp(21);
        tapping.addView(intervalHeader, intervalHeaderParams);
        TextView intervalLabel = text("連打間隔", 15, text, true);
        intervalHeader.addView(intervalLabel, new LinearLayout.LayoutParams(0, -2, 1));
        intervalValue = text("500 ms", 15, text, true);
        intervalHeader.addView(intervalValue);
        intervalSeekBar = new SeekBar(this);
        intervalSeekBar.setMax(99);
        intervalSeekBar.setProgress(Math.max(0, Math.min(99, (prefs.getInt(KEY_INTERVAL, 500) - 50) / 50)));
        intervalSeekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        intervalSeekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(-1, -2);
        seekParams.topMargin = dp(2);
        tapping.addView(intervalSeekBar, seekParams);
        intervalSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int ms = 50 + progress * 50;
                intervalValue.setText(ms + " ms");
                if (fromUser) prefs.edit().putInt(KEY_INTERVAL, ms).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        TextView range = text("50 ms  ·  5,000 ms", 12, muted, false);
        tapping.addView(range);
        jitterSwitch = addSwitchRow(tapping, "検出防止（間隔をランダム化）", "設定間隔から ±15% の範囲でゆらぎを加えます", false, KEY_JITTER);
        TextView jitterNote = text("アプリ側の検知や利用規約を回避できる機能ではありません。", 12, muted, false);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.leftMargin = dp(4);
        noteParams.rightMargin = dp(4);
        noteParams.topMargin = dp(4);
        tapping.addView(jitterNote, noteParams);
        addCard(page, tapping);

        LinearLayout appearance = card(surface, outline);
        addSectionHeader(appearance, "表示", "AMOLED ブラック・ライト・端末設定に対応");
        Spinner themeSpinner = new Spinner(this);
        String[] themes = {"AMOLED ブラック", "ライト", "端末設定に合わせる"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, themes);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        themeSpinner.setAdapter(adapter);
        String theme = prefs.getString(KEY_THEME, "amoled");
        themeSpinner.setSelection("light".equals(theme) ? 1 : ("system".equals(theme) ? 2 : 0));
        appearance.addView(themeSpinner);
        themeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            private boolean initialized;
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (!initialized) { initialized = true; return; }
                String next = position == 1 ? "light" : (position == 2 ? "system" : "amoled");
                if (!next.equals(prefs.getString(KEY_THEME, "amoled"))) {
                    prefs.edit().putString(KEY_THEME, next).apply();
                    recreate();
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        addCard(page, appearance);

        TextView footer = text("AutoCLiKD  ·  オープンソース  ·  v1.1.0", 12, muted, false);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(-1, -2);
        footerParams.topMargin = dp(6);
        page.addView(footer, footerParams);

        setContentView(scroll);
        updateTargetLabel();
        updatePermissionStatus();
        updatePointLabel();
    }

    private void updatePermissionStatus() {
        boolean overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        overlayStatus.setText("重ね表示: " + (overlay ? "許可済み" : "未許可"));
        overlayStatus.setTextColor(overlay ? color("#A5A5A5", "#646464") : color("#FFB4AB", "#B3261E"));
        boolean service = isAccessibilityServiceEnabled();
        accessibilityStatus.setText("ユーザー補助: " + (service ? "有効" : "未有効"));
        accessibilityStatus.setTextColor(service ? color("#A5A5A5", "#646464") : color("#FFB4AB", "#B3261E"));
        AutoClickAccessibilityService.refreshFromSettings();
    }

    private void updateTargetLabel() {
        String label = prefs.getString(KEY_TARGET_LABEL, "");
        targetValue.setText(label.isEmpty() ? "アプリを選択してください" : label);
    }

    private void updatePointLabel() {
        float x = prefs.getFloat("tap_x", -1f);
        float y = prefs.getFloat("tap_y", -1f);
        if (x < 0f || y < 0f) pointStatus.setText("タップ位置: 画面中央（初期値）");
        else pointStatus.setText(String.format(java.util.Locale.JAPAN, "タップ位置: 横 %.0f%% ・ 縦 %.0f%%", x * 100f, y * 100f));
    }

    private void chooseTargetApp() {
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        PackageManager pm = getPackageManager();
        List<ResolveInfo> results;
        if (Build.VERSION.SDK_INT >= 33) {
            results = pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0));
        } else {
            //noinspection deprecation
            results = pm.queryIntentActivities(launcherIntent, 0);
        }
        List<ResolveInfo> apps = new ArrayList<>();
        for (ResolveInfo info : results) {
            if (info.activityInfo == null || getPackageName().equals(info.activityInfo.packageName)) continue;
            apps.add(info);
        }
        Collections.sort(apps, Comparator.comparing(info -> info.loadLabel(pm).toString(), String.CASE_INSENSITIVE_ORDER));
        if (apps.isEmpty()) {
            Toast.makeText(this, "起動できるアプリが見つかりません", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] labels = new CharSequence[apps.size()];
        int selected = -1;
        String current = prefs.getString(KEY_TARGET_PACKAGE, "");
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i).loadLabel(pm);
            if (apps.get(i).activityInfo.packageName.equals(current)) selected = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("対象アプリを選択")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    ResolveInfo chosen = apps.get(which);
                    prefs.edit()
                            .putString(KEY_TARGET_PACKAGE, chosen.activityInfo.packageName)
                            .putString(KEY_TARGET_LABEL, chosen.loadLabel(pm).toString())
                            .apply();
                    updateTargetLabel();
                    AutoClickAccessibilityService.refreshFromSettings();
                    dialog.dismiss();
                })
                .setNeutralButton("選択を解除", (dialog, which) -> {
                    prefs.edit().remove(KEY_TARGET_PACKAGE).remove(KEY_TARGET_LABEL).apply();
                    updateTargetLabel();
                    AutoClickAccessibilityService.refreshFromSettings();
                })
                .setNegativeButton("閉じる", null)
                .show();
    }

    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } else {
            Toast.makeText(this, "重ね表示は許可済みです", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName expected = new ComponentName(this, AutoClickAccessibilityService.class);
        for (String entry : enabled.split(":")) {
            ComponentName component = ComponentName.unflattenFromString(entry);
            if (expected.equals(component)) return true;
        }
        return false;
    }

    private void chooseTapPosition() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "先に重ね表示を許可してください", Toast.LENGTH_SHORT).show();
            openOverlaySettings();
            return;
        }
        if (!isAccessibilityServiceEnabled() || AutoClickAccessibilityService.current() == null) {
            Toast.makeText(this, "先に AutoCLiKD のユーザー補助サービスを有効にしてください", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        AutoClickAccessibilityService.current().beginPointSelection();
    }

    private Switch addSwitchRow(LinearLayout parent, String title, String subtitle, boolean defaultValue, String key) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(13), 0, dp(3));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView heading = text(title, 14, color("#FFFFFF", "#171717"), true);
        TextView detail = text(subtitle, 12, color("#A5A5A5", "#646464"), false);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = dp(3);
        copy.addView(heading);
        copy.addView(detail, detailParams);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(this);
        toggle.setChecked(prefs.getBoolean(key, defaultValue));
        row.addView(toggle);
        parent.addView(row);
        row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(key, checked).apply();
            AutoClickAccessibilityService.refreshFromSettings();
        });
        return toggle;
    }

    private void addSectionHeader(LinearLayout card, String title, String description) {
        TextView heading = text(title, 18, color("#FFFFFF", "#171717"), true);
        card.addView(heading);
        TextView detail = text(description, 13, color("#A5A5A5", "#646464"), false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(3);
        params.bottomMargin = dp(14);
        card.addView(detail, params);
    }

    private void addCard(LinearLayout page, LinearLayout card) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(14);
        page.addView(card, params);
    }

    private LinearLayout card(int background, int outline) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(17), dp(17), dp(17), dp(17));
        card.setBackground(rounded(background, dp(20), outline));
        return card;
    }

    private GradientDrawable rounded(int background, int radius, int outline) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(background);
        shape.setCornerRadius(radius);
        if (outline != 0) shape.setStroke(dp(1), outline);
        return shape;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String value, int background, int foreground) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(foreground);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackground(rounded(background, dp(14), 0));
        return button;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
