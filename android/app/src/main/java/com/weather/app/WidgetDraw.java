package com.weather.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Draws each home-screen widget as one bitmap, in the app's own look (fonts, symbols, colours). */
final class WidgetDraw {
  static final int NOW = 0, PILOT = 1, GLANCE = 2, HOURS = 3, DAYS = 4, TIDE = 5, MINIMAL = 6;

  static final int WHITE = 0xFFF2F6FB, SUB = 0xFFB4C3D4, DIM = 0xFF8193A8, RAIN = 0xFF4CC9F0, NAV = 0xFF8FB8E8, WARM = 0xFFF5C25B;
  private static Typeface IT5, IT6, IT7, JB5, JB6, JB7;

  private final Context ctx;
  private final Canvas c;
  private final float w, h;
  private final TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);

  private WidgetDraw(Context ctx, Canvas c, float w, float h) { this.ctx = ctx; this.c = c; this.w = w; this.h = h; }

  static void fonts(Context ctx) {
    if (IT7 != null) return;
    android.content.res.AssetManager a = ctx.getAssets();
    IT5 = Typeface.createFromAsset(a, "fonts/InterTight-500.ttf");
    IT6 = Typeface.createFromAsset(a, "fonts/InterTight-600.ttf");
    IT7 = Typeface.createFromAsset(a, "fonts/InterTight-700.ttf");
    JB5 = Typeface.createFromAsset(a, "fonts/JetBrainsMono-500.ttf");
    JB6 = Typeface.createFromAsset(a, "fonts/JetBrainsMono-600.ttf");
    JB7 = Typeface.createFromAsset(a, "fonts/JetBrainsMono-700.ttf");
  }

  /** wDp/hDp: the widget's size in dp. Returns a bitmap at screen density (capped so it stays within RemoteViews limits). */
  static Bitmap draw(Context ctx, int kind, float wDp, float hDp, JSONObject d) {
    fonts(ctx);
    float dens = ctx.getResources().getDisplayMetrics().density;
    float scale = Math.min(Math.min(dens, 3f), (float) Math.sqrt(900_000f / Math.max(1f, wDp * hDp)));
    Bitmap b = Bitmap.createBitmap(Math.max(1, Math.round(wDp * scale)), Math.max(1, Math.round(hDp * scale)), Bitmap.Config.ARGB_8888);
    Canvas cv = new Canvas(b);
    cv.scale(scale, scale);
    WidgetDraw g = new WidgetDraw(ctx, cv, wDp, hDp);
    g.card();
    try {
      if (d == null || d.optJSONObject("now") == null) g.empty("Open Weather once to set your place");
      else switch (kind) {
        case NOW: g.now(d); break;
        case PILOT: g.pilot(d); break;
        case GLANCE: g.glance(d); break;
        case HOURS: g.hours(d); break;
        case DAYS: g.days(d); break;
        case TIDE: g.tide(d); break;
        default: g.minimal(d); break;
      }
    } catch (Exception e) {
      g.empty("Weather unavailable");
    }
    return b;
  }

  // ---------------------------------------------------------------------------------- pieces
  private void card() {
    float r = Math.min(26, Math.min(w, h) * 0.16f);
    RectF box = new RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f);
    Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
    f.setShader(new LinearGradient(0, 0, 0, h, 0xDB12243C, 0xE60A1626, Shader.TileMode.CLAMP));
    c.drawRoundRect(box, r, r, f);
    f.setShader(new RadialGradient(w * 0.88f, -h * 0.05f, Math.max(w, h) * 0.75f, 0x334C8CDC, 0x004C8CDC, Shader.TileMode.CLAMP));
    c.drawRoundRect(box, r, r, f);
    f.setShader(null);
    f.setStyle(Paint.Style.STROKE);
    f.setStrokeWidth(1f);
    f.setColor(0x2E8CBEFF);
    c.drawRoundRect(box, r, r, f);
  }

  private void empty(String msg) {
    // wrap onto as many centred lines as it takes
    java.util.List<String> lines = new java.util.ArrayList<>();
    String line = "";
    for (String word : msg.split(" ")) {
      String t = line.isEmpty() ? word : line + " " + word;
      if (!line.isEmpty() && measure(t, IT6, 14) > w - 36) { lines.add(line); line = word; } else line = t;
    }
    lines.add(line);
    float y = h / 2 + 5 - (lines.size() - 1) * 10;
    for (String l : lines) { text(l, w / 2, y, IT6, 14, SUB, Paint.Align.CENTER, 0); y += 20; }
  }

  private float text(String s, float x, float y, Typeface tf, float size, int color, Paint.Align al, float maxW) {
    p.setTypeface(tf);
    p.setTextSize(size);
    p.setColor(color);
    p.setTextAlign(al);
    p.setLetterSpacing(0);
    CharSequence t = maxW > 0 ? fit(s, maxW) : s;
    c.drawText(t, 0, t.length(), x, y, p);
    return p.measureText(t, 0, t.length());
  }

  /** Shortens to fit maxW with a trailing ellipsis (uses the paint as currently set). */
  private String fit(String s, float maxW) {
    if (p.measureText(s) <= maxW) return s;
    int n = s.length();
    while (n > 1 && p.measureText(s.substring(0, n).trim() + "…") > maxW) n--;
    return s.substring(0, n).trim() + "…";
  }

  private float measure(String s, Typeface tf, float size) {
    p.setTypeface(tf);
    p.setTextSize(size);
    p.setLetterSpacing(0);
    return p.measureText(s);
  }

  /** A run of differently styled pieces on one line; returns the width drawn. */
  private float runs(float x, float y, float maxW, Object... parts) {
    float x0 = x;
    for (int i = 0; i + 3 < parts.length; i += 4) {
      float left = maxW - (x - x0);
      if (left <= 8) break;
      x += text((String) parts[i], x, y, (Typeface) parts[i + 1], (Float) parts[i + 2], (Integer) parts[i + 3], Paint.Align.LEFT, left);
    }
    return x - x0;
  }

  /** The location arrow + place name. */
  private void header(String place, float x, float y, float maxW, int color) {
    Path a = new Path();
    float s = 10;
    a.moveTo(x + s, y - s + 1);
    a.lineTo(x, y - s * 0.45f + 1);
    a.lineTo(x + s * 0.45f, y - s * 0.45f + 1 + 0.5f);
    a.lineTo(x + s * 0.55f, y + 1);
    a.close();
    Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
    f.setColor(NAV);
    c.drawPath(a, f);
    text(place, x + 16, y, IT6, 14, color, Paint.Align.LEFT, maxW - 16);
  }

  /** Big temperature with a drawn degree ring. Returns its full width. */
  private float bigTemp(String t, float x, float base, float size, Paint.Align al) {
    float tw = measure(t, JB7, size), ring = size * 0.09f, full = tw + size * 0.08f + ring * 2 + size * 0.02f;
    float left = al == Paint.Align.CENTER ? x - full / 2 : al == Paint.Align.RIGHT ? x - full : x;
    p.setLetterSpacing(-0.04f);
    p.setTypeface(JB7);
    p.setTextSize(size);
    p.setColor(WHITE);
    p.setTextAlign(Paint.Align.LEFT);
    c.drawText(t, left, base, p);
    p.setLetterSpacing(0);
    Paint o = new Paint(Paint.ANTI_ALIAS_FLAG);
    o.setStyle(Paint.Style.STROKE);
    o.setStrokeWidth(size * 0.04f);
    o.setColor(WHITE);
    c.drawCircle(left + tw + size * 0.08f + ring, base - size * 0.66f, ring, o);
    return full;
  }

  private void icon(int code, boolean day, float x, float y, float s) {
    Drawable dr = ctx.getResources().getDrawable(iconRes(code, day), null);
    dr.setBounds(Math.round(x), Math.round(y), Math.round(x + s), Math.round(y + s));
    dr.draw(c);
  }

  static int iconRes(int c, boolean day) {
    if (c <= 1) return day ? R.drawable.wx_clear_day : c == 0 ? R.drawable.wx_clear_night : R.drawable.wx_mostly_clear_night;
    if (c == 2) return day ? R.drawable.wx_partly_day : R.drawable.wx_partly_night;
    if (c == 3) return R.drawable.wx_overcast;
    if (c == 45 || c == 48) return R.drawable.wx_fog;
    if (c >= 51 && c <= 57) return R.drawable.wx_drizzle;
    if ((c >= 71 && c <= 77) || c == 85 || c == 86) return R.drawable.wx_snow;
    if (c >= 95) return R.drawable.wx_storm;
    if (c == 66 || c == 67) return R.drawable.wx_freezing;
    if (c >= 80) return day ? R.drawable.wx_showers_day : R.drawable.wx_showers_night;
    return R.drawable.wx_rain;
  }

  static String wxText(int c) {
    switch (c) {
      case 0: return "Clear"; case 1: return "Mostly clear"; case 2: return "Partly cloudy"; case 3: return "Overcast";
      case 45: return "Fog"; case 48: return "Freezing fog"; case 51: return "Light drizzle"; case 53: return "Drizzle"; case 55: return "Heavy drizzle";
      case 56: case 57: return "Freezing drizzle"; case 61: return "Light rain"; case 63: return "Rain"; case 65: return "Heavy rain";
      case 66: case 67: return "Freezing rain"; case 71: return "Light snow"; case 73: return "Snow"; case 75: return "Heavy snow"; case 77: return "Snow grains";
      case 80: case 81: return "Showers"; case 82: return "Heavy showers"; case 85: case 86: return "Snow showers";
      case 95: return "Thunderstorms"; case 96: case 99: return "T-storms, hail";
      default: return "—";
    }
  }

  static String deg(double v) { return Double.isNaN(v) ? "--" : String.valueOf(Math.round(v)); }

  /** "Wed"-style local wall clock for the place, as "yyyy-MM-dd'T'HH:mm". */
  static String wallNow(JSONObject d) {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
    f.setTimeZone(TimeZone.getTimeZone("UTC"));
    return f.format(new Date(System.currentTimeMillis() + d.optInt("off", 0) * 1000L));
  }

  static long wallMs(String iso) {
    try {
      SimpleDateFormat f = new SimpleDateFormat(iso.length() > 10 ? "yyyy-MM-dd HH:mm" : "yyyy-MM-dd", Locale.US);
      f.setTimeZone(TimeZone.getTimeZone("UTC"));
      return f.parse(iso.replace('T', ' ')).getTime();
    } catch (Exception e) { return 0; }
  }

  /** Hours from the current one on (the cache can be up to half an hour old). */
  static JSONArray hoursFromNow(JSONObject d) throws Exception {
    JSONArray all = d.getJSONArray("hours"), out = new JSONArray();
    String now = wallNow(d).substring(0, 13);
    for (int i = 0; i < all.length(); i++) if (all.getJSONObject(i).getString("t").substring(0, 13).compareTo(now) >= 0) out.put(all.getJSONObject(i));
    return out.length() > 0 ? out : all;
  }

  static JSONArray daysFromToday(JSONObject d) throws Exception {
    JSONArray all = d.getJSONArray("days"), out = new JSONArray();
    String today = wallNow(d).substring(0, 10);
    for (int i = 0; i < all.length(); i++) if (all.getJSONObject(i).getString("d").compareTo(today) >= 0) out.put(all.getJSONObject(i));
    return out;
  }

  private static JSONObject today(JSONObject d) throws Exception {
    JSONArray ds = daysFromToday(d);
    return ds.length() > 0 ? ds.getJSONObject(0) : d.getJSONArray("days").getJSONObject(0);
  }

  private static String hiLo(JSONObject d, String sep) throws Exception {
    JSONObject t = today(d);
    return "H " + deg(t.optDouble("hi")) + "°" + sep + "L " + deg(t.optDouble("lo")) + "°";
  }

  private static String rain(JSONObject d, String k) {
    JSONObject r = d.optJSONObject("rain");
    return r == null ? "" : r.optString(k, "");
  }

  // ---------------------------------------------------------------------------------- A · Now
  private void now(JSONObject d) throws Exception {
    JSONObject n = d.getJSONObject("now");
    float pad = Math.max(14, Math.min(20, w * 0.09f));
    header(d.optString("place"), pad, pad + 13, w - pad * 2, SUB);
    float size = Math.min(h * 0.31f, w * 0.30f);
    float base = pad + 22 + size * 0.86f;
    float tw = bigTemp(deg(n.optDouble("temp")), pad - 2, base, size, Paint.Align.LEFT);
    float is = Math.min(size * 1.15f, w - pad * 2 - tw - 4);
    if (is > 28) icon(n.optInt("code"), n.optBoolean("day", true), w - pad - is + 4, base - size * 0.78f - (is - size * 0.9f) / 2, is);
    float y = h - pad;
    String r = rain(d, "short");
    float fs = h < 150 ? 12 : 13.5f;
    if (!r.isEmpty()) { text(r, pad, y, IT6, fs, RAIN, Paint.Align.LEFT, w - pad * 2); y -= fs * 1.45f; }
    text(hiLo(d, " · "), pad, y, IT5, fs, SUB, Paint.Align.LEFT, w - pad * 2);
    y -= fs * 1.55f;
    text(wxText(n.optInt("code")), pad, y, IT7, fs + 3, WHITE, Paint.Align.LEFT, w - pad * 2);
  }

  // ---------------------------------------------------------------------------------- B · Pilot
  static int catColor(String cat) {
    switch (cat) { case "VFR": return 0xFF3DDC84; case "MVFR": return 0xFF4EA8FF; case "IFR": return 0xFFFF5C5C; case "LIFR": return 0xFFE05CFF; default: return DIM; }
  }

  private void pilot(JSONObject d) throws Exception {
    JSONObject m = d.optJSONObject("metar");
    float pad = Math.max(14, Math.min(20, w * 0.09f));
    if (m == null) {
      header(d.optString("place"), pad, pad + 13, w - pad * 2, SUB);
      text("No airport nearby", w / 2, h / 2 + 6, IT6, 14, SUB, Paint.Align.CENTER, w - pad * 2);
      return;
    }
    p.setTypeface(IT6);
    header(m.optString("id") + " · " + m.optString("name"), pad, pad + 13, w - pad * 2, SUB);
    String cat = m.optString("cat", "");
    if (cat.isEmpty()) cat = "—";
    float bh = 26, bw = measure(cat, JB7, 15) + 36, by = pad + 24;
    Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
    f.setColor(0xFF1C3554);
    c.drawRoundRect(new RectF(pad, by, pad + bw, by + bh), 9, 9, f);
    f.setColor(catColor(cat));
    c.drawCircle(pad + 13, by + bh / 2, 5, f);
    text(cat, pad + 24, by + bh / 2 + 5.5f, JB7, 15, WHITE, Paint.Align.LEFT, 0);
    long age = m.optLong("obs") > 0 ? (System.currentTimeMillis() - m.optLong("obs")) / 60000 : -1;
    if (age >= 0 && w > 150) text(age + "m ago", w - pad, by + bh / 2 + 5, JB5, 12, DIM, Paint.Align.RIGHT, 0);
    String[][] rows = {{"Wind", m.optString("wind", "--")}, {"Ceiling", m.optString("ceil", "--")}, {"Vis", m.optString("vis", "--")}, {"Temp", m.optString("td", "--")}};
    float top = by + bh + 8, room = h - pad - top, step = room / rows.length, fs = Math.min(15, step * 0.62f);
    for (int i = 0; i < rows.length; i++) {
      float y = top + step * (i + 0.5f) + fs * 0.36f;
      text(rows[i][0], pad, y, IT5, fs, SUB, Paint.Align.LEFT, 0);
      text(rows[i][1], w - pad, y, JB6, fs + 1, WHITE, Paint.Align.RIGHT, w - pad * 2 - 60);
    }
  }

  // ---------------------------------------------------------------------------------- C · Glance
  private void glance(JSONObject d) throws Exception {
    JSONObject n = d.getJSONObject("now");
    float pad = 16, is = Math.min(h * 0.72f, 64), x = pad;
    icon(n.optInt("code"), n.optBoolean("day", true), x, (h - is) / 2, is);
    x += is + 8;
    float size = Math.min(h * 0.5f, 50);
    x += bigTemp(deg(n.optDouble("temp")), x, h / 2 + size * 0.36f, size, Paint.Align.LEFT) + 14;
    float room = w - x - pad;
    String cond = wxText(n.optInt("code")), where = " · " + d.optString("place");
    if (measure(cond + where, IT7, 17) > room) where = "";
    runs(x, h / 2 - 4, room, cond, IT7, 17f, WHITE, where, IT7, 17f, WHITE);
    String r = rain(d, "span");
    runs(x, h / 2 + 18, room, hiLo(d, " "), IT5, 14f, SUB, r.isEmpty() ? "" : " · ", IT5, 14f, SUB, r, IT5, 14f, RAIN);
  }

  // ---------------------------------------------------------------------------------- D · Next hours
  private void hours(JSONObject d) throws Exception {
    JSONObject n = d.getJSONObject("now");
    float pad = 18, size = Math.min(h * 0.24f, 54), base = pad + size * 0.86f;
    float x = pad + bigTemp(deg(n.optDouble("temp")), pad - 2, base, size, Paint.Align.LEFT);
    float is = size * 1.05f;
    icon(n.optInt("code"), n.optBoolean("day", true), x + 2, base - size * 0.85f, is);
    x += is + 12;
    int pmax = d.optJSONObject("rain") == null ? 0 : d.getJSONObject("rain").optInt("pmax");
    float pillW = 0;
    if (pmax >= 30) {
      String s = pmax + "% rain";
      pillW = measure(s, JB6, 13) + 18;
      Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
      f.setColor(0x334CC9F0);
      c.drawRoundRect(new RectF(w - pad - pillW, pad - 2, w - pad, pad + 20), 10, 10, f);
      text(s, w - pad - pillW / 2, pad + 13, JB6, 13, RAIN, Paint.Align.CENTER, 0);
    }
    text(wxText(n.optInt("code")), x, base - size * 0.42f, IT7, 18, WHITE, Paint.Align.LEFT, w - x - pad - pillW - 6);
    text(d.optString("place") + " · " + hiLo(d, " "), x, base - size * 0.42f + 21, IT5, 14, SUB, Paint.Align.LEFT, w - x - pad);

    JSONArray hs = hoursFromNow(d);
    int cols = Math.max(3, Math.min(Math.min(8, hs.length()), (int) ((w - pad * 2) / 54)));
    float top = base + 14, colW = (w - pad * 2) / cols, rem = h - top - 12;
    float lab = Math.min(15, rem * 0.15f), hi = Math.min(Math.min(48, colW * 0.75f), rem * 0.36f);
    float block = lab + 4 + hi + 6 + lab + 4 + lab + 6 + lab;
    top += Math.max(0, (rem - block) / 2);
    for (int i = 0; i < cols; i++) {
      JSONObject o = hs.getJSONObject(i);
      float cx = pad + colW * (i + 0.5f), y = top + lab + 2;
      text(i == 0 ? "now" : WidgetData.hourLabel(o.getString("t")), cx, y, IT5, lab, SUB, Paint.Align.CENTER, 0);
      icon(o.optInt("code"), o.optBoolean("day", true), cx - hi / 2, y + 4, hi);
      y += hi + 6 + lab + 4;
      text(deg(o.optDouble("temp")) + "°", cx + 2, y, JB6, lab + 3, WHITE, Paint.Align.CENTER, 0);
      int pp = o.optInt("pp");
      if (pp >= 30) text(pp + "%", cx, y + lab + 6, JB5, lab - 1, RAIN, Paint.Align.CENTER, 0);
    }
  }

  // ---------------------------------------------------------------------------------- E · 5-day
  private void days(JSONObject d) throws Exception {
    JSONObject n = d.getJSONObject("now");
    float pad = 18;
    header(d.optString("place"), pad, pad + 12, w * 0.55f, SUB);
    text(deg(n.optDouble("temp")) + "° " + wxText(n.optInt("code")), w - pad, pad + 12, IT5, 14, SUB, Paint.Align.RIGHT, w * 0.4f);
    JSONArray ds = daysFromToday(d);
    float top = pad + 24;
    int rows = Math.max(1, Math.min(ds.length(), Math.min(7, (int) ((h - top - 8) / 30))));
    float step = (h - top - 10) / rows, fs = Math.min(17, step * 0.52f);
    double lo = 1e9, hi = -1e9;
    for (int i = 0; i < rows; i++) { lo = Math.min(lo, ds.getJSONObject(i).optDouble("lo")); hi = Math.max(hi, ds.getJSONObject(i).optDouble("hi")); }
    String[] names = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    java.util.Calendar cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    float xName = pad, xIcon = pad + Math.max(measure("Today", IT7, fs), measure("Wed", IT7, fs)) + 8, is = Math.min(step * 0.92f, 34), xPp = xIcon + is + 6, xLo = xPp + fs * 2.9f;
    float xHi = w - pad, barL = xLo + fs * 1.9f, barR = xHi - fs * 2.0f;
    Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    track.setColor(0x26FFFFFF);
    track.setStrokeCap(Paint.Cap.ROUND);
    bar.setStrokeCap(Paint.Cap.ROUND);
    float bw = Math.max(4, fs * 0.36f);
    track.setStrokeWidth(bw);
    bar.setStrokeWidth(bw);
    for (int i = 0; i < rows; i++) {
      JSONObject o = ds.getJSONObject(i);
      float cy = top + step * (i + 0.5f), y = cy + fs * 0.36f;
      cal.setTimeInMillis(wallMs(o.getString("d")));
      text(i == 0 ? "Today" : names[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1], xName, y, IT7, fs, WHITE, Paint.Align.LEFT, xIcon - xName - 2);
      icon(o.optInt("code"), true, xIcon, cy - is / 2, is);
      int pp = o.optInt("pp");
      if (pp >= 20) text(pp + "%", xPp, y, JB6, fs - 3, RAIN, Paint.Align.LEFT, 0);
      text(deg(o.optDouble("lo")), xLo + fs * 1.3f, y, JB6, fs, SUB, Paint.Align.RIGHT, 0);
      text(deg(o.optDouble("hi")), xHi, y, JB7, fs, WHITE, Paint.Align.RIGHT, 0);
      if (barR - barL > 20 && hi > lo) {
        c.drawLine(barL, cy, barR, cy, track);
        float a = (float) (barL + (o.optDouble("lo") - lo) / (hi - lo) * (barR - barL)), b = (float) (barL + (o.optDouble("hi") - lo) / (hi - lo) * (barR - barL));
        bar.setShader(new LinearGradient(barL, 0, barR, 0, 0xFF5BC8F5, WARM, Shader.TileMode.CLAMP));
        c.drawLine(a, cy, Math.max(a + 1, b), cy, bar);
      }
    }
  }

  // ---------------------------------------------------------------------------------- F · Tide & water
  private void tide(JSONObject d) throws Exception {
    JSONObject t = d.optJSONObject("tide");
    float pad = Math.max(14, Math.min(20, w * 0.09f));
    boolean english = d.optBoolean("english", true);
    if (t == null) {
      header(d.optString("place"), pad, pad + 13, w - pad * 2, SUB);
      text("No tide station nearby", w / 2, h / 2 + 6, IT6, 14, SUB, Paint.Align.CENTER, w - pad * 2);
      return;
    }
    header(t.optString("name", "Tide"), pad, pad + 13, w - pad * 2, SUB);
    JSONArray pts = t.getJSONArray("pts"), ext = t.getJSONArray("ext");
    long now = wallMs(wallNow(d).replace('T', ' '));
    double cur = Double.NaN;
    boolean rising = true;
    for (int i = 1; i < pts.length(); i++) {
      long t1 = wallMs(pts.getJSONArray(i).getString(0));
      if (t1 >= now) {
        long t0 = wallMs(pts.getJSONArray(i - 1).getString(0));
        double v0 = pts.getJSONArray(i - 1).getDouble(1), v1 = pts.getJSONArray(i).getDouble(1);
        cur = v0 + (v1 - v0) * (now - t0) / Math.max(1.0, t1 - t0);
        rising = v1 > v0;
        break;
      }
    }
    String next = "";
    for (int i = 0; i < ext.length(); i++) {
      JSONArray e = ext.getJSONArray(i);
      if (wallMs(e.getString(0)) > now) { next = ("H".equals(e.optString(2)) ? "high " : "low ") + clock(e.getString(0)); break; }
    }
    float size = Math.min(h * 0.24f, w * 0.26f), base = pad + 22 + size * 0.86f;
    String v = Double.isNaN(cur) ? "--" : String.format(Locale.US, "%.1f", cur);
    float tw = measure(v, JB7, size);
    p.setLetterSpacing(-0.04f);
    p.setTypeface(JB7); p.setTextSize(size); p.setColor(WHITE); p.setTextAlign(Paint.Align.LEFT);
    c.drawText(v, pad - 2, base, p);
    p.setLetterSpacing(0);
    text(english ? "ft" : "m", pad + tw + 2, base - size * 0.5f, IT6, size * 0.34f, SUB, Paint.Align.LEFT, 0);
    float ly = base + 22;
    text((rising ? "Rising" : "Falling") + (next.isEmpty() ? "" : " · " + next), pad, ly, IT7, 15, WHITE, Paint.Align.LEFT, w - pad * 2);

    StringBuilder foot = new StringBuilder();
    if (d.has("water")) foot.append("Water ").append(deg(d.optDouble("water"))).append("°");
    if (d.has("waves")) foot.append(foot.length() > 0 ? " · " : "").append("waves ").append(String.format(Locale.US, "%.1f", d.optDouble("waves"))).append(english ? " ft" : " m");
    float fy = h - pad, cBot = foot.length() > 0 ? fy - 22 : fy - 4, cTop = ly + 12;
    if (foot.length() > 0) text(foot.toString(), pad, fy, IT5, 13.5f, SUB, Paint.Align.LEFT, w - pad * 2);
    if (cBot - cTop > 14) {
      long a = now - 3 * 3600_000L, b = now + 15 * 3600_000L;
      double mn = 1e9, mx = -1e9;
      for (int i = 0; i < pts.length(); i++) {
        long tt = wallMs(pts.getJSONArray(i).getString(0));
        if (tt < a || tt > b) continue;
        double q = pts.getJSONArray(i).getDouble(1);
        mn = Math.min(mn, q); mx = Math.max(mx, q);
      }
      if (mx > mn) {
        Path path = new Path();
        boolean started = false;
        float cl = pad, cr = w - pad;
        for (int i = 0; i < pts.length(); i++) {
          long tt = wallMs(pts.getJSONArray(i).getString(0));
          if (tt < a || tt > b) continue;
          float X = cl + (float) (tt - a) / (b - a) * (cr - cl), Y = (float) (cBot - (pts.getJSONArray(i).getDouble(1) - mn) / (mx - mn) * (cBot - cTop));
          if (!started) { path.moveTo(X, Y); started = true; } else path.lineTo(X, Y);
        }
        Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeWidth(3);
        s.setStrokeCap(Paint.Cap.ROUND);
        s.setStrokeJoin(Paint.Join.ROUND);
        s.setColor(RAIN);
        c.drawPath(path, s);
        if (!Double.isNaN(cur)) {
          float X = cl + (float) (now - a) / (b - a) * (cr - cl), Y = (float) (cBot - (cur - mn) / (mx - mn) * (cBot - cTop));
          Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
          dot.setColor(0xFF0E1D30);
          c.drawCircle(X, Y, 6, dot);
          dot.setColor(RAIN);
          c.drawCircle(X, Y, 4, dot);
        }
      }
    }
  }

  static String clock(String s) {
    try {
      int hh = Integer.parseInt(s.substring(11, 13)), mm = Integer.parseInt(s.substring(14, 16));
      return (hh % 12 == 0 ? 12 : hh % 12) + ":" + (mm < 10 ? "0" : "") + mm + (hh < 12 ? "a" : "p");
    } catch (Exception e) { return ""; }
  }

  // ---------------------------------------------------------------------------------- G · Minimal
  private void minimal(JSONObject d) throws Exception {
    JSONObject n = d.getJSONObject("now");
    float pad = Math.max(14, Math.min(20, w * 0.09f));
    header(d.optString("place"), pad, pad + 13, w - pad * 2, SUB);
    float fs = h < 150 ? 12 : 13.5f, foot = h - pad, size = Math.min(h * 0.27f, w * 0.30f), base = foot - fs - 10;
    float is = Math.min(base - size * 0.8f - (pad + 22) - 4, w * 0.5f);
    if (is > 20) icon(n.optInt("code"), n.optBoolean("day", true), (w - is) / 2, pad + 22 + Math.max(0, (base - size * 0.8f - (pad + 22) - is) / 2), is);
    bigTemp(deg(n.optDouble("temp")), w / 2, base, size, Paint.Align.CENTER);
    String r = rain(d, "at");
    text(r.isEmpty() ? wxText(n.optInt("code")) : r, w / 2, foot, IT5, fs, SUB, Paint.Align.CENTER, w - pad * 2);
  }
}
