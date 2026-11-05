package com.weather.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Runs about every 15 minutes in the background (Android decides the exact timing).
 * Sends a notification when rain is about to start at the watched place, and for
 * severe or extreme NWS warnings there.
 */
public class WeatherJob extends JobService {
  static final int JOB_ID = 1;
  static final String PREFS = "wx";

  static void schedule(Context c) {
    JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    if (js == null || js.getPendingJob(JOB_ID) != null) return;
    JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, WeatherJob.class))
        .setPeriodic(15 * 60 * 1000L)
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        .setPersisted(true)
        .build();
    js.schedule(job);
  }

  static void cancel(Context c) {
    JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    if (js != null) js.cancel(JOB_ID);
  }

  @Override
  public boolean onStartJob(final JobParameters params) {
    new Thread(() -> {
      try { check(getApplicationContext()); } catch (Exception ignored) {}
      jobFinished(params, false);
    }).start();
    return true;
  }

  @Override
  public boolean onStopJob(JobParameters params) { return true; }

  static void check(Context c) throws Exception {
    SharedPreferences sp = c.getSharedPreferences(PREFS, 0);
    String w = sp.getString("watch", null);
    if (WidgetData.anyWidgets(c)) { try { WidgetData.refresh(c, 25 * 60 * 1000L, true); } catch (Throwable ignored) {} }
    if (w == null) return;
    JSONObject watch = new JSONObject(w);
    double lat = watch.getDouble("lat"), lon = watch.getDouble("lon");
    String name = watch.optString("name", "your location");
    if (watch.optBoolean("rain", true)) {
      try { checkRain(c, sp, lat, lon, name, "mm".equals(watch.optString("unit"))); } catch (Exception ignored) {}
    }
    if (watch.optBoolean("alerts", true)) {
      try { checkAlerts(c, sp, lat, lon, name); } catch (Exception ignored) {}
    }
  }

  /** Rain starting within the next hour after a dry spell (at most once every 3 hours). */
  static void checkRain(Context c, SharedPreferences sp, double lat, double lon, String name, boolean metric) throws Exception {
    JSONObject j = new JSONObject(get(String.format(Locale.US,
        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&minutely_15=precipitation&past_minutely_15=2&forecast_minutely_15=8&timezone=auto",
        lat, lon)));
    JSONObject m = j.getJSONObject("minutely_15");
    JSONArray t = m.getJSONArray("time"), p = m.getJSONArray("precipitation");
    if (p.length() < 6) return;
    // first two slots are the past 30 minutes
    boolean dryNow = p.optDouble(0, 0) < 0.05 && p.optDouble(1, 0) < 0.05 && p.optDouble(2, 0) < 0.1;
    int start = -1;
    double peak = 0;
    for (int k = 2; k < Math.min(p.length(), 7); k++) {
      double v = p.optDouble(k, 0);
      if (start < 0 && v >= 0.2) start = k;
      if (start >= 0) peak = Math.max(peak, v);
    }
    long now = System.currentTimeMillis();
    if (!dryNow) { sp.edit().putLong("rainWet", now).apply(); return; }
    if (start < 0) return;
    long last = sp.getLong("rainNotified", 0), wet = sp.getLong("rainWet", 0);
    if (now - last < 3 * 3600_000L || now - wet < 2 * 3600_000L) return;
    double perHour = peak * 4; // mm per 15 min -> mm/h
    String kind = perHour < 2.5 ? "Light rain" : perHour < 7.6 ? "Rain" : "Heavy rain";
    String when = clock(t.optString(start, ""));
    int mins = (start - 2) * 15;
    String soon = mins <= 0 ? "starting now" : "starting around " + when;
    notify(c, "rain", 100, kind + " " + soon, name + " · " + (mins <= 0 ? "now" : "in about " + mins + " min"), false);
    sp.edit().putLong("rainNotified", now).apply();
  }

  /** NWS alerts at the point: Severe/Extreme severity, or anything called a Warning. US only. */
  static void checkAlerts(Context c, SharedPreferences sp, double lat, double lon, String name) throws Exception {
    if (lat < 17 || lat > 72 || lon > -60 || lon < -180) return;
    JSONObject j = new JSONObject(get(String.format(Locale.US, "https://api.weather.gov/alerts/active?point=%.4f,%.4f", lat, lon)));
    JSONArray f = j.optJSONArray("features");
    if (f == null) return;
    Set<String> seen = new HashSet<>(sp.getStringSet("seenAlerts", new HashSet<>()));
    Set<String> current = new HashSet<>();
    for (int i = 0; i < f.length(); i++) {
      JSONObject p = f.getJSONObject(i).optJSONObject("properties");
      if (p == null || !"Actual".equals(p.optString("status"))) continue;
      String id = p.optString("id"), event = p.optString("event"), sev = p.optString("severity");
      current.add(id);
      boolean important = "Extreme".equals(sev) || "Severe".equals(sev) || event.endsWith("Warning");
      if (!important || seen.contains(id)) continue;
      String head = p.optString("headline", "");
      notify(c, "alerts", 200 + (id.hashCode() & 0xffff), event + " · " + name,
          head.isEmpty() ? p.optString("description", "") : head, true);
    }
    sp.edit().putStringSet("seenAlerts", current).apply();
  }

  static void notify(Context c, String channel, int id, String title, String text, boolean urgent) {
    NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    if (nm == null) return;
    if (Build.VERSION.SDK_INT >= 26) {
      NotificationChannel ch = urgent
          ? new NotificationChannel("alerts", "Severe weather warnings", NotificationManager.IMPORTANCE_HIGH)
          : new NotificationChannel("rain", "Rain starting soon", NotificationManager.IMPORTANCE_DEFAULT);
      nm.createNotificationChannel(ch);
    }
    Intent open = new Intent(c, MainActivity.class);
    open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    PendingIntent pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, channel) : new Notification.Builder(c);
    b.setSmallIcon(R.drawable.ic_stat)
        .setColor(urgent ? Color.parseColor("#ff5468") : Color.parseColor("#7cc4ff"))
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(new Notification.BigTextStyle().bigText(text))
        .setContentIntent(pi)
        .setAutoCancel(true);
    nm.notify(id, b.build());
  }

  /** "2026-11-03T15:15" -> "3:15 PM" */
  static String clock(String iso) {
    try {
      int h = Integer.parseInt(iso.substring(11, 13)), m = Integer.parseInt(iso.substring(14, 16));
      return (h % 12 == 0 ? 12 : h % 12) + ":" + (m < 10 ? "0" : "") + m + (h < 12 ? " AM" : " PM");
    } catch (Exception e) { return "soon"; }
  }

  static String get(String url) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    try {
      c.setConnectTimeout(12000);
      c.setReadTimeout(20000);
      c.setRequestProperty("User-Agent", "WeatherApp/1.0 (Android; personal weather app)");
      c.setRequestProperty("Accept", "application/json, application/geo+json");
      InputStream in = c.getInputStream();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[16384];
      int n;
      while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
      in.close();
      return out.toString("UTF-8");
    } finally {
      c.disconnect();
    }
  }
}
