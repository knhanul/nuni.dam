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

  @Test
  public void memoryPressureOnlyForRecentLowOrWorse() {
    assertTrue(Core.memoryPressure(10, 0));
    assertTrue(Core.memoryPressure(15, 60_000));
    assertTrue(Core.memoryPressure(80, 1_000));
    assertFalse(Core.memoryPressure(5, 0)); // RUNNING_MODERATE
    assertFalse(Core.memoryPressure(20, 0)); // UI_HIDDEN
    assertFalse(Core.memoryPressure(15, 4 * 60_000)); // too old
    assertFalse(Core.memoryPressure(15, -1));
  }

  @Test
  public void folderSourceUnchanged() {
    assertFalse(Core.sourceUnchanged(false, true, 1, 1, 1, 1));
    assertFalse(Core.sourceUnchanged(true, false, 1, 1, 1, 1));
    assertTrue(Core.sourceUnchanged(true, true, 100, 2000, 100, 2000));
    assertFalse(Core.sourceUnchanged(true, true, 100, 2000, 101, 2000));
    assertFalse(Core.sourceUnchanged(true, true, 100, 2000, 100, 2001));
    // Provider without modified time: compare size only
    assertTrue(Core.sourceUnchanged(true, true, 0, 2000, 0, 2000));
    assertFalse(Core.sourceUnchanged(true, true, 0, 2000, 0, 3000));
    // Provider without size: compare modified only
    assertTrue(Core.sourceUnchanged(true, true, 100, 0, 100, 0));
    // Provider reports neither: keep the existing import, do not download again
    assertTrue(Core.sourceUnchanged(true, true, 0, 0, 0, 0));
  }

  @Test
  public void windowValueOnlyRewrittenWhenChanged() {
    assertTrue(Core.windowValueChanged(Float.NaN, 0.5f));
    assertFalse(Core.windowValueChanged(0.5f, 0.5f));
    assertTrue(Core.windowValueChanged(0.5f, 0.01f));
  }

  @Test
  public void uptimeLabel() {
    assertEquals("0시간 00분", Core.uptimeLabel(-5));
    assertEquals("1시간 05분", Core.uptimeLabel(65 * 60_000L));
    assertEquals("23시간 48분", Core.uptimeLabel((23 * 60 + 48) * 60_000L + 59_000L));
  }
}
