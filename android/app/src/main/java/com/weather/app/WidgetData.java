package com.weather.app;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Fetches what the home-screen widgets show, for the place the app last had open
 * (the "watch" the page saves through the WxNative bridge), and caches it.
 * Everything here runs on a background thread.
 */
final class WidgetData {
  static final String KEY = "widgetData";
  private WidgetData() {}

  /** True when at least one of our widgets is on the home screen. */
  static boolean anyWidgets(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    for (Class<?> k : Widgets.ALL) if (m.getAppWidgetIds(new ComponentName(c, k)).length > 0) return true;
    return false;
  }

  static JSONObject cached(Context c) {
    try {
      String s = c.getSharedPreferences(WeatherJob.PREFS, 0).getString(KEY, null);
      return s == null ? null : new JSONObject(s);
    } catch (Exception e) { return null; }
  }

  /** Refreshes the cache if it's older than maxAgeMs (or the place changed), then redraws every widget. */
  private static final java.util.concurrent.atomic.AtomicBoolean BUSY = new java.util.concurrent.atomic.AtomicBoolean();

  /** Refresh on a background thread; does nothing if one is already running. */
  static void refreshAsync(Context c, long maxAgeMs) {
    final Context app = c.getApplicationContext();
    if (!BUSY.compareAndSet(false, true)) return;
    Widgets.BG.execute(() -> {
      try { refresh(app, maxAgeMs, false); } catch (Throwable ignored) {}
      finally { BUSY.set(false); }
    });
  }

  /** Fetches again if the cache is older than maxAgeMs or the place changed; redraws when it fetched (or when redraw is true). */
  static synchronized void refresh(Context c, long maxAgeMs, boolean redraw) {
    SharedPreferences sp = c.getSharedPreferences(WeatherJob.PREFS, 0);
    String w = sp.getString("watch", null);
    JSONObject old = cached(c);
    boolean changed = false;
    try {
      if (w != null) {
        JSONObject watch = new JSONObject(w);
        String key = String.format(Locale.US, "%.3f,%.3f,%s,%s|%s|%s|%s", watch.getDouble("lat"), watch.getDouble("lon"), watch.optString("temp", "F"), watch.optString("unit", "in"),
            id(watch, "apt"), id(watch, "tide"), id(watch, "water"));
        boolean fresh = old != null && !old.optBoolean("partial") && key.equals(old.optString("key")) && System.currentTimeMillis() - old.optLong("t") < maxAgeMs;
        if (!fresh) {
          final SharedPreferences fsp = sp;
          JSONObject d = fetch(watch, part -> {
            try { part.put("key", key); part.put("partial", true); fsp.edit().putString(KEY, part.toString()).commit(); Widgets.updateAll(c); } catch (Throwable ignored) {}
          });
          d.put("key", key);
          sp.edit().putString(KEY, d.toString()).commit();
          changed = true;
        }
      }
    } catch (Throwable ignored) {}
    if (changed || redraw) Widgets.updateAll(c);
  }

  private static String id(JSONObject o, String k) {
    JSONObject x = o.optJSONObject(k);
    return x == null ? "" : x.optString("id");
  }

  // ---------------------------------------------------------------------------------------------
  private static void putWhenDone(JSONObject out, String k, java.util.concurrent.Future<Object> f) {
    if (f == null) return;
    try { Object v = f.get(25, java.util.concurrent.TimeUnit.SECONDS); if (v != null) out.put(k, v); } catch (Throwable ignored) {}
  }

  interface Early { void accept(JSONObject partial); }

  static JSONObject fetch(JSONObject watch) throws Exception { return fetch(watch, null); }

