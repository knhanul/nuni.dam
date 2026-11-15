package co.kr.nuni.dam;

import static org.junit.Assert.*;

import androidx.exifinterface.media.ExifInterface;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ExifCopyTest {
  @Test
  public void tagsIncludeGpsDateMakeAndModel() {
    List<String> tags = Arrays.asList(ExifCopy.TAGS);
    assertTrue(tags.contains(ExifInterface.TAG_GPS_LATITUDE));
    assertTrue(tags.contains(ExifInterface.TAG_GPS_LATITUDE_REF));
    assertTrue(tags.contains(ExifInterface.TAG_GPS_LONGITUDE));
    assertTrue(tags.contains(ExifInterface.TAG_GPS_LONGITUDE_REF));
    assertTrue(tags.contains(ExifInterface.TAG_GPS_ALTITUDE));
    assertTrue(tags.contains(ExifInterface.TAG_GPS_ALTITUDE_REF));
    assertTrue(tags.contains(ExifInterface.TAG_DATETIME_ORIGINAL));
    assertTrue(tags.contains(ExifInterface.TAG_MAKE));
    assertTrue(tags.contains(ExifInterface.TAG_MODEL));
  }

  @Test
  public void copyAttributesWritesKnownTagsAndForcesUprightOrientation() {
    Map<String, String> src = new LinkedHashMap<>();
    src.put(ExifInterface.TAG_GPS_LATITUDE, "33/1,15/1,17/1");
    src.put(ExifInterface.TAG_GPS_LATITUDE_REF, "N");
    src.put(ExifInterface.TAG_GPS_LONGITUDE, "126/1,33/1,36/1");
    src.put(ExifInterface.TAG_GPS_LONGITUDE_REF, "E");
    src.put(ExifInterface.TAG_GPS_ALTITUDE, "12/1");
    src.put(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:05:03 12:00:00");
    src.put(ExifInterface.TAG_MAKE, "Apple");
    src.put(ExifInterface.TAG_MODEL, "iPhone 15");
    src.put(ExifInterface.TAG_ORIENTATION, "6");
    src.put("IgnoredTag", "no");
    Map<String, String> dest = new LinkedHashMap<>();
    ExifCopy.copyAttributes(src::get, dest::put);
    assertEquals("33/1,15/1,17/1", dest.get(ExifInterface.TAG_GPS_LATITUDE));
    assertEquals("N", dest.get(ExifInterface.TAG_GPS_LATITUDE_REF));
    assertEquals("126/1,33/1,36/1", dest.get(ExifInterface.TAG_GPS_LONGITUDE));
    assertEquals("E", dest.get(ExifInterface.TAG_GPS_LONGITUDE_REF));
    assertEquals("12/1", dest.get(ExifInterface.TAG_GPS_ALTITUDE));
    assertEquals("2024:05:03 12:00:00", dest.get(ExifInterface.TAG_DATETIME_ORIGINAL));
    assertEquals("Apple", dest.get(ExifInterface.TAG_MAKE));
    assertEquals("iPhone 15", dest.get(ExifInterface.TAG_MODEL));
    assertEquals(
        String.valueOf(ExifInterface.ORIENTATION_NORMAL),
        dest.get(ExifInterface.TAG_ORIENTATION));
    assertFalse(dest.containsKey("IgnoredTag"));
  }

  @Test
  public void copyAttributesSkipsMissingTags() {
    Map<String, String> dest = new LinkedHashMap<>();
    ExifCopy.copyAttributes(tag -> null, dest::put);
    assertEquals(1, dest.size());
    assertEquals(
        String.valueOf(ExifInterface.ORIENTATION_NORMAL),
        dest.get(ExifInterface.TAG_ORIENTATION));
  }

  @Test
  public void parseCapturedAtReadsExifDate() {
    // 2024:05:03 12:00:00 UTC (formatter uses Locale.ROOT, default TZ of the JVM)
    long t = ExifCopy.parseCapturedAt("2024:05:03 12:00:00");
    assertTrue(t > 0);
    assertEquals(0, ExifCopy.parseCapturedAt(null));
    assertEquals(0, ExifCopy.parseCapturedAt(""));
    assertEquals(0, ExifCopy.parseCapturedAt("not-a-date"));
    assertEquals(0, ExifCopy.parseCapturedAt("2024-05-03 12:00:00"));
  }
}
