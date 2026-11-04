package com.weather.app;

import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;

/** Renders every widget with live data for a sample place and writes PNGs (only when SHOTS_DIR is set). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "xxhdpi")
public class WidgetShotsTest {
  @Test
  public void shots() throws Exception {
    String dir = System.getProperty("shots.dir", "");
    Assume.assumeTrue(!dir.isEmpty());
    new File(dir).mkdirs();
    Context c = RuntimeEnvironment.getApplication();
    JSONObject watch = new JSONObject("{\"lat\":37.7799,\"lon\":-121.978,\"name\":\"San Ramon\",\"temp\":\"F\",\"unit\":\"in\","
        + "\"apt\":{\"id\":\"KCCR\",\"name\":\"Concord\"},\"tide\":{\"id\":\"9414290\",\"name\":\"San Francisco\"},\"wave\":{\"lat\":37.759,\"lon\":-122.833}}");
    JSONObject d = WidgetData.fetch(watch);
    write(new File(dir, "data.json"), d.toString(2).getBytes("UTF-8"));
    int[][] sizes = {{170, 170}, {170, 170}, {350, 90}, {350, 190}, {350, 230}, {170, 170}, {170, 170}};
    String[] names = {"now", "pilot", "glance", "hours", "days", "tide", "minimal"};
    for (int k = 0; k < names.length; k++) {
      save(WidgetDraw.draw(c, k, sizes[k][0], sizes[k][1], d), new File(dir, names[k] + ".png"));
      // a second size: how a resized / differently sized launcher cell looks
      save(WidgetDraw.draw(c, k, Math.round(sizes[k][0] * 1.15f), Math.round(sizes[k][1] * (k == 2 ? 1f : 1.25f)), d), new File(dir, names[k] + "_big.png"));
    }
    save(WidgetDraw.draw(c, 0, 170, 170, null), new File(dir, "empty.png"));
    // the same place on a rainy night, to see the rain styling
    JSONObject r = new JSONObject(d.toString());
    org.json.JSONArray hs = r.getJSONArray("hours");
    for (int i = 2; i < Math.min(7, hs.length()); i++) { hs.getJSONObject(i).put("pp", 50 + i * 4).put("code", 61).put("p", 0.04); }
    r.getJSONObject("now").put("code", 61);
    r.put("rain", WidgetData.rainText(hs, true));
    for (int k : new int[]{0, 2, 3, 6}) save(WidgetDraw.draw(c, k, sizes[k][0], sizes[k][1], r), new File(dir, "rain_" + names[k] + ".png"));
  }

  static void save(Bitmap b, File f) throws Exception {
    FileOutputStream o = new FileOutputStream(f);
    b.compress(Bitmap.CompressFormat.PNG, 100, o);
    o.close();
  }

  static void write(File f, byte[] b) throws Exception {
    FileOutputStream o = new FileOutputStream(f);
    o.write(b);
    o.close();
  }
}