  static JSONObject fetch(JSONObject watch, Early early) throws Exception {
    double lat = watch.getDouble("lat"), lon = watch.getDouble("lon");
    boolean F = !"C".equals(watch.optString("temp", "F")), english = !"mm".equals(watch.optString("unit", "in"));
    JSONObject out = new JSONObject();
    out.put("t", System.currentTimeMillis());
    out.put("place", watch.optString("name", "My location"));
    out.put("F", F);
    out.put("english", english);

    // forecast: now, next hours, next days
    JSONObject j = new JSONObject(WeatherJob.get(String.format(Locale.US,
        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,is_day,apparent_temperature,precipitation"
            + "&hourly=temperature_2m,weather_code,precipitation_probability,precipitation,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
            + "&temperature_unit=%s&precipitation_unit=%s&forecast_hours=14&forecast_days=7&timezone=auto",
        lat, lon, F ? "fahrenheit" : "celsius", english ? "inch" : "mm")));
    JSONObject cur = j.getJSONObject("current"), h = j.getJSONObject("hourly"), dd = j.getJSONObject("daily");
    JSONObject now = new JSONObject();
    now.put("temp", cur.getDouble("temperature_2m"));
    int code0 = cur.getInt("weather_code");
    // the model can miss rain that is falling now: if it is measuring rain this quarter-hour, say so
    double pNow = cur.optDouble("precipitation", 0);
    if (code0 < 51 && pNow >= (english ? 0.004 : 0.1)) code0 = pNow >= (english ? 0.04 : 1.0) ? 63 : 61;
    now.put("code", code0);
    now.put("day", cur.optInt("is_day", 1) == 1);
    now.put("feels", cur.optDouble("apparent_temperature"));
    now.put("time", cur.optString("time"));
    out.put("now", now);
    out.put("off", j.optInt("utc_offset_seconds", 0));

    JSONArray ht = h.getJSONArray("time"), hours = new JSONArray();
    String nowHour = cur.optString("time", "").length() >= 13 ? cur.optString("time").substring(0, 13) : "";
    int first = 0;
    for (int i = 0; i < ht.length(); i++) if (ht.getString(i).substring(0, 13).compareTo(nowHour) >= 0) { first = i; break; }
    for (int i = first; i < ht.length() && hours.length() < 13; i++) {
      JSONObject o = new JSONObject();
      o.put("t", ht.getString(i));
      o.put("temp", h.getJSONArray("temperature_2m").optDouble(i));
      o.put("code", h.getJSONArray("weather_code").optInt(i));
      o.put("pp", h.getJSONArray("precipitation_probability").optInt(i));
      o.put("p", h.getJSONArray("precipitation").optDouble(i));
      o.put("day", h.getJSONArray("is_day").optInt(i) == 1);
      hours.put(o);
    }
    out.put("hours", hours);

    JSONArray dt = dd.getJSONArray("time"), days = new JSONArray();
    for (int i = 0; i < dt.length(); i++) {
      JSONObject o = new JSONObject();
      o.put("d", dt.getString(i));
      o.put("code", dd.getJSONArray("weather_code").optInt(i));
      o.put("hi", dd.getJSONArray("temperature_2m_max").optDouble(i));
      o.put("lo", dd.getJSONArray("temperature_2m_min").optDouble(i));
      o.put("pp", dd.getJSONArray("precipitation_probability_max").optInt(i));
      days.put(o);
    }
    out.put("days", days);
    out.put("rain", rainText(hours, english));

    // the forecast is enough to draw most widgets: show it now, then fetch the extras side by side
    if (early != null) { try { early.accept(new JSONObject(out.toString())); } catch (Throwable ignored) {} }
    final boolean fF = F, fEng = english;
    final String localNow = now.optString("time", "").replace("-", "");
    final JSONObject apt = watch.optJSONObject("apt"), st = watch.optJSONObject("tide"), ws = watch.optJSONObject("water"), wp = watch.optJSONObject("wave");
    final String wid = ws != null && ws.optString("id").matches("[A-Za-z0-9]{4,12}") ? ws.optString("id") : st != null ? st.optString("id") : "";
    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
    java.util.concurrent.Future<Object> fM = null, fT = null, fW = null, fV = null;
    if (apt != null && apt.optString("id").matches("[A-Z0-9]{3,5}")) fM = pool.submit(() -> metar(apt.getString("id"), apt.optString("name"), fF));
    else out.put("noApt", true);
    if (st != null && st.optString("id").matches("[A-Za-z0-9]{4,12}")) fT = pool.submit(() -> tide(st.getString("id"), st.optString("name"), fEng, localNow));
    if (wid.matches("[A-Za-z0-9]{4,12}")) fW = pool.submit(() -> waterTemp(wid, fF));
    if (wp != null) fV = pool.submit(() -> {
      JSONObject m = new JSONObject(WeatherJob.get(String.format(Locale.US,
          "https://marine-api.open-meteo.com/v1/marine?latitude=%.3f&longitude=%.3f&current=wave_height", wp.getDouble("lat"), wp.getDouble("lon"))));
      double wh = m.getJSONObject("current").optDouble("wave_height");
      return Double.isNaN(wh) ? null : (Object) (fEng ? wh * 3.2808 : wh);
    });
    pool.shutdown();
    putWhenDone(out, "metar", fM);
    // what the nearest airport is reporting right now beats the model (same rule as the app's Today tab)
    try {
      JSONObject m = out.optJSONObject("metar");
      if (m != null && System.currentTimeMillis() - m.optLong("obs", 0) < 90 * 60_000L) {
        int c = now.getInt("code"), wx = m.optInt("wxc", -1);
        double cov = m.optDouble("cov", -1);
        boolean clr = m.optBoolean("clr", false);
        if (wx >= 0) c = wx;
        else if (cov >= 0) c = (c >= 51 && !clr) ? c : (cov == 2.5 ? 3 : (int) cov);
        now.put("code", c);
      }
    } catch (Exception ignored) {}
    putWhenDone(out, "tide", fT);
    putWhenDone(out, "water", fW);
    putWhenDone(out, "waves", fV);
    pool.shutdownNow();
    return out;
  }

