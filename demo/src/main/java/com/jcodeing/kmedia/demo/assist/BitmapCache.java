/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jcodeing.kmedia.demo.assist;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.util.LruCache;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Implements a basic cache of album arts, with async loading support.
 */
public final class BitmapCache {

  private static final int MAX_ALBUM_ART_CACHE_SIZE = 12 * 1024 * 1024;  // 12 MB
  private static final int MAX_ART_WIDTH = 800;  // pixels
  private static final int MAX_ART_HEIGHT = 480;  // pixels

  // Resolution reasonable for carrying around as an icon (generally in
  // MediaDescription.getIconBitmap). This should not be bigger than necessary, because
  // the MediaDescription object should be lightweight. If you set it too high and try to
  // serialize the MediaDescription, you may get FAILED BINDER TRANSACTION errors.
  private static final int MAX_ART_WIDTH_ICON = 128;  // pixels
  private static final int MAX_ART_HEIGHT_ICON = 128;  // pixels

  private static final int BIG_BITMAP_INDEX = 0;
  private static final int ICON_BITMAP_INDEX = 1;

  private final LruCache<String, Bitmap[]> mCache;

  private static final BitmapCache sInstance = new BitmapCache();

  public static BitmapCache getInstance() {
    return sInstance;
  }

  public BitmapCache() {
    // Holds no more than MAX_ALBUM_ART_CACHE_SIZE bytes, bounded by maxmemory/4 and
    // Integer.MAX_VALUE:
    int maxSize = Math.min(MAX_ALBUM_ART_CACHE_SIZE,
        (int) (Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 4)));
    mCache = new LruCache<String, Bitmap[]>(maxSize) {
      @Override
      protected int sizeOf(String key, Bitmap[] value) {
        return value[BIG_BITMAP_INDEX].getByteCount()
            + value[ICON_BITMAP_INDEX].getByteCount();
      }
    };
  }

  public Bitmap getBigImage(String artUrl) {
    Bitmap[] result = mCache.get(artUrl);
    return result == null ? null : result[BIG_BITMAP_INDEX];
  }

  public Bitmap getIconImage(String artUrl) {
    Bitmap[] result = mCache.get(artUrl);
    return result == null ? null : result[ICON_BITMAP_INDEX];
  }

  public void fetch(final String artUrl, final FetchListener listener) {
    // WARNING: for the sake of simplicity, simultaneous multi-thread fetch requests
    // are not handled properly: they may cause redundant costly operations, like HTTP
    // requests and bitmap rescales. For production-level apps, we recommend you use
    // a proper image loading library, like Glide.
    Bitmap[] bitmap = mCache.get(artUrl);
    if (bitmap != null) {
      listener.onFetched(artUrl, bitmap[BIG_BITMAP_INDEX], bitmap[ICON_BITMAP_INDEX]);
      return;
    }

    new AsyncTask<Void, Void, Bitmap[]>() {
      @Override
      protected Bitmap[] doInBackground(Void[] objects) {
        Bitmap[] bitmaps;
        try {
          Bitmap bitmap = fetchAndRescaleBitmap(artUrl,
              MAX_ART_WIDTH, MAX_ART_HEIGHT);
          Bitmap icon = scaleBitmap(bitmap,
              MAX_ART_WIDTH_ICON, MAX_ART_HEIGHT_ICON);
          bitmaps = new Bitmap[]{bitmap, icon};
          mCache.put(artUrl, bitmaps);
        } catch (IOException e) {
          return null;
        }
        return bitmaps;
      }

      @Override
      protected void onPostExecute(Bitmap[] bitmaps) {
        if (bitmaps == null) {
          listener.onError(artUrl, new IllegalArgumentException("got null bitmaps"));
        } else {
          listener.onFetched(artUrl,
              bitmaps[BIG_BITMAP_INDEX], bitmaps[ICON_BITMAP_INDEX]);
        }
      }
    }.execute();
  }

  public static abstract class FetchListener {

    public abstract void onFetched(String url, Bitmap bigImage, Bitmap iconImage);

    public void onError(String url, Exception e) {
      //Do nothing
    }
  }

  // ============================@BitmapHelper@============================
  // Max read limit that we allow our input stream to mark/reset.
  private static final int MAX_READ_LIMIT_PER_IMG = 1024 * 1024;

  public static Bitmap scaleBitmap(Bitmap src, int maxWidth, int maxHeight) {
    double scaleFactor = Math.min(
        ((double) maxWidth) / src.getWidth(), ((double) maxHeight) / src.getHeight());
    return Bitmap.createScaledBitmap(src,
        (int) (src.getWidth() * scaleFactor), (int) (src.getHeight() * scaleFactor), false);
  }

  public static Bitmap scaleBitmap(int scaleFactor, InputStream is) {
    // Get the dimensions of the bitmap
    BitmapFactory.Options bmOptions = new BitmapFactory.Options();

    // Decode the image file into a Bitmap sized to fill the View
    bmOptions.inJustDecodeBounds = false;
    bmOptions.inSampleSize = scaleFactor;

    return BitmapFactory.decodeStream(is, null, bmOptions);
  }

  public static int findScaleFactor(int targetW, int targetH, InputStream is) {
    // Get the dimensions of the bitmap
    BitmapFactory.Options bmOptions = new BitmapFactory.Options();
    bmOptions.inJustDecodeBounds = true;
    BitmapFactory.decodeStream(is, null, bmOptions);
    int actualW = bmOptions.outWidth;
    int actualH = bmOptions.outHeight;

    // Determine how much to scale down the image
    return Math.min(actualW / targetW, actualH / targetH);
  }

  @SuppressWarnings("SameParameterValue")
  public static Bitmap fetchAndRescaleBitmap(String uri, int width, int height)
      throws IOException {
    URL url = new URL(uri);
    BufferedInputStream is = null;
    try {
      HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
      is = new BufferedInputStream(urlConnection.getInputStream());
      is.mark(MAX_READ_LIMIT_PER_IMG);
      int scaleFactor = findScaleFactor(width, height, is);
      is.reset();
      return scaleBitmap(scaleFactor, is);
    } finally {
      if (is != null) {
        is.close();
      }
    }
  }

}