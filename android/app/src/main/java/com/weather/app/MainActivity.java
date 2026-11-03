package com.weather.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * The weather app: one full-screen web view showing web/index.html.
 * The page is served from a private https address so the phone's location works,
 * and data requests are fetched natively (no browser cross-site limits).
 */
public class MainActivity extends Activity {
  static final String HOST = "weather.app.local";
  private static final int REQ_LOC = 7;
  private static final String[] ALLOWED = {
      "open-meteo.com", "api.tidesandcurrents.noaa.gov", "api.bigdatacloud.net", "api.rainviewer.com"
  };

  private WebView web;
  private String geoOrigin;
  private GeolocationPermissions.Callback geoCallback;

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    Window w = getWindow();
    if (Build.VERSION.SDK_INT >= 21) {
      w.setStatusBarColor(Color.parseColor("#0b1424"));
      w.setNavigationBarColor(Color.parseColor("#08121f"));
    }
    web = new WebView(this);
    web.setBackgroundColor(Color.parseColor("#08121f"));
    web.setOverScrollMode(View.OVER_SCROLL_NEVER);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setGeolocationEnabled(true);
    s.setMediaPlaybackRequiresUserGesture(true);
    String ver = "1.0";
    try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
    s.setUserAgentString(s.getUserAgentString() + " WeatherApp/" + ver);

    web.setWebChromeClient(new WebChromeClient() {
      @Override
      public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
        if (hasLocation()) { cb.invoke(origin, true, false); return; }
        geoOrigin = origin; geoCallback = cb;
        if (Build.VERSION.SDK_INT >= 23) {
          requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
              Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
        } else cb.invoke(origin, true, false);
      }
    });
    web.setWebViewClient(new WebViewClient() {
      @Override
      public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
        return intercept(req.getUrl());
      }
      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
        Uri u = req.getUrl();
        if (HOST.equals(u.getHost())) return false;
        // links (credits, app update) open in the phone's browser
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
        return true;
      }
    });
    setContentView(web);
    web.loadUrl("https://" + HOST + "/index.html");
  }

  private boolean hasLocation() {
    if (Build.VERSION.SDK_INT < 23) return true;
    return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
    if (code == REQ_LOC && geoCallback != null) {
      geoCallback.invoke(geoOrigin, hasLocation(), false);
      geoCallback = null;
    }
  }

  /** Serves the app's own files, and relays data requests to the allowed weather services. */
  private WebResourceResponse intercept(Uri u) {
    if (!HOST.equals(u.getHost())) return null; // map tiles, fonts, scripts load normally
    String path = u.getPath() == null ? "/" : u.getPath();
    try {
      if (path.equals("/proxy")) return proxy(u.getQueryParameter("u"));
      if (path.equals("/") ) path = "/index.html";
      InputStream in = getAssets().open("web" + path);
      String type = path.endsWith(".html") ? "text/html" : path.endsWith(".js") ? "application/javascript"
          : path.endsWith(".css") ? "text/css" : path.endsWith(".png") ? "image/png" : "application/octet-stream";
      return new WebResourceResponse(type, "utf-8", in);
    } catch (Exception e) {
      return error(404, "Not found");
    }
  }

  static boolean allowed(String url) {
    try {
      Uri u = Uri.parse(url);
      if (!"https".equals(u.getScheme()) || u.getHost() == null) return false;
      for (String a : ALLOWED) if (u.getHost().equals(a) || u.getHost().endsWith("." + a)) return true;
    } catch (Exception ignored) {}
    return false;
  }

  private WebResourceResponse proxy(String target) {
    if (target == null || !allowed(target)) return error(403, "Blocked");
    HttpURLConnection c = null;
    try {
      c = (HttpURLConnection) new URL(target).openConnection();
      c.setConnectTimeout(12000);
      c.setReadTimeout(25000);
      c.setRequestProperty("User-Agent", "WeatherApp (Android)");
      c.setRequestProperty("Accept", "application/json");
      int code = c.getResponseCode();
      InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      if (in != null) { byte[] b = new byte[16384]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); in.close(); }
      WebResourceResponse r = new WebResourceResponse("application/json", "utf-8",
          new ByteArrayInputStream(out.toByteArray()));
      r.setStatusCodeAndReasonPhrase(code, code >= 400 ? "Error" : "OK");
      r.setResponseHeaders(headers());
      return r;
    } catch (Exception e) {
      return error(502, "Network error");
    } finally {
      if (c != null) c.disconnect();
    }
  }

  private static Map<String, String> headers() {
    Map<String, String> h = new HashMap<>();
    h.put("Cache-Control", "no-store");
    h.put("Access-Control-Allow-Origin", "*");
    return h;
  }

  private static WebResourceResponse error(int code, String msg) {
    WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8",
        new ByteArrayInputStream(msg.getBytes()));
    r.setStatusCodeAndReasonPhrase(code, msg);
    r.setResponseHeaders(headers());
    return r;
  }

  @Override
  public void onBackPressed() {
    web.evaluateJavascript("window.wxBack&&wxBack()", v -> {
      if (!"true".equals(v)) MainActivity.super.onBackPressed();
    });
  }

  @Override protected void onResume() { super.onResume(); web.onResume(); }
  @Override protected void onPause() { web.onPause(); super.onPause(); }
  @Override protected void onDestroy() { web.destroy(); super.onDestroy(); }
}