  /** "Rain from 1a", "Rain until 4a", or "" — plus the hour span and chance for the other widgets. */
  static JSONObject rainText(JSONArray hours, boolean english) throws Exception {
    int start = -1, end = -1, pmax = 0;
    double wet = english ? 0.01 : 0.25;
    for (int i = 0; i < Math.min(12, hours.length()); i++) {
      JSONObject o = hours.getJSONObject(i);
      boolean w = o.optInt("pp") >= 50 || o.optDouble("p", 0) >= wet;
      pmax = Math.max(pmax, o.optInt("pp"));
      if (w) { if (start < 0) start = i; end = i; } else if (start >= 0 && end < i - 1) break;
    }
    JSONObject r = new JSONObject();
    r.put("pmax", pmax);
    if (start < 0) { r.put("short", ""); r.put("span", ""); return r; }
    String a = hourLabel(hours.getJSONObject(start).getString("t")), b = hourLabel(hours.getJSONObject(Math.min(hours.length() - 1, end + 1)).getString("t"));
    int pp = 0;
    for (int i = start; i <= end; i++) pp = Math.max(pp, hours.getJSONObject(i).optInt("pp"));
    r.put("short", start == 0 ? "Rain until " + b : "Rain from " + a);
    r.put("span", start == 0 ? "rain until " + b : "rain likely " + a + "–" + b);
    r.put("at", start == 0 ? "Rain now · " + pp + "%" : "Rain at " + a + " · " + pp + "%");
    return r;
  }

  /** "2026-11-04T01:00" -> "1a" */
  static String hourLabel(String iso) {
    try {
      int hh = Integer.parseInt(iso.substring(11, 13));
      return (hh % 12 == 0 ? 12 : hh % 12) + (hh < 12 ? "a" : "p");
    } catch (Exception e) { return ""; }
  }

  static JSONObject metar(String id, String name, boolean F) throws Exception {
    JSONArray a = new JSONArray(WeatherJob.get("https://aviationweather.gov/api/data/metar?ids=" + id + "&format=json"));
    JSONObject m = a.getJSONObject(0), o = new JSONObject();
    o.put("id", id);
    o.put("name", name.isEmpty() ? m.optString("name", "").replaceAll(",.*$", "") : name);
    String cat = m.optString("fltCat", m.optString("fltcat", ""));
    o.put("cat", cat);
    Object wd = m.opt("wdir");
    int ws = m.optInt("wspd", 0), wg = m.optInt("wgst", 0);
    String dir = wd instanceof Number ? String.format(Locale.US, "%03d°", ((Number) wd).intValue()) : "VRB";
    o.put("wind", ws == 0 ? "Calm" : dir + " " + ws + (wg > 0 ? "G" + wg : "") + "kt");
    JSONArray cl = m.optJSONArray("clouds");
    String ceil = "None";
    if (cl != null) for (int i = 0; i < cl.length(); i++) {
      JSONObject c = cl.getJSONObject(i);
      String cov = c.optString("cover");
      if (cov.matches("BKN|OVC|OVX|VV") && !c.isNull("base")) { ceil = cov + " " + fmtK(c.getInt("base")); break; }
    }
    if ("None".equals(ceil) && cl != null && cl.length() > 0) {
      JSONObject c = cl.getJSONObject(0);
      String cov = c.optString("cover");
      ceil = cov.matches("FEW|SCT") && !c.isNull("base") ? cov + " " + fmtK(c.getInt("base")) : (cov.matches("CLR|SKC|CAVOK") ? "Clear" : "None");
    }
    o.put("ceil", ceil);
    o.put("vis", m.optString("visib", "").isEmpty() ? "--" : m.optString("visib") + " sm");
    if (!m.isNull("temp") && !m.isNull("dewp")) o.put("td", Math.round(m.getDouble("temp")) + "/" + Math.round(m.getDouble("dewp")) + "°C");
    o.put("obs", m.optLong("obsTime", 0) * 1000);
    // weather now, read from the report: precipitation code (or -1) and how much of the sky is covered
    String raw = " " + m.optString("rawOb", "").replaceAll("\\sRMK\\s.*$", "") + " ";
    int wx = -1;
    if (raw.matches("(?s).*\\s[-+]?(VC)?TS.*")) wx = 95;
    else if (raw.matches("(?s).*\\s[-+]?(SH)?SN.*")) wx = 73;
    else if (raw.matches("(?s).*\\s[-+]?(FZ)?DZ.*")) wx = 53;
    else if (raw.matches("(?s).*\\s[-+]?(SH|FZ)?RA.*")) wx = raw.matches("(?s).*\\s\\+(SH)?RA.*") ? 65 : raw.matches("(?s).*\\s-(SH)?RA.*") ? 61 : 63;
    else if (raw.matches("(?s).*\\sFG\\s.*")) wx = 45;
    o.put("wxc", wx);
    double cov = -1;
    if (cl != null) for (int i = 0; i < cl.length(); i++) {
      JSONObject c = cl.getJSONObject(i);
      String cv = c.optString("cover");
      int base = c.isNull("base") ? 0 : c.optInt("base");
      if (base > 12000) continue;
      double r = cv.matches("SKC|CLR|CAVOK") ? 0 : cv.equals("FEW") ? 1 : cv.equals("SCT") ? 2 : cv.equals("BKN") ? 2.5 : cv.matches("OVC|OVX|VV") ? 3 : -1;
      cov = Math.max(cov, r);
    }
    o.put("cov", cov);
    o.put("clr", raw.matches("(?s).*\\s(SKC|CLR)\\s.*"));
    return o;
  }

