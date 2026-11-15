package co.kr.nuni.dam;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class MediaAccessTest {
  @Test
  public void withLocationAddsPermissionOnApi29AndAbove() {
    String[] got = MediaAccess.withLocation(new String[] {"android.permission.READ_MEDIA_IMAGES"}, 33);
    List<String> list = Arrays.asList(got);
    assertTrue(list.contains("android.permission.READ_MEDIA_IMAGES"));
    assertTrue(list.contains(MediaAccess.ACCESS_MEDIA_LOCATION));
    assertEquals(2, got.length);
  }

  @Test
  public void withLocationAddsAlongsideStorageOnApi29() {
    String[] got =
        MediaAccess.withLocation(new String[] {"android.permission.READ_EXTERNAL_STORAGE"}, 29);
    List<String> list = Arrays.asList(got);
    assertTrue(list.contains("android.permission.READ_EXTERNAL_STORAGE"));
    assertTrue(list.contains(MediaAccess.ACCESS_MEDIA_LOCATION));
  }

  @Test
  public void withLocationDoesNotDuplicate() {
    String[] got =
        MediaAccess.withLocation(
            new String[] {
              "android.permission.READ_MEDIA_IMAGES", MediaAccess.ACCESS_MEDIA_LOCATION
            },
            33);
    int n = 0;
    for (String p : got) if (MediaAccess.ACCESS_MEDIA_LOCATION.equals(p)) n++;
    assertEquals(1, n);
  }

  @Test
  public void withLocationSkipsBelowApi29() {
    String[] got =
        MediaAccess.withLocation(new String[] {"android.permission.READ_EXTERNAL_STORAGE"}, 28);
    assertEquals(1, got.length);
    assertEquals("android.permission.READ_EXTERNAL_STORAGE", got[0]);
  }

  @Test
  public void withLocationHandlesNullBase() {
    String[] got = MediaAccess.withLocation(null, 29);
    assertEquals(1, got.length);
    assertEquals(MediaAccess.ACCESS_MEDIA_LOCATION, got[0]);
    assertEquals(0, MediaAccess.withLocation(null, 23).length);
  }
}
