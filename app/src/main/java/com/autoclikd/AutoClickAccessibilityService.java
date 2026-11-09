package com.autoclikd;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.Random;

public class AutoClickAccessibilityService extends AccessibilityService {
    private static final String KEY_TARGET_PACKAGE = "target_package";
    private static final String KEY_TARGET_LABEL = "target_label";
    private static final String KEY_POPUP_ENABLED = "popup_enabled";
    private static final String KEY_HIDE_ON_LEAVE = "hide_on_leave";
    private static final String KEY_INTERVAL = "interval_ms";
    private static final String KEY_JITTER = "jitter_enabled";

    private static AutoClickAccessibilityService activeService;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private WindowManager windowManager;
    private SharedPreferences prefs;
    private String foregroundPackage = "";
    private boolean tapping;
    private boolean dismissedForLaunch;
    private boolean hidePending;
    private View popup;
    private LinearLayout popupRoot;
    private TextView intervalLabel;
    private Button tapButton;
    private WindowManager.LayoutParams popupParams;
    private PointSelectionView pointSelectionView;
    private WindowManager.LayoutParams pickerParams;

    private final Runnable tapLoop = new Runnable() {
        @Override public void run() {
            if (!tapping || activeService != AutoClickAccessibilityService.this) return;
            dispatchTap();
            long base = Math.max(50, prefs.getInt(KEY_INTERVAL, 500));
            if (prefs.getBoolean(KEY_JITTER, false)) {
                double multiplier = 0.85d + random.nextDouble() * 0.30d;
                base = Math.max(50L, Math.round(base * multiplier));
            }
            handler.postDelayed(this, base);
        }
    };

    private final Runnable delayedHide = () -> {
        hidePending = false;
        if (prefs != null && prefs.getBoolean(KEY_HIDE_ON_LEAVE, true)
                && !targetPackage().equals(foregroundPackage)) {
            dismissedForLaunch = false;
            stopTapping();
            removePopup();
        }
    };

    public static AutoClickAccessibilityService current() {
        return activeService;
    }

    public static void refreshFromSettings() {
        if (activeService != null) activeService.reconcilePopup();
    }

    public static void onHostActivityResumed() {
        if (activeService != null) activeService.markHostActivityForeground();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        activeService = this;
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        String activePackage = activeWindowPackage();
        if (activePackage != null) foregroundPackage = activePackage;
        reconcilePopup();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        String eventPackage = event.getPackageName() == null ? "" : event.getPackageName().toString();
        String activePackage = activeWindowPackage();
        String target = targetPackage();

        // Window events can describe a transient window rather than the app the
        // user is looking at. The active window stays the selected app while our
        // non-focusable overlay is displayed, so prefer its package when available.
        String nextPackage;
        if (!target.isEmpty() && target.equals(eventPackage)) nextPackage = target;
        else if (!target.isEmpty() && target.equals(activePackage)) nextPackage = target;
        else if (activePackage != null) nextPackage = activePackage;
        else if (eventPackage.isEmpty() || getPackageName().equals(eventPackage)) return;
        else nextPackage = eventPackage;

        foregroundPackage = nextPackage;
        if (!target.isEmpty() && target.equals(nextPackage)) {
            if (hidePending) {
                handler.removeCallbacks(delayedHide);
                hidePending = false;
                dismissedForLaunch = false;
            }
            reconcilePopup();
        } else if (prefs.getBoolean(KEY_HIDE_ON_LEAVE, true)) {
            // Ignore brief intermediate windows during app startup/transition.
            handler.removeCallbacks(delayedHide);
            hidePending = true;
            handler.postDelayed(delayedHide, 600);
        } else {
            reconcilePopup();
        }
    }

    @Override
    public void onInterrupt() {
        stopTapping();
        removePopup();
    }

    @Override
    public void onDestroy() {
        stopTapping();
        removePointPicker();
        removePopup();
        if (activeService == this) activeService = null;
        super.onDestroy();
    }