  static String fmtK(int ft) { return ft >= 1000 ? (ft % 1000 == 0 ? (ft / 1000) + "k" : String.format(Locale.US, "%.1fk", ft / 1000.0)) : ft + ""; }

  static JSONObject tide(String id, String name, boolean english, String localNow) throws Exception {
    String day = localNow.length() >= 8 ? localNow.substring(0, 8) : new java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(new java.util.Date());
    String base = "https://api.tidesandcurrents.noaa.gov/api/prod/datagetter?product=predictions&datum=MLLW&time_zone=lst_ldt&format=json&application=weather_app&station=" + id + "&units=" + (english ? "english" : "metric");
    JSONObject hl = new JSONObject(WeatherJob.get(base + "&begin_date=" + day + "&range=48&interval=hilo"));
    JSONObject o = new JSONObject();
    o.put("name", name);
    JSONArray ext = new JSONArray(), e = hl.getJSONArray("predictions");
    for (int i = 0; i < e.length(); i++) { JSONObject q = e.getJSONObject(i); ext.put(new JSONArray().put(q.getString("t")).put(q.getDouble("v")).put(q.optString("type"))); }
    JSONArray pts = new JSONArray();
    try {
      JSONArray p = new JSONObject(WeatherJob.get(base + "&begin_date=" + day + "&range=48&interval=30")).getJSONArray("predictions");
      for (int i = 0; i < p.length(); i++) { JSONObject q = p.getJSONObject(i); pts.put(new JSONArray().put(q.getString("t")).put(q.getDouble("v"))); }
    } catch (Exception noCurve) {
      // subordinate stations publish only highs and lows: draw NOAA's cosine curve between them
      java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
      f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
      for (int k = 0; k + 1 < ext.length(); k++) {
        long ta = f.parse(ext.getJSONArray(k).getString(0)).getTime(), tb = f.parse(ext.getJSONArray(k + 1).getString(0)).getTime();
        double va = ext.getJSONArray(k).getDouble(1), vb = ext.getJSONArray(k + 1).getDouble(1);
        for (long t = (ta / 1800000L + 1) * 1800000L; t < tb; t += 1800000L) {
          double fr = (t - ta) / (double) (tb - ta);
          pts.put(new JSONArray().put(f.format(new java.util.Date(t))).put(va + (vb - va) * (1 - Math.cos(Math.PI * fr)) / 2));
        }
      }
    }
    o.put("pts", pts);
    o.put("ext", ext);
    return o;
  }

  static double waterTemp(String id, boolean F) throws Exception {
    JSONObject j = new JSONObject(WeatherJob.get("https://api.tidesandcurrents.noaa.gov/api/prod/datagetter?product=water_temperature&range=6&format=json&application=weather_app&time_zone=gmt&station=" + id + "&units=" + (F ? "english" : "metric")));
    JSONArray a = j.getJSONArray("data");
    for (int i = a.length() - 1; i >= 0; i--) { String v = a.getJSONObject(i).optString("v", ""); if (!v.isEmpty()) return Double.parseDouble(v); }
    throw new Exception("no water temperature");
  }
}
