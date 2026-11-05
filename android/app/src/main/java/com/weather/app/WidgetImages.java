package com.weather.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Serves the widget pictures to the home screen. A full-size widget picture is too large to send to the
 * launcher directly (Android caps that hand-off at about 1 MB), so each one is saved as a PNG and the
 * launcher reads it from here. Read-only, and only files named like the widget pictures.
 */
public class WidgetImages extends ContentProvider {
  static final String AUTHORITY = "com.weather.app.widgetimg";

  static File dir(android.content.Context c) {
    File d = new File(c.getFilesDir(), "widgets");
    if (!d.exists()) d.mkdirs();
    return d;
  }

  @Override public boolean onCreate() { return true; }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    String name = uri.getLastPathSegment();
    if (name == null || !name.matches("w\\d+_\\d+\\.png") || !"r".equals(mode)) throw new FileNotFoundException();
    File f = new File(dir(getContext()), name);
    if (!f.exists()) throw new FileNotFoundException();
    return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
  }

  @Override public String getType(Uri uri) { return "image/png"; }
  @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
  @Override public Uri insert(Uri u, ContentValues v) { return null; }
  @Override public int delete(Uri u, String s, String[] a) { return 0; }
  @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
