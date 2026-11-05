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
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
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
    Crash.install(this);
    Crash.showLast(this);
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
    s.setAllowFileAccess(false);           // no access to files on the phone
    s.setAllowContentAccess(false);        // no access to other apps' content
    s.setSaveFormData(false);
    s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    String ver = "1.0";
    try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
    s.setUserAgentString(s.getUserAgentString() + " WeatherApp/" + ver);
    System.setProperty("wx.ver", ver);

    web.setWebChromeClient(new WebChromeClient() {
      @Override
      public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
        if (!isOurOrigin(origin)) { cb.invoke(origin, false, false); return; }
        if (hasLocation()) { cb.invoke(origin, true, false); return; }
        geoOrigin = origin; geoCallback = cb;
        if (Build.VERSION.SDK_INT >= 23) {
          requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
              Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
        } else cb.invoke(origin, true, false);
      }
    });
    web.setWebViewClient(new WebViewClient() {
      // if Android stops the page's process (low memory), start the page again instead of closing the app
      @Override
      public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
        try {
          android.view.ViewGroup parent = (android.view.ViewGroup) view.getParent();
          if (parent != null) parent.removeView(view);
          view.destroy();
        } catch (Throwable ignored) {}
        if (view == web) { web = null; recreate(); }
        return true;
      }
      @Override
      public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
        return intercept(req.getUrl());
      }
      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
        Uri u = req.getUrl();
        if (HOST.equals(u.getHost())) return false;
        // ordinary https links (credits, app download) open in the phone's browser; anything else is ignored
        if ("https".equals(u.getScheme())) {
          try {
            Intent i = new Intent(Intent.ACTION_VIEW, u);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(i);
          } catch (Exception ignored) {}
        }
        return true;
      }
    });
    web.addJavascriptInterface(new Bridge(), "WxNative");
    setContentView(web);
    web.loadUrl("https://" + HOST + "/");
  }

  /** Lets the page tell the app which place to watch and which notifications are on. */
  public class Bridge {
    @JavascriptInterface
    public void setWatch(String json) {
      if (json == null || json.length() > 2000) return;
      try {
        org.json.JSONObject o = new org.json.JSONObject(json);
        double lat = o.getDouble("lat"), lon = o.getDouble("lon");
        if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return;
        SharedPreferences sp = getSharedPreferences(WeatherJob.PREFS, 0);
        sp.edit().putString("watch", json).apply();
        final android.content.Context app = getApplicationContext();
        boolean widgets = WidgetData.anyWidgets(app);
        if (widgets || o.optBoolean("rain", true) || o.optBoolean("alerts", true)) WeatherJob.schedule(MainActivity.this);
        else WeatherJob.cancel(MainActivity.this);
        if (widgets) WidgetData.refreshAsync(app, 5 * 60 * 1000L);
      } catch (Exception ignored) {}
    }
    @JavascriptInterface
    public void requestNotifications() {
      if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
        runOnUiThread(() -> requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 8));
      }
    }
    @JavascriptInterface
    public boolean notificationsAllowed() {
      NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
      return nm == null || nm.areNotificationsEnabled();
    }
    @JavascriptInterface
    public void testNotification() {
      WeatherJob.notify(MainActivity.this, "rain", 99, "Notifications are working", "You'll get alerts for rain starting soon and severe weather here.", false);
    }
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

  static boolean isOurOrigin(String origin) {
    try { return HOST.equals(new URL(origin).getHost()) && "https".equals(new URL(origin).getProtocol()); }
    catch (Exception e) { return false; }
  }

  /** Only https, default port, no user-info tricks, and only the listed weather services. */
  static URL allowedUrl(String url) {
    try {
      URL u = new URL(url);
      String host = u.getHost() == null ? "" : u.getHost().toLowerCase(java.util.Locale.US);
      if (!"https".equals(u.getProtocol()) || u.getUserInfo() != null || (u.getPort() != -1 && u.getPort() != 443)) return null;
      if (url.indexOf('\\') >= 0 || host.isEmpty()) return null;
      for (String a : ALLOWED) if (host.equals(a) || host.endsWith("." + a)) return u;
    } catch (Exception ignored) {}
    return null;
  }

  /** Fetches data and map overlay tiles natively, so the page isn't blocked by cross-site rules. */
  private WebResourceResponse proxy(String target) {
    URL url = target == null || target.length() > 4000 ? null : allowedUrl(target);
    if (url == null) return error(403, "Blocked");
    HttpURLConnection c = null;
    try {
      for (int hop = 0; ; hop++) {
        c = (HttpURLConnection) url.openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(12000);
        c.setReadTimeout(25000);
        c.setRequestProperty("User-Agent", "WeatherApp/1.0 (Android; personal weather app)");
        int code = c.getResponseCode();
        if (code >= 300 && code < 400 && hop < 3) {
          String loc = c.getHeaderField("Location");
          c.disconnect();
          url = loc == null ? null : allowedUrl(new URL(url, loc).toString());
          if (url == null) return error(403, "Blocked redirect");
          continue;
        }
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        byte[] body = in == null ? new byte[0] : readAll(in);
        String type = c.getContentType() == null ? "application/octet-stream" : c.getContentType();
        String mime = type.split(";")[0].trim().toLowerCase(java.util.Locale.US);
        if (mime.contains("html") || mime.contains("javascript")) mime = "text/plain"; // data only, never runnable pages or scripts
        String enc = mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") ? "utf-8" : null;
        WebResourceResponse r = new WebResourceResponse(mime, enc, new ByteArrayInputStream(body));
        r.setStatusCodeAndReasonPhrase(code, code >= 400 ? "Error" : "OK");
        r.setResponseHeaders(headers());
        return r;
      }
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
    h.put("Access-Control-Allow-Origin", "https://" + HOST);
    h.put("X-Content-Type-Options", "nosniff");
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
    if (web == null) { super.onBackPressed(); return; }
    web.evaluateJavascript("window.wxBack&&wxBack()", v -> {
      if (!"true".equals(v)) MainActivity.super.onBackPressed();
    });
  }

  @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
  @Override protected void onPause() { if (web != null) web.onPause(); super.onPause(); }
  @Override protected void onDestroy() { if (web != null) web.destroy(); super.onDestroy(); }
}
