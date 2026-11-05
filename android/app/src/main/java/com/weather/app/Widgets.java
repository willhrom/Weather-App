package com.weather.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.widget.RemoteViews;

import org.json.JSONObject;

/** The seven home-screen widgets. Each is one picture drawn by WidgetDraw; tapping opens the app. */
public final class Widgets {
  private Widgets() {}

  /** One low-priority thread for all widget drawing. */
  static final java.util.concurrent.ExecutorService BG = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(() -> { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run(); }, "widgets");
    t.setDaemon(true);
    return t;
  });

  static final Class<?>[] ALL = {Now.class, Pilot.class, Glance.class, Hours.class, Days.class, Tide.class, Minimal.class};
  /** Default sizes in dp (used until the launcher reports the real size). */
  private static final int[][] SIZE = {{170, 170}, {170, 170}, {350, 90}, {350, 190}, {350, 230}, {170, 170}, {170, 170}};

  public abstract static class Base extends AppWidgetProvider {
    abstract int kind();

    // Drawing happens on a low-priority background thread: these callbacks arrive on the app's main
    // thread, and drawing there made the app itself stutter (and could trip "app not responding").
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
      final Context app = c.getApplicationContext();
      final PendingResult pr = goAsync();
      final int k = kind();
      BG.execute(() -> {
        try { JSONObject d = WidgetData.cached(app); for (int id : ids) render(app, m, id, k, d); }
        catch (Throwable ignored) {}
        finally { pr.finish(); }
        WidgetData.refreshAsync(app, 20 * 60 * 1000L);
      });
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle o) {
      final Context app = c.getApplicationContext();
      final PendingResult pr = goAsync();
      final int k = kind();
      BG.execute(() -> {
        try { render(app, m, id, k, WidgetData.cached(app)); } catch (Throwable ignored) {}
        finally { pr.finish(); }
      });
    }

    @Override
    public void onEnabled(Context c) { WeatherJob.schedule(c); }
  }

  public static class Now extends Base { int kind() { return WidgetDraw.NOW; } }
  public static class Pilot extends Base { int kind() { return WidgetDraw.PILOT; } }
  public static class Glance extends Base { int kind() { return WidgetDraw.GLANCE; } }
  public static class Hours extends Base { int kind() { return WidgetDraw.HOURS; } }
  public static class Days extends Base { int kind() { return WidgetDraw.DAYS; } }
  public static class Tide extends Base { int kind() { return WidgetDraw.TIDE; } }
  public static class Minimal extends Base { int kind() { return WidgetDraw.MINIMAL; } }

  static void updateAll(Context c) {
    try { updateAllNow(c); } catch (Throwable ignored) {}
  }

  private static void updateAllNow(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    JSONObject d = WidgetData.cached(c);
    for (int k = 0; k < ALL.length; k++)
      for (int id : m.getAppWidgetIds(new ComponentName(c, ALL[k]))) render(c, m, id, k, d);
  }

  static void render(Context c, AppWidgetManager m, int id, int kind, JSONObject d) {
    try {
      Bundle o = m.getAppWidgetOptions(id);
      int w = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
      int h = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
      if (w < 40) w = SIZE[kind][0];
      if (h < 40) h = SIZE[kind][1];
      Bitmap b = WidgetDraw.draw(c, kind, w, h, d);
      RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_image);
      Uri img = save(c, id, b);
      if (img != null) rv.setImageViewUri(R.id.widget_img, img);
      else rv.setImageViewBitmap(R.id.widget_img, small(b));
      rv.setContentDescription(R.id.widget_img, describe(d));
      Intent open = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      rv.setOnClickPendingIntent(R.id.widget_img, PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
      m.updateAppWidget(id, rv);
    } catch (Throwable ignored) {}
  }

  /** Writes the picture where WidgetImages serves it; a new name each time so the launcher reloads it. */
  private static Uri save(Context c, int id, Bitmap b) {
    try {
      java.io.File dir = WidgetImages.dir(c);
      String name = "w" + id + "_" + System.currentTimeMillis() + ".png";
      java.io.File f = new java.io.File(dir, name);
      java.io.FileOutputStream o = new java.io.FileOutputStream(f);
      b.compress(Bitmap.CompressFormat.PNG, 100, o);
      o.close();
      java.io.File[] old = dir.listFiles();
      if (old != null) for (java.io.File x : old)
        if (x.getName().startsWith("w" + id + "_") && !x.getName().equals(name) && System.currentTimeMillis() - x.lastModified() > 60_000) x.delete();
      return Uri.parse("content://" + WidgetImages.AUTHORITY + "/" + name);
    } catch (Exception e) { return null; }
  }

  /** Fallback: shrink to stay under the ~1 MB limit for pictures sent straight to the launcher. */
  private static Bitmap small(Bitmap b) {
    int px = b.getWidth() * b.getHeight();
    if (px <= 160_000) return b;
    float k = (float) Math.sqrt(160_000.0 / px);
    return Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
  }

  private static String describe(JSONObject d) {
    if (d == null || d.optJSONObject("now") == null) return "Weather";
    JSONObject n = d.optJSONObject("now");
    return d.optString("place") + ", " + WidgetDraw.deg(n.optDouble("temp")) + " degrees, " + WidgetDraw.wxText(n.optInt("code"));
  }
}
