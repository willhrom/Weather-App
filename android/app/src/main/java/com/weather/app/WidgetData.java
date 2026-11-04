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
  static synchronized void refresh(Context c, long maxAgeMs) {
    SharedPreferences sp = c.getSharedPreferences(WeatherJob.PREFS, 0);
    String w = sp.getString("watch", null);
    JSONObject old = cached(c);
    try {
      if (w != null) {
        JSONObject watch = new JSONObject(w);
        String key = String.format(Locale.US, "%.3f,%.3f,%s,%s", watch.getDouble("lat"), watch.getDouble("lon"), watch.optString("temp", "F"), watch.optString("unit", "in"));
        boolean fresh = old != null && key.equals(old.optString("key")) && System.currentTimeMillis() - old.optLong("t") < maxAgeMs
            && watch.optJSONObject("apt") == null == (old.optJSONObject("metar") == null || old.optBoolean("noApt"));
        if (!fresh) {
          JSONObject d = fetch(watch);
          d.put("key", key);
          sp.edit().putString(KEY, d.toString()).apply();
        }
      }
    } catch (Exception ignored) {}
    Widgets.updateAll(c);
  }

  // ---------------------------------------------------------------------------------------------
  static JSONObject fetch(JSONObject watch) throws Exception {
    double lat = watch.getDouble("lat"), lon = watch.getDouble("lon");
    boolean F = !"C".equals(watch.optString("temp", "F")), english = !"mm".equals(watch.optString("unit", "in"));
    JSONObject out = new JSONObject();
    out.put("t", System.currentTimeMillis());
    out.put("place", watch.optString("name", "My location"));
    out.put("F", F);
    out.put("english", english);

    // forecast: now, next hours, next days
    JSONObject j = new JSONObject(WeatherJob.get(String.format(Locale.US,
        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,is_day,apparent_temperature"
            + "&hourly=temperature_2m,weather_code,precipitation_probability,precipitation,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
            + "&temperature_unit=%s&precipitation_unit=%s&forecast_hours=14&forecast_days=7&timezone=auto",
        lat, lon, F ? "fahrenheit" : "celsius", english ? "inch" : "mm")));
    JSONObject cur = j.getJSONObject("current"), h = j.getJSONObject("hourly"), dd = j.getJSONObject("daily");
    JSONObject now = new JSONObject();
    now.put("temp", cur.getDouble("temperature_2m"));
    now.put("code", cur.getInt("weather_code"));
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

    // nearest airport (picked by the app) — flight category, wind, ceiling, visibility
    JSONObject apt = watch.optJSONObject("apt");
    if (apt != null && apt.optString("id").matches("[A-Z0-9]{3,5}")) {
      try { out.put("metar", metar(apt.getString("id"), apt.optString("name"), F)); } catch (Exception ignored) {}
    } else out.put("noApt", true);

    // tide station (picked by the app), water temperature there, and waves at the app's buoy/model point
    JSONObject st = watch.optJSONObject("tide");
    if (st != null && st.optString("id").matches("[A-Za-z0-9]{4,12}")) {
      try { out.put("tide", tide(st.getString("id"), st.optString("name"), english, now.optString("time", "").replace("-", ""))); } catch (Exception ignored) {}
      try { out.put("water", waterTemp(st.getString("id"), F)); } catch (Exception ignored) {}
    }
    JSONObject wp = watch.optJSONObject("wave");
    if (wp != null) {
      try {
        JSONObject m = new JSONObject(WeatherJob.get(String.format(Locale.US,
            "https://marine-api.open-meteo.com/v1/marine?latitude=%.3f&longitude=%.3f&current=wave_height", wp.getDouble("lat"), wp.getDouble("lon"))));
        double wh = m.getJSONObject("current").optDouble("wave_height");
        if (!Double.isNaN(wh)) out.put("waves", english ? wh * 3.2808 : wh);
      } catch (Exception ignored) {}
    }
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
    return o;
  }

  static String fmtK(int ft) { return ft >= 1000 ? (ft % 1000 == 0 ? (ft / 1000) + "k" : String.format(Locale.US, "%.1fk", ft / 1000.0)) : ft + ""; }

  static JSONObject tide(String id, String name, boolean english, String localNow) throws Exception {
    String day = localNow.length() >= 8 ? localNow.substring(0, 8) : new java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(new java.util.Date());
    String base = "https://api.tidesandcurrents.noaa.gov/api/prod/datagetter?product=predictions&datum=MLLW&time_zone=lst_ldt&format=json&application=weather_app&station=" + id + "&units=" + (english ? "english" : "metric");
    JSONObject cv = new JSONObject(WeatherJob.get(base + "&begin_date=" + day + "&range=48&interval=30"));
    JSONObject hl = new JSONObject(WeatherJob.get(base + "&begin_date=" + day + "&range=48&interval=hilo"));
    JSONObject o = new JSONObject();
    o.put("name", name);
    JSONArray pts = new JSONArray(), p = cv.getJSONArray("predictions");
    for (int i = 0; i < p.length(); i++) { JSONObject q = p.getJSONObject(i); pts.put(new JSONArray().put(q.getString("t")).put(q.getDouble("v"))); }
    o.put("pts", pts);
    JSONArray ext = new JSONArray(), e = hl.getJSONArray("predictions");
    for (int i = 0; i < e.length(); i++) { JSONObject q = e.getJSONObject(i); ext.put(new JSONArray().put(q.getString("t")).put(q.getDouble("v")).put(q.optString("type"))); }
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
