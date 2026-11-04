package fr.dabi.c3twin;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * Displays the iPhone's own grid and forwards touches inside the projected image,
 * with the car in 3D in a panel on the right (the twin page in its side mode).
 */
public final class ProjectionActivity extends Activity implements SurfaceHolder.Callback, CarData.Listener {
    /** Share of the screen width the car panel takes. */
    private static final float PANEL_SHARE = 0.3f;
    private static final int MAX_ATTEMPTS = 10;
    private final Handler main = new Handler();
    private FrameLayout area;
    private SurfaceView screen;
    private TextView status;
    private Button retry;
    private ZlinkStream stream;
    private WebView twin;
    private boolean twinReady;
    private TextView handle;
    private boolean folded;
    private ValueAnimator folding;
    private AudioStream audioMain, audioSystem;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean resumed, available;
    private int width, height, generation, attempts;
    /** Black margins ZLink leaves around the picture (left, top, right, bottom), cropped off screen. */
    private final int[] margins = new int[4];
    private MediaSession keySession;
    private float musicVolume = 1f;
    /** Music drops to this while Siri or a navigation prompt speaks, as CarPlay does in a car. */
    private static final float DUCKED = 0.25f;
    private final Runnable duck = new Runnable() {
        @Override public void run() {
            if (audioMain != null && audioSystem != null) {
                float target = audioSystem.speaking(450) ? DUCKED : 1f;
                // quick down, slower back up: a prompt is heard at once, the music returns gently
                float step = target < musicVolume ? 0.25f : 0.08f;
                float next = target < musicVolume ? Math.max(target, musicVolume - step) : Math.min(target, musicVolume + step);
                if (next != musicVolume) {
                    boolean wasFull = musicVolume >= 1f;
                    musicVolume = next;
                    audioMain.setVolume(next);
                    if (wasFull) Log.i("C3Twin", "Audio: musique baissée pendant la voix");
                    else if (next >= 1f) Log.i("C3Twin", "Audio: musique rétablie");
                }
            }
            main.postDelayed(this, 100);
        }
    };
    private final Runnable timeout = () -> retryOrFail("Aucune image reçue de Zlink.");
    private final Runnable nextAttempt = this::start;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.BLACK);
        LinearLayout bar = new LinearLayout(this);
        page.addView(bar);
        button(bar, "‹  Voiture", v -> finish());
        TextView title = new TextView(this);
        title.setText("CarPlay");
        title.setGravity(Gravity.CENTER);
        bar.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
        retry = button(bar, "Réessayer", v -> start());
        button(bar, "Ouvrir Zlink", v -> openZlink());
        area = new FrameLayout(this);
        page.addView(area, new LinearLayout.LayoutParams(-1, 0, 1));
        screen = new SurfaceView(this);
        area.addView(screen, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(19);
        status.setGravity(Gravity.CENTER);
        area.addView(status, new FrameLayout.LayoutParams(-1, -1));
        screen.setOnTouchListener(this::touch);
        screen.getHolder().addCallback(this);
        area.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> fit());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.BLACK);
        root.addView(page, new LinearLayout.LayoutParams(0, -1, 1 - PANEL_SHARE));
        handle = new TextView(this);
        handle.setGravity(Gravity.CENTER);
        handle.setTextColor(0xFF8A93A0);
        handle.setTextSize(22);
        handle.setBackgroundColor(0xFF0B0E13);
        handle.setOnClickListener(v -> setFolded(!folded, true));
        root.addView(handle, new LinearLayout.LayoutParams(Math.round(28 * getResources().getDisplayMetrics().density), -1));
        twin = sidePanel();
        root.addView(twin, new LinearLayout.LayoutParams(0, -1, PANEL_SHARE));
        setContentView(root);
        setFolded(prefs().getBoolean("panel_folded", false), false);
    }

    private SharedPreferences prefs() { return getSharedPreferences("carplay", MODE_PRIVATE); }

    /**
     * Folds the car panel away (CarPlay takes the whole width) or brings it back. The handle
     * stays as a thin strip; the 3D stops drawing while folded. Remembered for next time.
     */
    private void setFolded(boolean fold, boolean animate) {
        folded = fold;
        prefs().edit().putBoolean("panel_folded", fold).apply();
        handle.setText(fold ? "‹" : "›");
        handle.setContentDescription(fold ? "Afficher la voiture" : "Replier la voiture");
        if (folding != null) folding.cancel();
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) twin.getLayoutParams();
        float to = fold ? 0f : PANEL_SHARE;
        if (!fold) { twin.setVisibility(View.VISIBLE); if (resumed) twin.onResume(); }
        Runnable done = () -> { if (folded) { twin.setVisibility(View.GONE); twin.onPause(); } };
        if (!animate) { p.weight = to; twin.setLayoutParams(p); done.run(); return; }
        folding = ValueAnimator.ofFloat(p.weight, to).setDuration(260);
        folding.addUpdateListener(a -> { p.weight = (float) a.getAnimatedValue(); twin.setLayoutParams(p); });
        folding.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { done.run(); }
        });
        folding.start();
    }

    /** The twin page in side mode: the car, the speed, the lamps and the car's warnings. */
    private WebView sidePanel() {
        WebView w = new WebView(this);
        w.setBackgroundColor(0xFF05070A);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        w.setWebViewClient(new AssetClient(this));
        w.addJavascriptInterface(new SideBridge(), "Android");
        w.loadUrl(AssetClient.ORIGIN + "index.html?mode=side");
        return w;
    }

    /** What the side panel may ask (window.Android). */
    private final class SideBridge {
        @JavascriptInterface public void ready() {
            twinReady = true;
            main.post(() -> pushCar(CarData.get(ProjectionActivity.this).snapshot()));
        }
        /** A tap on the car goes back to the dashboard. */
        @JavascriptInterface public void home() { main.post(ProjectionActivity.this::finish); }
        @JavascriptInterface public void openCarplay() { }
        @JavascriptInterface public void launch(String pkg) { }
        @JavascriptInterface public String apps() { return "[]"; }
    }

    @Override public void onCarChanged(JSONObject state) { main.post(() -> pushCar(state)); }

    private void pushCar(JSONObject state) {
        if (twinReady && twin != null) twin.evaluateJavascript("window.twin && twin.set(" + state + ")", null);
    }

    private Button button(LinearLayout bar, String text, View.OnClickListener action) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setOnClickListener(action);
        bar.addView(b);
        return b;
    }

    private void start() {
        if (!resumed || !available || stream != null) return;
        status.setVisibility(View.VISIBLE);
        status.setText(attempts == 0 ? "Recherche de la projection Zlink…"
            : "En attente de l’iPhone… (" + attempts + "/" + MAX_ATTEMPTS + ")");
        retry.setVisibility(View.GONE);
        int attempt = ++generation;
        stream = new ZlinkStream(new ZlinkStream.Listener() {
            @Override public void picture(int w, int h, int left, int top, int right, int bottom) {
                main.post(() -> {
                    if (generation != attempt) return;
                    main.removeCallbacks(timeout);
                    attempts = 0;
                    width = w;
                    height = h;
                    // margins that would leave no picture are ignored
                    boolean sane = left + right < w / 2 && top + bottom < h / 2;
                    margins[0] = sane ? left : 0; margins[1] = sane ? top : 0;
                    margins[2] = sane ? right : 0; margins[3] = sane ? bottom : 0;
                    fit();
                    status.setVisibility(View.GONE);
                });
            }
            @Override public void failed() {
                main.post(() -> {
                    if (generation == attempt) retryOrFail("Projection indisponible.");
                });
            }
        });
        stream.start(screen.getHolder().getSurface());
        startAudio();
        main.postDelayed(timeout, 7000);
    }

    /** Audio is independent from the video: a missing audio port must not hide the picture. */
    private void startAudio() {
        if (focusRequest == null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setOnAudioFocusChangeListener(change -> {})
                .build();
            audioManager.requestAudioFocus(focusRequest);
        }
        if (audioMain == null) {
            musicVolume = 1f;
            audioMain = new AudioStream(AudioStream.MAIN_PORT, AudioAttributes.USAGE_MEDIA,
                () -> Log.i("C3Twin", "Audio principal indisponible"));
            audioMain.start();
        }
        if (audioSystem == null) {
            audioSystem = new AudioStream(AudioStream.SYSTEM_PORT,
                AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
                () -> Log.i("C3Twin", "Audio système indisponible"));
            audioSystem.start();
        }
    }

    /**
     * Scales the picture so that its useful part (the frame minus ZLink's margins) fills the
     * area, and pushes the margins out of view. The SurfaceView keeps the whole frame, so a
     * touch maps to frame coordinates exactly as before.
     */
    private void fit() {
        if (width == 0 || area.getWidth() == 0 || area.getHeight() == 0) return;
        int cw = width - margins[0] - margins[2], ch = height - margins[1] - margins[3];
        float scale = Math.min(area.getWidth() / (float) cw, area.getHeight() / (float) ch);
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) screen.getLayoutParams();
        int w = Math.round(width * scale), h = Math.round(height * scale);
        int left = Math.round((area.getWidth() - cw * scale) / 2 - margins[0] * scale);
        int top = Math.round((area.getHeight() - ch * scale) / 2 - margins[1] * scale);
        if (p.width != w || p.height != h || p.leftMargin != left || p.topMargin != top || p.gravity != (Gravity.TOP | Gravity.LEFT)) {
            p.width = w;
            p.height = h;
            p.gravity = Gravity.TOP | Gravity.LEFT;
            p.leftMargin = left;
            p.topMargin = top;
            screen.setLayoutParams(p);
            int[] at = new int[2];
            area.getLocationOnScreen(at);
            // where the frame lands on screen: lets a test (or a log reader) check the touch mapping
            Log.i("C3Twin", "Projection: image " + width + "x" + height + " marges " + margins[0] + "/" + margins[1]
                + "/" + margins[2] + "/" + margins[3] + " écran " + (at[0] + left) + "," + (at[1] + top) + " " + w + "x" + h);
        }
    }

    /** Head unit and steering wheel keys CarPlay understands. */
    private static boolean carplayKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_NEXT: case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE: case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_CALL: case KeyEvent.KEYCODE_ENDCALL:
            case KeyEvent.KEYCODE_VOICE_ASSIST: case KeyEvent.KEYCODE_SEARCH:
                return true;
            default:
                return false;
        }
    }

    /** True when the key went to CarPlay. */
    private boolean forwardKey(KeyEvent e) {
        if (!carplayKey(e.getKeyCode()) || stream == null || !stream.keysReady()) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            Log.i("C3Twin", "Projection: touche " + KeyEvent.keyCodeToString(e.getKeyCode()) + " vers CarPlay");
            stream.sendKey(e.getKeyCode());
        }
        return true;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        return forwardKey(e) || super.dispatchKeyEvent(e);
    }

    /**
     * Media keys the firmware routes to the active media session rather than to the screen
     * in front: while CarPlay is shown, that session is ours.
     */
    private void claimMediaKeys() {
        if (keySession == null) {
            keySession = new MediaSession(this, "C3Twin-CarPlay");
            keySession.setCallback(new MediaSession.Callback() {
                @Override public boolean onMediaButtonEvent(android.content.Intent i) {
                    KeyEvent e = i.getParcelableExtra(android.content.Intent.EXTRA_KEY_EVENT);
                    return e != null && forwardKey(e);
                }
            });
            keySession.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f).build());
        }
        keySession.setActive(true);
    }

    private boolean touch(View v, MotionEvent event) {
        if (stream == null || width == 0 || status.getVisibility() == View.VISIBLE) return false;
        String action;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: action = "DOWN"; break;
            case MotionEvent.ACTION_MOVE: action = "MOVE"; break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: action = "UP"; break;
            default: return true;
        }
        int x = Math.max(0, Math.min(width - 1, (int)(event.getX() * width / v.getWidth())));
        int y = Math.max(0, Math.min(height - 1, (int)(event.getY() * height / v.getHeight())));
        stream.sendTouch(action, x, y);
        return true;
    }

    /** Retries while the iPhone finishes connecting, then shows the manual fallback. */
    private void retryOrFail(String message) {
        stop();
        if (++attempts < MAX_ATTEMPTS) {
            status.setVisibility(View.VISIBLE);
            status.setText("En attente de l’iPhone… (" + attempts + "/" + MAX_ATTEMPTS + ")");
            retry.setVisibility(View.GONE);
            main.postDelayed(nextAttempt, 2500);
        } else {
            unavailable(message);
        }
    }

    private void unavailable(String message) {
        stop();
        attempts = 0;
        status.setText(message + "\nConnectez l’iPhone, puis réessayez.");
        status.setBackgroundColor(Color.BLACK);
        status.setVisibility(View.VISIBLE);
        retry.setVisibility(View.VISIBLE);
    }

    private void stop() {
        generation++;
        main.removeCallbacks(timeout);
        main.removeCallbacks(nextAttempt);
        if (stream != null) stream.close();
        stream = null;
        if (audioMain != null) { audioMain.close(); audioMain = null; }
        if (audioSystem != null) { audioSystem.close(); audioSystem = null; }
        if (focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        }
        width = height = 0;
    }

    private void openZlink() {
        Intent i = getPackageManager().getLaunchIntentForPackage(MainActivity.ZLINK);
        if (i != null) {
            try { startActivity(i); finish(); return; }
            catch (ActivityNotFoundException | SecurityException ignored) {}
        }
        Toast.makeText(this, "Zlink est indisponible sur cet appareil", Toast.LENGTH_SHORT).show();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        claimMediaKeys();
        main.post(duck);
        if (!folded) twin.onResume();
        CarData car = CarData.get(this);
        car.addListener(this);
        pushCar(car.snapshot());
        start();
    }
    @Override protected void onPause() {
        resumed = false;
        main.removeCallbacks(duck);
        if (keySession != null) keySession.setActive(false);
        stop();
        attempts = 0;
        CarData.get(this).removeListener(this);
        twin.onPause();
        super.onPause();
    }
    @Override protected void onDestroy() {
        if (keySession != null) keySession.release();
        ((LinearLayout) twin.getParent()).removeView(twin);
        twin.removeJavascriptInterface("Android");
        twin.destroy();
        twin = null;
        super.onDestroy();
    }
    @Override public void surfaceCreated(SurfaceHolder h) { available = true; start(); }
    @Override public void surfaceDestroyed(SurfaceHolder h) { available = false; stop(); }
    @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int height) {}
}
