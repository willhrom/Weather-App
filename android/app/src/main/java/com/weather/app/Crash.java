package com.weather.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/** Keeps the last crash report, and offers it (with a Copy button) the next time the app opens. */
final class Crash {
  private Crash() {}

  static void install(Context c) {
    final File f = new File(c.getFilesDir(), "crash.txt");
    final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
    if (prev instanceof Handler) return;
    Thread.setDefaultUncaughtExceptionHandler(new Handler(f, prev));
  }

  private static final class Handler implements Thread.UncaughtExceptionHandler {
    final File f; final Thread.UncaughtExceptionHandler prev;
    Handler(File f, Thread.UncaughtExceptionHandler prev) { this.f = f; this.prev = prev; }
    @Override public void uncaughtException(Thread t, Throwable e) {
      try {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String s = "v" + BuildConfigVersion.get() + " · thread " + t.getName() + "\n" + sw;
        FileOutputStream o = new FileOutputStream(f);
        o.write(s.substring(0, Math.min(s.length(), 6000)).getBytes("UTF-8"));
        o.close();
      } catch (Throwable ignored) {}
      if (prev != null) prev.uncaughtException(t, e);
    }
  }

  static void showLast(final Activity a) {
    try {
      File f = new File(a.getFilesDir(), "crash.txt");
      if (!f.exists()) return;
      byte[] b = new byte[(int) Math.min(f.length(), 6000)];
      FileInputStream in = new FileInputStream(f);
      int n = in.read(b);
      in.close();
      f.delete();
      final String text = new String(b, 0, Math.max(0, n), "UTF-8");
      new AlertDialog.Builder(a)
          .setTitle("Weather closed unexpectedly last time")
          .setMessage("Tap Copy and paste this to Claude so it can be fixed.\n\n" + text.substring(0, Math.min(700, text.length())))
          .setPositiveButton("Copy", (d, w) -> {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Weather crash", text));
          })
          .setNegativeButton("Dismiss", null)
          .show();
    } catch (Throwable ignored) {}
  }

  static final class BuildConfigVersion {
    static String get() { return String.valueOf(System.getProperty("wx.ver", "?")); }
  }
}
