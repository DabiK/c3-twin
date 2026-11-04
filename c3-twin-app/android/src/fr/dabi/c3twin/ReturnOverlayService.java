package fr.dabi.c3twin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;

/** A return shortcut only. Zlink retains its own display, audio and phone session. */
public final class ReturnOverlayService extends Service {
    private static final String CHANNEL = "carplay-return";
    private static final String HIDE = "fr.dabi.c3twin.HIDE_RETURN";
    private final Handler main = new Handler();
    private WindowManager windows;
    private TextView button;
    private WindowManager.LayoutParams layout;
    private final Runnable show = this::showButton;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && HIDE.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager notifications = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Retour à C3 Twin",
            NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        notifications.createNotificationChannel(channel);
        PendingIntent back = PendingIntent.getActivity(this, 0, homeIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent hide = PendingIntent.getService(this, 1,
            new Intent(this, ReturnOverlayService.class).setAction(HIDE),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        startForeground(42, new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_revert)
            .setContentTitle("Retour à C3 Twin")
            .setContentText("Touchez pour retrouver le dashboard")
            .setContentIntent(back).setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "Masquer le bouton", hide).build())
            .build());
        main.removeCallbacks(show);
        // Let Zlink take the foreground before adding the shortcut.
        main.postDelayed(show, 350);
        return START_NOT_STICKY;
    }

    private Intent homeIntent() {
        return new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showButton() {
        if (MainActivity.dashboardVisible || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        if (button != null) return;
        windows = getSystemService(WindowManager.class);
        button = new TextView(this);
        button.setText("‹  C3");
        button.setTextColor(Color.WHITE);
        button.setTextSize(17);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setContentDescription("Revenir à C3 Twin. Déplacer pour repositionner.");
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xD922252A);
        background.setCornerRadius(dp(24));
        background.setStroke(dp(1), 0x35FFFFFF);
        button.setBackground(background);
        layout = new WindowManager.LayoutParams(dp(88), dp(48),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.LEFT;
        restorePosition();
        button.setOnClickListener(v -> {
            try { startActivity(homeIntent()); }
            catch (RuntimeException e) { Log.w("C3Twin", "Retour dashboard", e); }
            stopSelf();
        });
        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        button.setOnTouchListener(new android.view.View.OnTouchListener() {
            float startX, startY;
            int originalX, originalY;
            boolean dragged;
            @Override public boolean onTouch(android.view.View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = e.getRawX(); startY = e.getRawY();
                        originalX = layout.x; originalY = layout.y;
                        dragged = false;
                        button.setAlpha(1f);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - startX, dy = e.getRawY() - startY;
                        if (dx * dx + dy * dy > slop * slop) dragged = true;
                        if (dragged) {
                            layout.x = originalX + Math.round(dx);
                            layout.y = originalY + Math.round(dy);
                            clamp();
                            try { windows.updateViewLayout(button, layout); }
                            catch (RuntimeException ex) { stopSelf(); }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragged) savePosition(); else v.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default: return true;
                }
            }
        });
        try { windows.addView(button, layout); }
        catch (RuntimeException e) {
            button = null;
            Log.w("C3Twin", "Fenêtre retour indisponible", e);
            stopSelf();
        }
    }

    private Point bounds() {
        Point size = new Point();
        windows.getDefaultDisplay().getSize(size);
        return new Point(Math.max(0, size.x - layout.width), Math.max(0, size.y - layout.height));
    }

    private void clamp() {
        Point b = bounds();
        layout.x = Math.max(0, Math.min(layout.x, b.x));
        layout.y = Math.max(0, Math.min(layout.y, b.y));
    }

    private void restorePosition() {
        Point b = bounds();
        android.content.SharedPreferences prefs = getSharedPreferences("returnOverlay", MODE_PRIVATE);
        layout.x = Math.round(b.x * prefs.getFloat("x", .98f));
        layout.y = Math.round(b.y * prefs.getFloat("y", .02f));
        clamp();
    }

    private void savePosition() {
        Point b = bounds();
        getSharedPreferences("returnOverlay", MODE_PRIVATE).edit()
            .putFloat("x", b.x == 0 ? 0 : layout.x / (float) b.x)
            .putFloat("y", b.y == 0 ? 0 : layout.y / (float) b.y).apply();
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (button != null) {
            restorePosition();
            try { windows.updateViewLayout(button, layout); }
            catch (RuntimeException e) { stopSelf(); }
        }
    }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (button != null) {
            try { windows.removeView(button); }
            catch (IllegalArgumentException ignored) { }
            button = null;
        }
        stopForeground(true);
        super.onDestroy();
    }
}
