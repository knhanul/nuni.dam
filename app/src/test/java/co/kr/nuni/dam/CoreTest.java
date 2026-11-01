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
}
