package co.kr.nuni.dam;

import static org.junit.Assert.*;

import org.junit.Test;

public class MemoryAndRestartTest {
  @Test
  public void frameDecodeTargetFitsTheScreen() {
    assertEquals(1920, Core.frameDecodeTarget(1920, 1200, false));
    assertEquals(1280, Core.frameDecodeTarget(800, 1280, false));
    assertEquals(800, Core.frameDecodeTarget(800, 480, false));
    assertEquals(1280, Core.frameDecodeTarget(1920, 1200, true));
    assertEquals(2048, Core.frameDecodeTarget(2560, 1600, false));
    assertEquals(2048, Core.frameDecodeTarget(0, 0, false));
    assertEquals(1280, Core.frameDecodeTarget(-1, 0, true));
  }

  @Test
  public void sampleUsesScreenTarget() {
    // 6000x4000 photo on a 1920px-wide tablet: 6000/1920 > 2, so sample 4
    assertEquals(4, Core.sample(6000, 4000, Core.frameDecodeTarget(1920, 1200, false)));
    assertEquals(8, Core.sample(6000, 4000, Core.frameDecodeTarget(1920, 1200, true)));
    assertEquals(1, Core.sample(1280, 800, Core.frameDecodeTarget(1920, 1200, false)));
  }

  @Test
  public void bitmapCacheIsOneEighthCapped() {
    assertEquals(4 * 1024 * 1024, Core.bitmapCacheBytes(32L * 1024 * 1024));
    assertEquals(8 * 1024 * 1024, Core.bitmapCacheBytes(256L * 1024 * 1024));
    assertEquals(1024 * 1024, Core.bitmapCacheBytes(4L * 1024 * 1024));
    assertEquals(1024 * 1024, Core.bitmapCacheBytes(0));
    assertEquals(1024 * 1024, Core.bitmapCacheBytes(-10));
  }

  @Test
  public void relaunchDecision() {
    assertTrue(Core.shouldRelaunchFrame(true, false, false));
    assertFalse(Core.shouldRelaunchFrame(true, true, false));
    assertFalse(Core.shouldRelaunchFrame(true, false, true));
    assertFalse(Core.shouldRelaunchFrame(false, false, false));
    assertFalse(Core.shouldRelaunchFrame(false, true, true));
  }

  @Test
  public void releaseCachesFromRunningLowUp() {
    assertFalse(Core.shouldReleaseCaches(5)); // RUNNING_MODERATE
    assertTrue(Core.shouldReleaseCaches(10)); // RUNNING_LOW
    assertTrue(Core.shouldReleaseCaches(15)); // RUNNING_CRITICAL
    assertTrue(Core.shouldReleaseCaches(20)); // UI_HIDDEN
    assertTrue(Core.shouldReleaseCaches(40));
    assertTrue(Core.shouldReleaseCaches(80));
    assertFalse(Core.shouldReleaseCaches(0));
  }

  @Test
  public void abnormalExitMessage() {
    assertNull(Core.abnormalExitMessage(false, false, "RUNNING_LOW"));
    assertNull(Core.abnormalExitMessage(true, true, "RUNNING_CRITICAL"));
    assertEquals(
        "비정상 종료 추정: 기록 없음, 마지막 메모리 상태 RUNNING_CRITICAL",
        Core.abnormalExitMessage(true, false, "RUNNING_CRITICAL"));
    assertEquals(
        "비정상 종료 추정: 기록 없음, 마지막 메모리 상태 없음",
        Core.abnormalExitMessage(true, false, ""));
    assertEquals(
        "비정상 종료 추정: 기록 없음, 마지막 메모리 상태 없음",
        Core.abnormalExitMessage(true, false, null));
  }
}
