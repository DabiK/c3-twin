package fr.dabi.c3twin;

import android.content.Context;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Serves assets/web/ on a made-up https origin, so that fetch(), workers and modules
 * behave as on the web; everything else is refused (the page works offline).
 */
final class AssetClient extends WebViewClient {
    static final String ORIGIN = "https://c3twin.local/";

    private final Context context;
    private final Map<String, String> types = new HashMap<>();

    AssetClient(Context context) {
        this.context = context.getApplicationContext();
        types.put("html", "text/html");
        types.put("js", "application/javascript");
        types.put("glb", "model/gltf-binary");
        types.put("json", "application/json");
        types.put("txt", "text/plain");
        types.put("png", "image/png");
        types.put("wasm", "application/wasm");
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
        String url = req.getUrl().toString();
        if (!url.startsWith(ORIGIN)) return notFound();
        String path = req.getUrl().getPath();
        if (path == null || path.equals("/")) path = "/index.html";
        String ext = path.substring(path.lastIndexOf('.') + 1);
        try {
            InputStream in = context.getAssets().open("web" + path);
            String type = types.containsKey(ext) ? types.get(ext) : "application/octet-stream";
            String charset = type.startsWith("text") || type.endsWith("javascript") ? "utf-8" : null;
            return new WebResourceResponse(type, charset, in);
        } catch (IOException e) {
            return notFound();
        }
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
        return true;    // never leave the page
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, new ByteArrayInputStream(new byte[0]));
    }
}
