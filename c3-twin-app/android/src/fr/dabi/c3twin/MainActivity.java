package fr.dabi.c3twin;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.List;

/**
 * The head unit's home screen: the dashboard page in a WebView, fed with the
 * car's state by {@link CarData}. The page is served from the APK's assets on
 * a made-up https origin so that fetch(), workers and modules behave as on the web.
 */
public class MainActivity extends Activity implements CarData.Listener {
    private static final String TAG = "C3Twin";
    static final String ZLINK = "com.zjinnova.zlink";
    static boolean dashboardVisible;
    private boolean pendingCarplay;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private CarData car;
    private boolean pageReady;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        WebView.setWebContentsDebuggingEnabled(
            (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        web = new WebView(this);
        web.setBackgroundColor(0xFF07090B);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new AssetClient(this));
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i(TAG, "page: " + m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl(AssetClient.ORIGIN + "index.html");

        car = CarData.get(this);
        car.addListener(this);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) car.startGps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        dashboardVisible = true;
        stopService(new Intent(this, ReturnOverlayService.class));
        hideSystemBars();
        web.onResume();
        if (pageReady) push(car.snapshot());
        if (pendingCarplay) {
            pendingCarplay = false;
            main.post(() -> launchZlink());
        }
    }

    @Override
    protected void onPause() {
        dashboardVisible = false;
        web.onPause();      // stops the 3D loop while CarPlay or another app is in front
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        car.removeListener(this);
        web.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // the home screen has nowhere to go back to
        web.evaluateJavascript("document.getElementById('drawer').classList.remove('open')", null);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    /** CarData calls this from any thread with the part of the state that changed. */
    @Override
    public void onCarChanged(JSONObject patch) {
        main.post(() -> push(patch));
    }

    private void push(JSONObject patch) {
        if (!pageReady || web == null) return;
        web.evaluateJavascript("window.twin && twin.set(" + patch + ")", null);
    }

    private void open(String pkg) {
        if (ZLINK.equals(pkg)) { openCarplay(); return; }
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            main.post(() -> Toast.makeText(this, pkg.equals(ZLINK) ? "ZLink n'est pas installé" : "Application introuvable", Toast.LENGTH_SHORT).show());
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(i);
    }

    private void openCarplay() {
        if (Settings.canDrawOverlays(this)
                || getPreferences(MODE_PRIVATE).getBoolean("overlayExplained", false)) {
            launchZlink();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Revenir à C3 Twin")
            .setMessage("Un petit bouton déplaçable permet de revenir à la voiture depuis Zlink.")
            .setPositiveButton("Activer le bouton", (dialog, which) -> {
                getPreferences(MODE_PRIVATE).edit().putBoolean("overlayExplained", true).apply();
                pendingCarplay = true;
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                } catch (ActivityNotFoundException | SecurityException e) {
                    pendingCarplay = false;
                    launchZlink();
                }
            })
            .setNegativeButton("Sans le bouton", (dialog, which) -> {
                getPreferences(MODE_PRIVATE).edit().putBoolean("overlayExplained", true).apply();
                launchZlink();
            }).show();
    }

    private void launchZlink() {
        Intent target = getPackageManager().getLaunchIntentForPackage(ZLINK);
        if (target == null) {
            Toast.makeText(this, "Zlink n’est pas installé", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if (Settings.canDrawOverlays(this)) {
                startForegroundService(new Intent(this, ReturnOverlayService.class));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Bouton retour indisponible", e);
        }
        try { startActivity(target); }
        catch (ActivityNotFoundException | SecurityException e) {
            stopService(new Intent(this, ReturnOverlayService.class));
            Toast.makeText(this, "Zlink est indisponible", Toast.LENGTH_SHORT).show();
        }
    }

    /** What the page may ask of the app (window.Android). */
    private class Bridge {
        @JavascriptInterface
        public void ready() {
            pageReady = true;
            main.post(() -> push(car.snapshot()));
        }

        @JavascriptInterface
        public void openCarplay() { main.post(() -> open(ZLINK)); }

        @JavascriptInterface
        public void launch(String pkg) { main.post(() -> open(pkg)); }

        @JavascriptInterface
        public String apps() { return listApps(); }
    }

    private String cachedApps;

    private String listApps() {
        if (cachedApps != null) return cachedApps;
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(q, 0);
        Collections.sort(found, (a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        JSONArray out = new JSONArray();
        for (ResolveInfo r : found) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            try {
                JSONObject a = new JSONObject();
                a.put("pkg", pkg);
                a.put("label", r.loadLabel(pm).toString());
                a.put("icon", iconDataUrl(r.loadIcon(pm)));
                out.put(a);
            } catch (Exception e) {
                Log.w(TAG, "icône ignorée pour " + pkg, e);
            }
        }
        cachedApps = out.toString();
        return cachedApps;
    }

    private static String iconDataUrl(Drawable d) {
        Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, 96, 96);
        d.draw(c);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, png);
        return "data:image/png;base64," + Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP);
    }
}