    private String targetPackage() {
        return prefs == null ? "" : prefs.getString(KEY_TARGET_PACKAGE, "");
    }

    private String activeWindowPackage() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null) return null;
            return root.getPackageName().toString();
        } catch (Exception ignored) {
            return null;
        } finally {
            if (root != null) {
                try { root.recycle(); } catch (Exception ignored) { }
            }
        }
    }

    private void markHostActivityForeground() {
        handler.removeCallbacks(delayedHide);
        hidePending = false;
        foregroundPackage = getPackageName();
        dismissedForLaunch = false;
        if (prefs != null && prefs.getBoolean(KEY_HIDE_ON_LEAVE, true)) {
            stopTapping();
            removePopup();
        }
    }

    private void reconcilePopup() {
        if (prefs == null || windowManager == null) return;
        boolean enabled = prefs.getBoolean(KEY_POPUP_ENABLED, true);
        boolean overlayGranted = Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this);
        boolean onTarget = !targetPackage().isEmpty() && targetPackage().equals(foregroundPackage);
        if (!enabled || !overlayGranted) {
            stopTapping();
            removePopup();
            return;
        }
        if (onTarget && !dismissedForLaunch) {
            if (popup == null) showPopup();
            else updatePopupLabels();
        } else if (!onTarget && prefs.getBoolean(KEY_HIDE_ON_LEAVE, true)) {
            stopTapping();
            removePopup();
        }
    }

    private void showPopup() {
        if (popup != null || windowManager == null) return;
        popupRoot = new LinearLayout(this);
        popupRoot.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        popupRoot.setPadding(pad, dp(11), pad, pad);
        GradientDrawable card = new GradientDrawable();
        card.setColor(Color.rgb(19, 19, 19));
        card.setCornerRadius(dp(18));
        card.setStroke(dp(1), Color.rgb(65, 65, 65));
        popupRoot.setBackground(card);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = overlayText("AutoCLiKD", 16, Color.WHITE, true);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(34), 1));
        TextView close = overlayText("×", 25, Color.LTGRAY, false);
        close.setGravity(Gravity.CENTER);
        header.addView(close, new LinearLayout.LayoutParams(dp(34), dp(34)));
        close.setOnClickListener(v -> {
            dismissedForLaunch = true;
            stopTapping();
            removePopup();
        });
        popupRoot.addView(header);
        header.setOnTouchListener(new View.OnTouchListener() {
            private int startX;
            private int startY;
            private float downX;
            private float downY;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (popupParams == null) return false;
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    startX = popupParams.x;
                    startY = popupParams.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    popupParams.x = Math.max(0, startX + Math.round(event.getRawX() - downX));
                    popupParams.y = Math.max(0, startY + Math.round(event.getRawY() - downY));
                    try { windowManager.updateViewLayout(popupRoot, popupParams); } catch (Exception ignored) { }
                    return true;
                }
                return event.getAction() == MotionEvent.ACTION_UP;
            }
        });

        TextView target = overlayText(prefs.getString(KEY_TARGET_LABEL, "対象アプリ"), 13, Color.LTGRAY, false);
        LinearLayout.LayoutParams targetParams = new LinearLayout.LayoutParams(-1, -2);
        targetParams.topMargin = dp(2);
        popupRoot.addView(target, targetParams);
        intervalLabel = overlayText("間隔 500 ms ・ 位置は画面中央", 12, Color.rgb(175, 175, 175), false);
        LinearLayout.LayoutParams intervalParams = new LinearLayout.LayoutParams(-1, -2);
        intervalParams.topMargin = dp(5);
        intervalParams.bottomMargin = dp(11);
        popupRoot.addView(intervalLabel, intervalParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        tapButton = overlayButton(tapping ? "停止" : "連打開始", true);
        actions.addView(tapButton, new LinearLayout.LayoutParams(0, dp(42), 1));
        TextView position = overlayText("位置", 13, Color.WHITE, true);
        position.setGravity(Gravity.CENTER);
        position.setBackground(overlayShape(Color.rgb(48, 48, 48), Color.rgb(72, 72, 72)));
        LinearLayout.LayoutParams positionParams = new LinearLayout.LayoutParams(dp(68), dp(42));
        positionParams.leftMargin = dp(8);
        actions.addView(position, positionParams);
        position.setOnClickListener(v -> beginPointSelection());
        tapButton.setOnClickListener(v -> {
            if (tapping) stopTapping();
            else startTapping();
            updatePopupLabels();
        });
        popupRoot.addView(actions);

        popupParams = new WindowManager.LayoutParams(
                dp(280), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        popupParams.gravity = Gravity.TOP | Gravity.START;
        popupParams.x = dp(16);
        popupParams.y = dp(100);
        popup = popupRoot;
        try {
            windowManager.addView(popup, popupParams);
            updatePopupLabels();
        } catch (Exception e) {
            popup = null;
            popupRoot = null;
            popupParams = null;
        }
    }

    private void updatePopupLabels() {
        if (intervalLabel == null || popup == null) return;
        int interval = Math.max(50, prefs.getInt(KEY_INTERVAL, 500));
        float x = prefs.getFloat("tap_x", -1f);
        float y = prefs.getFloat("tap_y", -1f);
        String location = x < 0 || y < 0 ? "画面中央" : String.format(Locale.JAPAN, "%.0f%% / %.0f%%", x * 100, y * 100);
        String variation = prefs.getBoolean(KEY_JITTER, false) ? " ・ ±15%ゆらぎ" : "";
        intervalLabel.setText("間隔 " + interval + " ms" + variation + "\n位置 " + location);
        if (tapButton != null) tapButton.setText(tapping ? "停止" : "連打開始");
    }

    private void startTapping() {
        if (tapping || popup == null) return;
        tapping = true;
        handler.removeCallbacks(tapLoop);
        tapLoop.run();
    }

    private void stopTapping() {
        tapping = false;
        handler.removeCallbacks(tapLoop);
        if (tapButton != null) tapButton.setText("連打開始");
    }

    private void dispatchTap() {
        Point display = new Point();
        windowManager.getDefaultDisplay().getRealSize(display);
        float nx = prefs.getFloat("tap_x", 0.5f);
        float ny = prefs.getFloat("tap_y", 0.5f);
        float x = Math.max(1, Math.min(display.x - 1, nx * display.x));
        float y = Math.max(1, Math.min(display.y - 1, ny * display.y));
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 55))
                .build();
        dispatchGesture(gesture, null, handler);
    }

    private void removePopup() {
        if (popup != null && windowManager != null) {
            try { windowManager.removeView(popup); } catch (Exception ignored) { }
        }
        popup = null;
        popupRoot = null;
        intervalLabel = null;
        tapButton = null;
        popupParams = null;
    }

    public void beginPointSelection() {
        if (pointSelectionView != null || windowManager == null) return;
        stopTapping();
        removePopup();
        pointSelectionView = new PointSelectionView(this);
        pickerParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        pickerParams.gravity = Gravity.TOP | Gravity.START;
        try {
            windowManager.addView(pointSelectionView, pickerParams);
        } catch (Exception e) {
            pointSelectionView = null;
            pickerParams = null;
        }
    }

    private void finishPointSelection(boolean saved) {
        removePointPicker();
        if (saved) android.widget.Toast.makeText(this, "タップ位置を保存しました", android.widget.Toast.LENGTH_SHORT).show();
        reconcilePopup();
    }

    private void removePointPicker() {
        if (pointSelectionView != null && windowManager != null) {
            try { windowManager.removeView(pointSelectionView); } catch (Exception ignored) { }
        }
        pointSelectionView = null;
        pickerParams = null;
    }

    private TextView overlayText(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private Button overlayButton(String value, boolean prominent) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTextColor(prominent ? Color.BLACK : Color.WHITE);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(overlayShape(prominent ? Color.WHITE : Color.rgb(48, 48, 48), Color.TRANSPARENT));
        return button;
    }

    private GradientDrawable overlayShape(int background, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(background);
        shape.setCornerRadius(dp(12));
        if (stroke != Color.TRANSPARENT) shape.setStroke(dp(1), stroke);
        return shape;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private final class PointSelectionView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float candidateX;
        private float candidateY;
        private int actionOnUp;
        private RectF saveRect;
        private RectF cancelRect;

        PointSelectionView(Context context) {
            super(context);
            Point display = new Point();
            windowManager.getDefaultDisplay().getRealSize(display);
            float nx = prefs.getFloat("tap_x", 0.5f);
            float ny = prefs.getFloat("tap_y", 0.5f);
            candidateX = nx * display.x;
            candidateY = ny * display.y;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float width = getWidth();
            float height = getHeight();
            paint.setColor(0xCC000000);
            canvas.drawRect(0, 0, width, height, paint);

            paint.setColor(Color.WHITE);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            paint.setTextSize(20 * density);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("タップ位置を選択", width / 2, 66 * density, paint);
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(14 * density);
            paint.setColor(0xFFE0E0E0);
            canvas.drawText("画面上の位置をタップしてから決定してください", width / 2, 94 * density, paint);

            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * density);
            canvas.drawCircle(candidateX, candidateY, 18 * density, paint);
            canvas.drawLine(candidateX - 30 * density, candidateY, candidateX + 30 * density, candidateY, paint);
            canvas.drawLine(candidateX, candidateY - 30 * density, candidateX, candidateY + 30 * density, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(13 * density);
            canvas.drawText(String.format(Locale.JAPAN, "%.0f%% / %.0f%%", candidateX / Math.max(1, width) * 100, candidateY / Math.max(1, height) * 100),
                    Math.min(candidateX + 24 * density, width - 110 * density), Math.max(candidateY - 24 * density, 120 * density), paint);

            float margin = 22 * density;
            saveRect = new RectF(margin, height - 76 * density, width - margin, height - 20 * density);
            paint.setColor(Color.WHITE);
            canvas.drawRoundRect(saveRect, 16 * density, 16 * density, paint);
            paint.setColor(Color.BLACK);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(15 * density);
            canvas.drawText("この位置に設定", saveRect.centerX(), saveRect.centerY() + 5 * density, paint);

            cancelRect = new RectF(width - 122 * density, 18 * density, width - 18 * density, 58 * density);
            paint.setColor(0xFF333333);
            canvas.drawRoundRect(cancelRect, 18 * density, 18 * density, paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(13 * density);
            canvas.drawText("キャンセル", cancelRect.centerX(), cancelRect.centerY() + 4 * density, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX();
            float y = event.getY();
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                actionOnUp = cancelRect != null && cancelRect.contains(x, y) ? 2
                        : (saveRect != null && saveRect.contains(x, y) ? 1 : 0);
                if (actionOnUp == 0) {
                    candidateX = x;
                    candidateY = y;
                    invalidate();
                }
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                if (actionOnUp == 1 && saveRect != null && saveRect.contains(x, y)) {
                    Point display = new Point();
                    windowManager.getDefaultDisplay().getRealSize(display);
                    prefs.edit()
                            .putFloat("tap_x", Math.max(0f, Math.min(1f, candidateX / Math.max(1, getWidth()))))
                            .putFloat("tap_y", Math.max(0f, Math.min(1f, candidateY / Math.max(1, getHeight()))))
                            .apply();
                    finishPointSelection(true);
                } else if (actionOnUp == 2) {
                    finishPointSelection(false);
                }
                actionOnUp = 0;
                return true;
            }
            return true;
        }
    }
}
