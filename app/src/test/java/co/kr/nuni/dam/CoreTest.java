package co.kr.nuni.dam;

import static org.junit.Assert.*;

import java.io.*;
import java.util.Random;
import org.junit.Test;

public class CoreTest {
  @Test
  public void nightAcrossMidnight() {
    assertTrue(Core.night(1380, 1320, 420));
    assertTrue(Core.night(360, 1320, 420));
    assertFalse(Core.night(420, 1320, 420));
    assertFalse(Core.night(720, 1320, 420));
  }

  @Test
  public void sameTimesDisableNight() {
    assertFalse(Core.night(0, 0, 0));
    assertFalse(Core.night(720, 0, 0));
  }

  @Test
  public void daytimeBoundaries() {
    assertTrue(Core.night(60, 60, 120));
    assertFalse(Core.night(120, 60, 120));
  }

  @Test
  public void invalidTimes() {
    assertEquals(1439, Core.time("23:59"));
    assertEquals("07:05", Core.timeText(425));
    for (String s : new String[] {"24:00", "23:60", "7:00", "01:01x", "-1:00"})
      try {
        Core.time(s);
        fail(s);
      } catch (IllegalArgumentException expected) {
      }
  }

  @Test
  public void tokenIsExact() {
    Core.Token t = new Core.Token(100, 1000);
    assertEquals(48, t.value.length());
    assertTrue(t.accepts(t.value, 100));
    assertFalse(t.accepts(null, 100));
    assertFalse(t.accepts(t.value + "x", 100));
    assertFalse(t.accepts("", 100));
  }

  @Test
  public void tokenExpiry() {
    Core.Token t = new Core.Token(100, 1000);
    assertTrue(t.accepts(t.value, 1099));
    assertFalse(t.accepts(t.value, 1100));
    assertFalse(t.accepts(t.value, 1101));
  }

  @Test
  public void tokenRotation() {
    assertNotEquals(new Core.Token(0, 10).value, new Core.Token(0, 10).value);
  }

  @Test
  public void missingFacesMeanContain() {
    assertTrue(Core.crop(1200, 1800, 1920, 1080, new double[0], -1, -1, 1).contain);
  }

  @Test
  public void distantFacesMeanContain() {
    assertTrue(
        Core.crop(
                1000,
                1800,
                1920,
                1080,
                new double[] {.1, .05, .2, .18, .7, .8, .8, .92},
                -1,
                -1,
                1.03)
            .contain);
  }

  @Test
  public void manualFocusBounds() {
    Core.Crop c = Core.crop(1000, 1500, 1920, 1080, null, 1, 0, 1);
    assertFalse(c.contain);
    assertTrue(c.x >= 0 && c.y >= 0);
    assertTrue(c.x + c.w <= 1.000001 && c.y + c.h <= 1.000001);
  }

  @Test
  public void threeThousandFaceZoomScenarios() {
    Random r = new Random(2907);
    for (int i = 0; i < 3000; i++) {
      double x = r.nextDouble() * .7,
          y = r.nextDouble() * .7,
          w = .03 + r.nextDouble() * .2,
          h = .03 + r.nextDouble() * .2;
      Core.Crop c =
          Core.crop(
              2048,
              1536,
              400 + r.nextInt(1600),
              300 + r.nextInt(900),
              new double[] {x, y, x + w, y + h},
              -1,
              -1,
              1 + r.nextDouble() * .08);
      assertTrue(c.x >= 0 && c.y >= 0);
      assertTrue(c.x + c.w <= 1.000001 && c.y + c.h <= 1.000001);
      if (!c.contain) {
        assertTrue(c.x <= x + .000001 && c.y <= y + .000001);
        assertTrue(c.x + c.w >= x + w - .000001 && c.y + c.h >= y + h - .000001);
      }
    }
  }

  @Test
  public void malformedFaceFallsBack() {
    assertTrue(
        Core.crop(1000, 1000, 600, 300, new double[] {Double.NaN, 0, .2, .2}, -1, -1, 1).contain);
  }

