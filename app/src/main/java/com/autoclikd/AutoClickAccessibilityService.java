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
import android.widget.LinearLayout;

import java.util.Locale;
import java.util.Random;

public class AutoClickAccessibilityService extends AccessibilityService {
    private static final String KEY_TARGET_PACKAGE = "target_package";
    private static final String KEY_POPUP_ENABLED = "popup_enabled";
    private static final String KEY_HIDE_ON_LEAVE = "hide_on_leave";
    private static final String KEY_SHOW_POSITION_BUTTON = "show_position_button";
    private static final String KEY_INTERVAL = "interval_ms";
    private static final String KEY_JITTER = "jitter_enabled";

    private static AutoClickAccessibilityService activeService;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private WindowManager windowManager;
    private SharedPreferences prefs;
    private String foregroundPackage = "";
    private boolean tapping;
    private boolean hidePending;
    private View popup;
    private LinearLayout popupRoot;
    private IconButton startButton;
    private IconButton positionButton;
    private boolean popupShowsPositionButton;
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
        if (onTarget) {
            if (popup == null) showPopup();
            else if (popupShowsPositionButton != prefs.getBoolean(KEY_SHOW_POSITION_BUTTON, false)) {
                removePopup();
                showPopup();
            } else updatePopupButtons();
        } else if (!onTarget && prefs.getBoolean(KEY_HIDE_ON_LEAVE, true)) {
            stopTapping();
            removePopup();
        }
    }

    private void showPopup() {
        if (popup != null || windowManager == null) return;
        popupRoot = new LinearLayout(this);
        popupRoot.setOrientation(LinearLayout.HORIZONTAL);
        int pad = dp(4);
        popupRoot.setPadding(pad, pad, pad, pad);
        GradientDrawable card = new GradientDrawable();
        card.setColor(0xEE171717);
        card.setCornerRadius(dp(16));
        card.setStroke(dp(1), Color.rgb(55, 55, 55));
        popupRoot.setBackground(card);
        startButton = new IconButton(false);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        popupRoot.addView(startButton, buttonParams);
        startButton.setOnClickListener(v -> {
            if (tapping) stopTapping();
            else startTapping();
            updatePopupButtons();
        });

        popupShowsPositionButton = prefs.getBoolean(KEY_SHOW_POSITION_BUTTON, false);
        positionButton = null;
        if (popupShowsPositionButton) {
            positionButton = new IconButton(true);
            LinearLayout.LayoutParams positionParams = new LinearLayout.LayoutParams(dp(44), dp(44));
            positionParams.leftMargin = dp(4);
            popupRoot.addView(positionButton, positionParams);
            positionButton.setOnClickListener(v -> beginPointSelection());
        }

        popupParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        popupParams.gravity = Gravity.TOP | Gravity.START;
        popupParams.x = dp(16);
        popupParams.y = dp(100);
        popup = popupRoot;
        try {
            windowManager.addView(popup, popupParams);
            updatePopupButtons();
        } catch (Exception e) {
            popup = null;
            popupRoot = null;
            startButton = null;
            positionButton = null;
            popupParams = null;
        }
    }

    private void updatePopupButtons() {
        if (startButton != null) startButton.refresh();
        if (positionButton != null) positionButton.refresh();
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
        updatePopupButtons();
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
        startButton = null;
        positionButton = null;
        popupShowsPositionButton = false;
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

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private final class IconButton extends View {
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean location;

        IconButton(boolean location) {
            super(AutoClickAccessibilityService.this);
            this.location = location;
            setClickable(true);
            setFocusable(true);
            updateDescription();
        }

        private void updateDescription() {
            setContentDescription(location ? "タップ位置を選ぶ" : (tapping ? "連打を停止" : "連打開始"));
        }

        void refresh() {
            updateDescription();
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(getWidth(), getHeight()) / 2f - dp(1);

            iconPaint.setStyle(Paint.Style.FILL);
            iconPaint.setColor(location || tapping ? Color.rgb(48, 48, 48) : Color.WHITE);
            canvas.drawCircle(cx, cy, radius, iconPaint);
            iconPaint.setColor(location || tapping ? Color.WHITE : Color.BLACK);
            iconPaint.setStrokeWidth(2f * density);
            iconPaint.setStrokeCap(Paint.Cap.ROUND);
            if (location) {
                iconPaint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(cx, cy, 6.5f * density, iconPaint);
                canvas.drawLine(cx, cy - 14f * density, cx, cy - 9f * density, iconPaint);
                canvas.drawLine(cx, cy + 9f * density, cx, cy + 14f * density, iconPaint);
                canvas.drawLine(cx - 14f * density, cy, cx - 9f * density, cy, iconPaint);
                canvas.drawLine(cx + 9f * density, cy, cx + 14f * density, cy, iconPaint);
            } else if (tapping) {
                iconPaint.setStyle(Paint.Style.FILL);
                float half = 5f * density;
                canvas.drawRoundRect(cx - half, cy - half, cx + half, cy + half, density, density, iconPaint);
            } else {
                iconPaint.setStyle(Paint.Style.FILL);
                Path triangle = new Path();
                triangle.moveTo(cx - 4f * density, cy - 9f * density);
                triangle.lineTo(cx + 9f * density, cy);
                triangle.lineTo(cx - 4f * density, cy + 9f * density);
                triangle.close();
                canvas.drawPath(triangle, iconPaint);
            }
        }
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
