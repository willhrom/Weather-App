package com.weather.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.RemoteViews;

import org.json.JSONObject;

/** The seven home-screen widgets. Each is one picture drawn by WidgetDraw; tapping opens the app. */
public final class Widgets {
  private Widgets() {}

  static final Class<?>[] ALL = {Now.class, Pilot.class, Glance.class, Hours.class, Days.class, Tide.class, Minimal.class};
  /** Default sizes in dp (used until the launcher reports the real size). */
  private static final int[][] SIZE = {{170, 170}, {170, 170}, {350, 90}, {350, 190}, {350, 230}, {170, 170}, {170, 170}};

  public abstract static class Base extends AppWidgetProvider {
    abstract int kind();

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
      JSONObject d = WidgetData.cached(c);
      for (int id : ids) render(c, m, id, kind(), d);
      final Context app = c.getApplicationContext();
      final PendingResult pr = goAsync();
      new Thread(() -> {
        try { WidgetData.refresh(app, 20 * 60 * 1000L); } catch (Exception ignored) {}
        finally { pr.finish(); }
      }).start();
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle o) {
      render(c, m, id, kind(), WidgetData.cached(c));
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
      rv.setImageViewBitmap(R.id.widget_img, b);
      rv.setContentDescription(R.id.widget_img, describe(d));
      Intent open = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      rv.setOnClickPendingIntent(R.id.widget_img, PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
      m.updateAppWidget(id, rv);
    } catch (Exception ignored) {}
  }

  private static String describe(JSONObject d) {
    if (d == null || d.optJSONObject("now") == null) return "Weather";
    JSONObject n = d.optJSONObject("now");
    return d.optString("place") + ", " + WidgetDraw.deg(n.optDouble("temp")) + " degrees, " + WidgetDraw.wxText(n.optInt("code"));
  }
}
