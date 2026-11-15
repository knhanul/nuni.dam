package co.kr.nuni.dam;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PlaceLogTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void coordsAreRoundedToTwoDecimals() {
    assertEquals("~33.25,126.56", PlaceLog.coords(33.2549, 126.5623));
    assertFalse(PlaceLog.coords(37.566535, 126.977969).contains("37.566535"));
  }

  @Test
  public void shortIdUsesFirstEightCharacters() {
    assertEquals("abcdefgh", PlaceLog.shortId("abcdefgh" + "x".repeat(56)));
    assertEquals("short", PlaceLog.shortId("short"));
    assertEquals("-", PlaceLog.shortId(""));
  }

  @Test
  public void outcomeKeyIgnoresRepeatedErrorsAndKeepsFoundChanges() {
    assertEquals("NO_GPS", PlaceLog.outcomeKey("NO_GPS", "sources=internal(no_gps)"));
    assertEquals("NO_GPS", PlaceLog.outcomeKey("NO_GPS", "sources=original(missing)"));
    assertEquals("PENDING", PlaceLog.outcomeKey("PENDING", "~33.25,126.56"));
    assertEquals(
        "FOUND|~33.25,126.56 제주 서귀포시 via=geocoder",
        PlaceLog.outcomeKey("FOUND", "~33.25,126.56 제주 서귀포시 via=geocoder"));
    assertNotEquals(
        PlaceLog.outcomeKey("FOUND", "~33.25,126.56 제주 via=geocoder"),
        PlaceLog.outcomeKey("FOUND", "~33.25,126.56 서울 via=geocoder"));
  }

  @Test
  public void sameOutcomeIsNotLoggedTwice() {
    HashMap<String, String> last = new HashMap<>();
    String id = "abc";
    String key = PlaceLog.outcomeKey("NO_GPS", "sources=internal(no_gps)");
    assertFalse(key.equals(last.get(id)));
    last.put(id, key);
    assertEquals(key, PlaceLog.outcomeKey("NO_GPS", "sources=original(missing),internal(no_gps)"));
    assertTrue(key.equals(last.get(id)));
  }

  @Test
  public void rollingLogDropsOldestHalfAndKeepsABackup() throws Exception {
    File log = tmp.newFile("place_log.txt");
    File old = new File(tmp.getRoot(), "place_log.old");
    int max = 200;
    StringBuilder first = new StringBuilder();
    for (int i = 0; i < 20; i++) {
      String line = "LINE-" + i + " " + "x".repeat(20) + "\n";
      first.append(line);
      PlaceLog.appendRolling(log, old, line, max);
    }
    assertTrue("should have exceeded cap", first.length() > max);
    assertTrue(log.length() <= max);
    assertTrue("expected a .old rotation", old.isFile() && old.length() > 0);
    String kept = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
    String archived = new String(Files.readAllBytes(old.toPath()), StandardCharsets.UTF_8);
    assertFalse("oldest line should have been dropped", kept.contains("LINE-0"));
    assertTrue("newest line should remain", kept.contains("LINE-19"));
    assertTrue(archived.length() > 0);
    assertTrue("archived half should hold earlier lines", archived.contains("LINE-"));
    assertFalse(kept.contains("\n\n"));
    assertTrue(kept.endsWith("\n"));
  }

  @Test
  public void summaryLineCountsActivePhotos() {
    assertEquals(
        "전체 0장 · 위치 있음 0장 · 지명 찾음 0장 · 찾는 중 0장 · 위치 없음 0장",
        PlaceLog.summaryLine(Collections.emptyList()));
    assertEquals(
        "전체 0장 · 위치 있음 0장 · 지명 찾음 0장 · 찾는 중 0장 · 위치 없음 0장",
        PlaceLog.summaryLine(null));

    Photo noGps = photo(false, "", 0);
    Photo pending = photo(true, "", 0);
    Photo found = photo(true, "제주 서귀포시", 1);
    Photo failed = photo(true, "", 99);
    assertEquals(
        "전체 4장 · 위치 있음 3장 · 지명 찾음 1장 · 찾는 중 2장 · 위치 없음 1장",
        PlaceLog.summaryLine(Arrays.asList(noGps, pending, found, failed, null)));
    assertEquals(
        "전체 4장 · 위치 있음 3장 · 지명 찾음 1장 · 찾는 중 2장 · 위치 없음 1장",
        PlaceLog.summaryLine(4, 3, 1, 2, 1));
  }

  @Test
  public void playbackReasonSkipsWhenPlaceHidden() {
    assertNull(Places.playbackOutcome(null, true, true));
    assertNull(Places.playbackOutcome(photo(true, "제주 서귀포시", 1), false, true));
  }

  @Test
  public void playbackReasonSelectsNoGpsPendingFailedAndFound() {
    Places.PlaybackOutcome noGps = Places.playbackOutcome(photo(false, "", 0), true, true);
    assertEquals("NO_GPS", noGps.result);
    assertEquals("", noGps.detail);

    Places.PlaybackOutcome pendingOff =
        Places.playbackOutcome(photo(true, "", 0), true, false);
    assertEquals("PENDING", pendingOff.result);
    assertEquals("~33.25,126.56 net=off", pendingOff.detail);

    Places.PlaybackOutcome pendingOn = Places.playbackOutcome(photo(true, "", 0), true, true);
    assertEquals("PENDING", pendingOn.result);
    assertEquals("~33.25,126.56 net=on", pendingOn.detail);

    Places.PlaybackOutcome failed = Places.playbackOutcome(photo(true, "", 42), true, true);
    assertEquals("FAILED", failed.result);
    assertEquals("~33.25,126.56 tried", failed.detail);

    Places.PlaybackOutcome found =
        Places.playbackOutcome(photo(true, "제주 서귀포시", 1), true, false);
    assertEquals("FOUND", found.result);
    assertEquals("~33.25,126.56 제주 서귀포시 via=stored", found.detail);
  }

  private static Photo photo(boolean gps, String name, long geocodedAt) {
    Photo p = new Photo();
    p.id = "testid01" + gps + name + geocodedAt;
    p.hasGps = gps;
    p.lat = 33.2549;
    p.lon = 126.5623;
    p.placeName = name;
    p.geocodedAt = geocodedAt;
    return p;
  }
}
