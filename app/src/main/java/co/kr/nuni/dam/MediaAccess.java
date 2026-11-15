package co.kr.nuni.dam;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import androidx.core.content.ContextCompat;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** MediaStore 사진의 원본 EXIF(위치)를 읽기 위한 권한과 열기. */
public final class MediaAccess {
  public static final String ACCESS_MEDIA_LOCATION = "android.permission.ACCESS_MEDIA_LOCATION";
  public static final int REQ_LOCATION = 73;

  private static Runnable pendingAfterLocation;

  private MediaAccess() {}

  public static String[] withLocation(String[] base) {
    return withLocation(base, Build.VERSION.SDK_INT);
  }

  static String[] withLocation(String[] base, int api) {
    if (base == null) base = new String[0];
    if (api < 29) return base;
    for (String p : base) {
      if (ACCESS_MEDIA_LOCATION.equals(p)) return Arrays.copyOf(base, base.length);
    }
    String[] out = Arrays.copyOf(base, base.length + 1);
    out[base.length] = ACCESS_MEDIA_LOCATION;
    return out;
  }

  public static boolean hasLocationPermission(Context c) {
    if (c == null || Build.VERSION.SDK_INT < 29) return true;
    return ContextCompat.checkSelfPermission(c, ACCESS_MEDIA_LOCATION)
        == PackageManager.PERMISSION_GRANTED;
  }

  /** 권한이 없으면 요청한 뒤, 허용/거부와 관계없이 next를 이어서 실행한다. 이미 있으면 바로 실행. */
  public static void runWithLocationAccess(Activity a, Runnable next) {
    if (next == null) return;
    if (a == null || hasLocationPermission(a)) {
      next.run();
      return;
    }
    pendingAfterLocation = next;
    a.requestPermissions(new String[] {ACCESS_MEDIA_LOCATION}, REQ_LOCATION);
  }

  public static boolean consumeLocationResult(int requestCode) {
    if (requestCode != REQ_LOCATION) return false;
    Runnable next = pendingAfterLocation;
    pendingAfterLocation = null;
    if (next != null) next.run();
    return true;
  }

  public static InputStream openInputStream(Context c, Uri uri) throws IOException {
    if (c == null || uri == null) return null;
    Uri original = requireOriginal(uri);
    if (original != null && !original.equals(uri)) {
      try {
        InputStream in = c.getContentResolver().openInputStream(original);
        if (in != null) return in;
      } catch (SecurityException | IOException ignored) {
      }
    }
    return c.getContentResolver().openInputStream(uri);
  }

  public static Uri requireOriginal(Uri uri) {
    if (uri == null || Build.VERSION.SDK_INT < 29) return uri;
    try {
      return MediaStore.setRequireOriginal(uri);
    } catch (RuntimeException e) {
      return uri;
    }
  }
}
