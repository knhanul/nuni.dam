package co.kr.nuni.dam;

import androidx.exifinterface.media.ExifInterface;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 다시 저장한 JPEG에 원본 EXIF의 위치·촬영시각·기종을 남기고, 방향은 이미 바로잡았으니 1로 둔다. */
public final class ExifCopy {
  public static final String[] TAGS = {
    ExifInterface.TAG_GPS_LATITUDE,
    ExifInterface.TAG_GPS_LATITUDE_REF,
    ExifInterface.TAG_GPS_LONGITUDE,
    ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_ALTITUDE,
    ExifInterface.TAG_GPS_ALTITUDE_REF,
    ExifInterface.TAG_GPS_TIMESTAMP,
    ExifInterface.TAG_GPS_DATESTAMP,
    ExifInterface.TAG_DATETIME_ORIGINAL,
    ExifInterface.TAG_DATETIME,
    ExifInterface.TAG_DATETIME_DIGITIZED,
    ExifInterface.TAG_OFFSET_TIME,
    ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
    ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
    ExifInterface.TAG_SUBSEC_TIME,
    ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
    ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
    ExifInterface.TAG_MAKE,
    ExifInterface.TAG_MODEL
  };

  interface Getter {
    String get(String tag);
  }

  interface Setter {
    void set(String tag, String value);
  }

  private ExifCopy() {}

  public static void copyAttributes(Getter src, Setter dest) {
    if (src == null || dest == null) return;
    for (String tag : TAGS) {
      String v = src.get(tag);
      if (v != null) dest.set(tag, v);
    }
    dest.set(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_NORMAL));
  }

  public static void writeTo(ExifInterface src, File dest) {
    if (src == null || dest == null || !dest.isFile()) return;
    try {
      ExifInterface out = new ExifInterface(dest.getAbsolutePath());
      copyAttributes(src::getAttribute, out::setAttribute);
      out.saveAttributes();
    } catch (Exception ignored) {
    }
  }

  public static long capturedAt(ExifInterface exif) {
    if (exif == null) return 0;
    return parseCapturedAt(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
  }

  public static long parseCapturedAt(String date) {
    if (date == null || date.isEmpty()) return 0;
    try {
      SimpleDateFormat fmt = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT);
      fmt.setLenient(false);
      Date parsed = fmt.parse(date);
      return parsed == null ? 0 : parsed.getTime();
    } catch (Exception e) {
      return 0;
    }
  }
}