  @Test
  public void sampleBeforeDecode() {
    assertEquals(4, Core.sample(6000, 4000, 2048));
    assertEquals(1, Core.sample(1200, 800, 2048));
    assertEquals(8, Core.sample(6000, 4000, 1280));
  }

  @Test
  public void invalidImageBounds() {
    for (int[] d : new int[][] {{0, 100}, {-1, 3}, {100000, 100000}})
      try {
        Core.sample(d[0], d[1], 2048);
        fail();
      } catch (IllegalArgumentException expected) {
      }
  }

  @Test
  public void quotaAndSpace() {
    long m = 1048576;
    assertTrue(Core.fits(100 * m, 20 * m, 200 * m, 150 * m));
    assertFalse(Core.fits(190 * m, 20 * m, 200 * m, 150 * m));
    assertFalse(Core.fits(100 * m, 20 * m, 200 * m, 100 * m));
    assertFalse(Core.fits(-1, 0, 1, 100 * m));
  }

  @Test
  public void quotaCannotOverflow() {
    assertFalse(Core.fits(Long.MAX_VALUE - 1, 50, Long.MAX_VALUE, Long.MAX_VALUE));
    assertFalse(Core.fits(0, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
  }

  @Test
  public void failedPartialEmptyScansCannotDelete() {
    assertFalse(Core.reconcile(false, false, true, 30));
    assertFalse(Core.reconcile(true, true, true, 30));
    assertFalse(Core.reconcile(true, false, true, 0));
    assertFalse(Core.reconcile(true, false, false, 30));
    assertTrue(Core.reconcile(true, false, true, 30));
  }

  @Test
  public void retainedPhotosProtected() {
    assertFalse(Core.mayTrash(true, false, 0));
    assertFalse(Core.mayTrash(false, true, 0));
    assertFalse(Core.mayTrash(false, false, 1));
    assertTrue(Core.mayTrash(false, false, 0));
  }

  @Test
  public void streamSizeLimit() throws Exception {
    try {
      Core.read(new ByteArrayInputStream(new byte[21]), 20);
      fail();
    } catch (IOException expected) {
    }
    assertEquals(20, Core.read(new ByteArrayInputStream(new byte[20]), 20).length);
  }

  @Test
  public void exactUploadPreservesNextRequest() throws Exception {
    ByteArrayInputStream in = new ByteArrayInputStream(new byte[] {1, 2, 3, 9});
    assertArrayEquals(new byte[] {1, 2, 3}, Core.read(new Core.ExactStream(in, 3), 3));
    assertEquals(9, in.read());
  }

  @Test
  public void partialUploadRejected() throws Exception {
    try {
      Core.read(new Core.ExactStream(new ByteArrayInputStream(new byte[] {1}), 2), 3);
      fail();
    } catch (EOFException expected) {
    }
  }

  @Test
  public void quoteParsingAndEncoding() {
    Core.QuoteEntry poem =
        Core.parseQuote("POEM|죽는 날까지 하늘을 우러러\\n한 점 부끄럼이 없기를|윤동주 · 서시");
    assertTrue(poem.isPoem());
    assertEquals("죽는 날까지 하늘을 우러러\n한 점 부끄럼이 없기를", poem.body);
    assertEquals("윤동주 · 서시", poem.author);
    assertTrue(Core.matchesQuoteKind(poem, "ALL"));
    assertTrue(Core.matchesQuoteKind(poem, "POEM"));
    assertFalse(Core.matchesQuoteKind(poem, "QUOTE"));
    assertFalse(Core.matchesQuoteKind(poem, "NONE"));

    Core.QuoteEntry encoded = Core.parseQuote(poem.encode());
    assertTrue(encoded.isPoem());
    assertEquals(poem.body, encoded.body);
    assertEquals(poem.author, encoded.author);

    Core.QuoteEntry plain = Core.parseQuote("눈부신 순간을 담아, 누니담.");
    assertFalse(plain.isPoem());
    assertEquals("눈부신 순간을 담아, 누니담.", plain.body);
    assertEquals("", plain.author);
    assertTrue(Core.matchesQuoteKind(plain, "QUOTE"));
    assertFalse(Core.matchesQuoteKind(plain, "POEM"));
  }

  @Test
  public void quoteCycleSelection() {
    assertEquals(-1, Core.pickQuoteIndex(0, "PHOTO", 3, 1000L, 10));
    assertEquals(3, Core.pickQuoteIndex(10, "PHOTO", 13, 1000L, 10));
    assertEquals(2, Core.pickQuoteIndex(5, "MINUTES_15", 0, 900_000L * 7, 10));
    assertEquals(1, Core.pickQuoteIndex(5, "HOUR", 0, 3_600_000L * 6, 10));
    assertEquals(4, Core.pickQuoteIndex(5, "DAY", 0, 1000L, 9));
  }

  @Test
  public void allPhotosAlbumIsExclusiveWithNamedAlbums() {
    assertEquals(
        java.util.Arrays.asList("album_family", "album_trip"),
        Core.exclusiveAlbumSelection(
            java.util.Arrays.asList("album_default", "album_family", "album_trip"),
            "album_default"));
    assertEquals(
        java.util.Arrays.asList("album_family", "album_trip"),
        Core.exclusiveAlbumSelection(
            java.util.Arrays.asList("album_family", "album_default", "album_trip"),
            "album_default"));
    assertEquals(
        java.util.Collections.singletonList("album_default"),
        Core.exclusiveAlbumSelection(
            java.util.Collections.singletonList("album_default"), "album_default"));
    assertEquals(
        java.util.Arrays.asList("album_family", "album_trip"),
        Core.exclusiveAlbumSelection(
            java.util.Arrays.asList("album_family", "album_trip"), "album_default"));
    assertEquals(
        java.util.Collections.emptyList(),
        Core.exclusiveAlbumSelection(
            java.util.Arrays.asList("", null, "  "), "album_default"));
    assertEquals(
        java.util.Collections.singletonList("album_family"),
        Core.exclusiveAlbumSelection(
            java.util.Arrays.asList("album_family", "album_family"), "album_default"));
  }

  @Test
  public void powerStateTransitionsAndHold() {
    Core.PowerConfig cfg =
        new Core.PowerConfig(false, 1320, 420, true, 300_000L, false, false, false, true);
    Core.PowerSavingStateMachine sm = new Core.PowerSavingStateMachine(0L);
    assertEquals(Core.PowerState.ACTIVE, sm.evaluate(10_000L, 720, cfg));
    assertEquals(Core.PowerState.IDLE_PENDING, sm.evaluate(160_000L, 720, cfg));
    assertEquals(Core.PowerState.DIMMED, sm.evaluate(310_000L, 720, cfg));

    // Motion event wakes to ACTIVE and enforces 30s minimum hold
    sm.onEvent(Core.PresenceEvent.motion(320_000L, true, 0.08f), 720, cfg);
    assertEquals(Core.PowerState.ACTIVE, sm.state());
    assertEquals(Core.PowerState.ACTIVE, sm.evaluate(340_000L, 720, cfg));
  }

  @Test
  public void nightSchedulePriorityAndTouchOverride() {
    Core.PowerConfig cfg =
        new Core.PowerConfig(true, 1320, 420, true, 60_000L, false, false, false, true);
    Core.PowerSavingStateMachine sm = new Core.PowerSavingStateMachine(0L);
    // At 23:00 (1380), night schedule forces DIMMED
    assertEquals(Core.PowerState.DIMMED, sm.evaluate(5_000L, 1380, cfg));
    // Motion at night when reactAtNight=false does NOT wake screen
    sm.onEvent(Core.PresenceEvent.motion(10_000L, true, 0.1f), 1380, cfg);
    assertEquals(Core.PowerState.DIMMED, sm.evaluate(11_000L, 1380, cfg));
    // User touch temporarily wakes screen during night for 60s
    sm.onEvent(Core.PresenceEvent.touch(15_000L), 1380, cfg);
    assertEquals(Core.PowerState.ACTIVE, sm.evaluate(30_000L, 1380, cfg));
    assertEquals(Core.PowerState.DIMMED, sm.evaluate(80_000L, 1380, cfg));
  }

  @Test
  public void sensorErrorAndPermissionRevocationAreNotAbsence() {
    Core.PowerConfig cfg =
        new Core.PowerConfig(false, 1320, 420, true, 60_000L, false, false, false, true);
    Core.PowerSavingStateMachine sm = new Core.PowerSavingStateMachine(0L);
    assertEquals(Core.PowerState.IDLE_PENDING, sm.evaluate(40_000L, 720, cfg));
    // Camera error / disconnect is ERROR, must not transition to DIMMED as if user were absent
    sm.onEvent(
        Core.PresenceEvent.failure(
            45_000L, Core.PresenceKind.CAMERA_MOTION, Core.SourceHealth.ERROR),
        720,
        cfg);
    assertEquals(Core.PowerState.ACTIVE, sm.evaluate(120_000L, 720, cfg));

    // Permission revocation moves to UNAVAILABLE
    sm.onEvent(
        Core.PresenceEvent.failure(
            130_000L, Core.PresenceKind.CAMERA_MOTION, Core.SourceHealth.PERMISSION_DENIED),
        720,
        cfg);
    assertEquals(Core.PowerState.UNAVAILABLE, sm.evaluate(140_000L, 720, cfg));
  }

  @Test
  public void inhibitPreventsIdleDimmingAndScreenOffRequiresObservation() {
    Core.PowerConfig unverifiedCfg =
        new Core.PowerConfig(false, 1320, 420, true, 60_000L, false, true, false, true);
    Core.PowerSavingStateMachine sm = new Core.PowerSavingStateMachine(0L);
    sm.setInhibited(true, 10_000L);
    assertEquals(Core.PowerState.ACTIVE, sm.evaluate(200_000L, 720, unverifiedCfg));
    sm.setInhibited(false, 200_000L);
    assertEquals(Core.PowerState.DIMMED, sm.evaluate(270_000L, 720, unverifiedCfg));
    // Even if display reports off, unverified wake capability stays DIMMED
    sm.notifyObservedScreenOff(true, 275_000L, unverifiedCfg);
    assertEquals(Core.PowerState.DIMMED, sm.evaluate(280_000L, 720, unverifiedCfg));

    Core.PowerConfig verifiedCfg =
        new Core.PowerConfig(false, 1320, 420, true, 60_000L, false, true, true, true);
    sm.notifyObservedScreenOff(true, 285_000L, verifiedCfg);
    assertEquals(Core.PowerState.SCREEN_OFF, sm.evaluate(290_000L, 720, verifiedCfg));
  }

  @Test
  public void lumaMotionRoiAndGlobalLightRejection() {
    int w = 20, h = 20;
    byte[] a = new byte[w * h];
    byte[] b = new byte[w * h];
    java.util.Arrays.fill(a, (byte) 80);
    // Uniform global brightness jump (+30 everywhere) -> mean shift compensated -> 0 motion
    java.util.Arrays.fill(b, (byte) 110);
    assertEquals(0f, Core.lumaMotionScore(a, b, w, h, 0.1f, 0.1f, 0.9f, 0.9f, 18), 0.001f);

    // Motion outside ROI (top-left corner 0..2) is ignored when ROI is [0.25..0.9]
    java.util.Arrays.fill(b, (byte) 80);
    for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) b[y * w + x] = (byte) 200;
    assertEquals(0f, Core.lumaMotionScore(a, b, w, h, 0.25f, 0.25f, 0.9f, 0.9f, 18), 0.001f);

    // Motion inside ROI is detected
    for (int y = 8; y < 14; y++) for (int x = 8; x < 14; x++) b[y * w + x] = (byte) 160;
    float score = Core.lumaMotionScore(a, b, w, h, 0.25f, 0.25f, 0.9f, 0.9f, 18);
    assertTrue(score > 0.15f);
  }

  @Test
  public void schedulePriorityAndOvernightResolution() {
    java.util.List<Core.ScheduleSlot> slots = new java.util.ArrayList<>();
    // Weekly Mon-Fri (mask 62) 06:00(360) ~ 09:00(540) -> scene_morning
    slots.add(
        new Core.ScheduleSlot(
            "r1", "평일 아침", "WEEKLY", "", "scene_morning", 62, 360, 540, 3, true, 0L));
    // Weekly Mon(mask 2) overnight 22:00(1320) ~ Tue 06:00(360) -> scene_night
    slots.add(
        new Core.ScheduleSlot(
            "r2", "월요 야간", "WEEKLY", "", "scene_night", 2, 1320, 360, 3, true, 0L));

    // Monday (dow=1) at 07:00 (420) -> matches r1
    Core.ScheduleSlot s1 =
        Core.resolveActiveSlot(slots, 1000L, "2026-11-09", "2026-11-08", 1, 420);
    assertNotNull(s1);
    assertEquals("scene_morning", s1.sceneId);

    // Tuesday (dow=2) at 02:00 (120) -> matches Monday overnight r2!
    Core.ScheduleSlot s2 =
        Core.resolveActiveSlot(slots, 1000L, "2026-11-10", "2026-11-09", 2, 120);
    assertNotNull(s2);
    assertEquals("scene_night", s2.sceneId);

    // Specific DATE rule (priority 2) overrides WEEKLY rule (priority 3)
    slots.add(
        new Core.ScheduleSlot(
            "r3", "기념일", "DATE", "2026-11-09", "scene_special", 127, 360, 600, 2, true, 0L));
    Core.ScheduleSlot s3 =
        Core.resolveActiveSlot(slots, 1000L, "2026-11-09", "2026-11-08", 1, 420);
    assertEquals("scene_special", s3.sceneId);

    // TEMP rule (priority 1) overrides DATE and WEEKLY rules while not expired
    slots.add(
        new Core.ScheduleSlot(
            "r4", "임시 재생", "TEMP", "", "scene_temp", 127, 0, 1439, 1, true, 5000L));
    Core.ScheduleSlot s4 =
        Core.resolveActiveSlot(slots, 2000L, "2026-11-09", "2026-11-08", 1, 420);
    assertEquals("scene_temp", s4.sceneId);
    // After expiry (nowMs=6000L), falls back to DATE rule
    Core.ScheduleSlot s5 =
        Core.resolveActiveSlot(slots, 6000L, "2026-11-09", "2026-11-08", 1, 420);
    assertEquals("scene_special", s5.sceneId);
  }

  @Test
  public void scheduleConflictDetection() {
    java.util.List<Core.ScheduleSlot> slots = new java.util.ArrayList<>();
    slots.add(
        new Core.ScheduleSlot(
            "a", "아침 1", "WEEKLY", "", "s1", 62, 360, 540, 3, true, 0L));
    slots.add(
        new Core.ScheduleSlot(
            "b", "아침 2", "WEEKLY", "", "s2", 2, 480, 600, 3, true, 0L));
    java.util.List<String> conflicts = Core.detectScheduleConflicts(slots);
    assertEquals(1, conflicts.size());
  }

  @Test
  public void poemPaginationPreservesLinesWithoutShrinking() {
    String poem = "1행\n2행\n3행\n4행\n5행\n6행\n7행";
    java.util.List<String> pages = Core.paginatePoem(poem, 3);
    assertEquals(3, pages.size());
    assertEquals("1행\n2행\n3행", pages.get(0));
    assertEquals("4행\n5행\n6행", pages.get(1));
    assertEquals("7행", pages.get(2));
  }

  @Test
  public void secretHashingIsDeterministicAndSalted() {
    String h1 = Core.hashSecret("1234", "saltA");
    String h2 = Core.hashSecret("1234", "saltA");
    String h3 = Core.hashSecret("1234", "saltB");
    assertEquals(64, h1.length());
    assertEquals(h1, h2);
    assertNotEquals(h1, h3);
    assertNotEquals("1234", h1);
  }

  @Test
  public void parseCsvRecordsHandlesSampleCsvAndMultilinePoems() throws Exception {
    File f = new File("../docs/nunidam_sample.csv");
    if (!f.exists()) f = new File("docs/nunidam_sample.csv");
    assertTrue("nunidam_sample.csv should exist", f.isFile());
    String csv;
    try (FileInputStream in = new FileInputStream(f)) {
      csv = new String(Core.read(in, 2 * 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8);
    }
    java.util.List<String[]> rows = Core.parseCsvRecords(csv);
    // 1 header row + 250 data rows = 251 rows
    assertEquals(251, rows.size());
    assertEquals("카테고리", rows.get(0)[0].trim());
    assertEquals("본문", rows.get(0)[1].trim());
    assertEquals("인생문장", rows.get(1)[0].trim());
    assertEquals("윌리엄 셰익스피어", rows.get(1)[2].trim());
    // Check multiline poem row (윤동주 · 서시 at index 51 + 50 = 101)
    String[] poemRow = rows.get(101);
    assertEquals("시 한 구절", poemRow[0].trim());
    assertTrue(poemRow[1].contains("\n"));
    assertEquals("윤동주", poemRow[2].trim());
    assertEquals("서시", poemRow[3].trim());
    // Check last row (Bilbe Verse)
    String[] lastRow = rows.get(250);
    assertEquals("Bilbe Verse", lastRow[0].trim());
    assertEquals("Matthew 11:28", lastRow[3].trim());
  }

  @Test
  public void spreadsheetTsvAndCsvImportExportAndScheduleFormatting() {
    String tsv =
        "카테고리\t본문\t작가\t제목\t출처\t표시시간\n"
            + "인생문장\t천천히 걸어도 괜찮습니다.\t누니담\t산책\t창작\t25\n"
            + "\t카테고리 없는 문장\t작자미상\t\t\t\n";
    java.util.List<Core.ImportedTextRow> parsed = Core.parseSpreadsheetTextRows(tsv);
    assertEquals(2, parsed.size());
    assertEquals("인생문장", parsed.get(0).category);
    assertEquals("천천히 걸어도 괜찮습니다.", parsed.get(0).body);
    assertEquals("누니담", parsed.get(0).author);
    assertEquals("산책", parsed.get(0).title);
    assertEquals("창작", parsed.get(0).source);
    assertEquals(25, parsed.get(0).displaySeconds);
    assertEquals("기타", parsed.get(1).category);
    assertEquals(15, parsed.get(1).displaySeconds);

    java.util.List<String[]> exportRows = new java.util.ArrayList<>();
    exportRows.add(new String[] {"카테고리", "본문", "작가", "제목", "출처", "표시시간"});
    exportRows.add(new String[] {"시 한 구절", "첫째 줄,\n\"둘째 줄\"", "시인", "제목", "", "20"});
    String csvOut = Core.formatCsvRecords(exportRows);
    assertTrue(csvOut.startsWith("\uFEFF"));
    java.util.List<Core.ImportedTextRow> roundTrip =
        Core.parseSpreadsheetTextRows(
            Core.decodeCsvBytes(csvOut.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertEquals(1, roundTrip.size());
    assertEquals("첫째 줄,\n\"둘째 줄\"", roundTrip.get(0).body);
    assertEquals(20, roundTrip.get(0).displaySeconds);

    assertEquals("매일", Core.formatDaysMask(127));
    assertEquals("평일(월~금)", Core.formatDaysMask(62));
    assertEquals("주말(토·일)", Core.formatDaysMask(65));
    assertEquals("월·수·금", Core.formatDaysMask(2 | 8 | 32));

    Core.ScheduleSlot overnight =
        new Core.ScheduleSlot("n1", "심야", "WEEKLY", "", "scene_night", 2, 1380, 360, 3, true, 0L);
    // Monday 18~24 (1080..1440) covers 23:00~24:00 = 60 min
    assertEquals(60, Core.weeklyBandCoveredMinutes(overnight, 1, 1080, 1440));
    // Tuesday 00~06 (0..360) covers 00:00~06:00 from Monday night = 360 min
    assertEquals(360, Core.weeklyBandCoveredMinutes(overnight, 2, 0, 360));
    // Monday 00~06 has 0 min because Sunday wasn't in mask
    assertEquals(0, Core.weeklyBandCoveredMinutes(overnight, 1, 0, 360));
  }
}
