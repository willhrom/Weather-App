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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * The weather app: a full-screen web view showing the live app page from Cloudflare Pages,
 * so changes pushed to GitHub reach the phone without reinstalling.
 * The last page that loaded is saved on the phone (and a copy ships inside the app),
 * so it still opens with no signal. Data requests are fetched natively.
 */
public class MainActivity extends Activity {
  static final String HOST = "willhrom-weather.pages.dev";
  private static final int REQ_LOC = 7;
  private static final String[] ALLOWED = {
      "open-meteo.com", "api.tidesandcurrents.noaa.gov", "api.bigdatacloud.net", "rainviewer.com",
      "aviationweather.gov", "api.weather.gov", "mesonet.agron.iastate.edu"
  };

  private WebView web;
  private String geoOrigin;
  private GeolocationPermissions.Callback geoCallback;

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    Window w = getWindow();
    if (Build.VERSION.SDK_INT >= 21) {
      w.setStatusBarColor(Color.parseColor("#07090c"));
      w.setNavigationBarColor(Color.parseColor("#07090c"));
    }
    web = new WebView(this);
    web.setBackgroundColor(Color.parseColor("#07090c"));
    web.setOverScrollMode(View.OVER_SCROLL_NEVER);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setGeolocationEnabled(true);
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
        // links (credits, app reinstall) open in the phone's browser
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
        return true;
      }
    });
    setContentView(web);
    web.loadUrl("https://" + HOST + "/");
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

  private WebResourceResponse intercept(Uri u) {
    if (!HOST.equals(u.getHost())) return null; // map tiles, fonts and scripts load normally
    String path = u.getPath() == null ? "/" : u.getPath();
    if (path.equals("/proxy")) return proxy(u.getQueryParameter("u"));
    if (path.equals("/") || path.equals("/index.html")) return page();
    return null;
  }

  /** The newest page from Cloudflare; if offline, the last saved copy; failing that, the built-in copy. */
  private WebResourceResponse page() {
    File cache = new File(getFilesDir(), "index.html");
    byte[] body = null;
    HttpURLConnection c = null;
    try {
      c = (HttpURLConnection) new URL("https://" + HOST + "/index.html?t=" + System.currentTimeMillis()).openConnection();
      c.setConnectTimeout(5000);
      c.setReadTimeout(8000);
      c.setRequestProperty("Cache-Control", "no-cache");
      if (c.getResponseCode() == 200) {
        body = readAll(c.getInputStream());
        if (body.length > 1000) {
          FileOutputStream out = new FileOutputStream(cache);
          out.write(body);
          out.close();
        } else body = null;
      }
    } catch (Exception ignored) {
    } finally {
      if (c != null) c.disconnect();
    }
    try {
      InputStream in = body != null ? new ByteArrayInputStream(body)
          : cache.exists() ? new FileInputStream(cache)
          : getAssets().open("web/index.html");
      WebResourceResponse r = new WebResourceResponse("text/html", "utf-8", in);
      r.setResponseHeaders(headers());
      return r;
    } catch (Exception e) {
      return error(500, "App page unavailable");
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

  /** Fetches data and map overlay tiles natively, so the page isn't blocked by cross-site rules. */
  private WebResourceResponse proxy(String target) {
    if (target == null || !allowed(target)) return error(403, "Blocked");
    HttpURLConnection c = null;
    try {
      c = (HttpURLConnection) new URL(target).openConnection();
      c.setConnectTimeout(12000);
      c.setReadTimeout(25000);
      c.setRequestProperty("User-Agent", "WeatherApp/1.0 (Android; personal weather app)");
      int code = c.getResponseCode();
      InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
      byte[] body = in == null ? new byte[0] : readAll(in);
      String type = c.getContentType() == null ? "application/octet-stream" : c.getContentType();
      String mime = type.split(";")[0].trim();
      String enc = mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") ? "utf-8" : null;
      WebResourceResponse r = new WebResourceResponse(mime, enc, new ByteArrayInputStream(body));
      r.setStatusCodeAndReasonPhrase(code, code >= 400 ? "Error" : "OK");
      r.setResponseHeaders(headers());
      return r;
    } catch (Exception e) {
      return error(502, "Network error");
    } finally {
      if (c != null) c.disconnect();
    }
  }

  private static byte[] readAll(InputStream in) throws java.io.IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] b = new byte[16384];
    int n;
    while ((n = in.read(b)) > 0) out.write(b, 0, n);
    in.close();
    return out.toByteArray();
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
