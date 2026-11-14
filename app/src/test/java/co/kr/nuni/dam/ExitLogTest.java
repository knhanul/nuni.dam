package co.kr.nuni.dam;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ExitLogTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void reasonLabelsAreKorean() {
    assertEquals("메모리 부족(LOW_MEMORY)", ExitLog.reasonLabel(3));
    assertEquals("크래시", ExitLog.reasonLabel(4));
    assertEquals("ANR", ExitLog.reasonLabel(6));
    assertEquals("과도한 자원 사용", ExitLog.reasonLabel(9));
    assertEquals("사용자 요청", ExitLog.reasonLabel(10));
    assertEquals("사용자 요청", ExitLog.reasonLabel(11));
    assertEquals("시스템이 종료", ExitLog.reasonLabel(2));
    assertEquals("시스템이 종료", ExitLog.reasonLabel(13));
    assertEquals("기타", ExitLog.reasonLabel(99));
  }

  @Test
  public void abnormalReasons() {
    assertTrue(ExitLog.isAbnormal(3));
    assertTrue(ExitLog.isAbnormal(4));
    assertTrue(ExitLog.isAbnormal(6));
    assertTrue(ExitLog.isAbnormal(9));
    assertTrue(ExitLog.isAbnormal(2));
    assertFalse(ExitLog.isAbnormal(1));
    assertFalse(ExitLog.isAbnormal(10));
    assertFalse(ExitLog.isAbnormal(11));
    assertFalse(ExitLog.isAbnormal(16));
  }

  @Test
  public void formatTimeIsSeoul() {
    // 2026-11-14 20:23:00 KST = 2026-11-14 11:23:00 UTC
    assertEquals("2026-11-14 20:23:00", ExitLog.formatTime(1794655380000L));
  }

  @Test
  public void capKeepsSmallText() {
    assertEquals("짧음", ExitLog.cap("짧음", 100));
  }

  @Test
  public void capTruncatesLargeText() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 200; i++) sb.append("가나다라마바사아자차카타파하\n");
    String capped = ExitLog.cap(sb.toString(), 200);
    assertTrue(capped.length() < sb.length());
    assertTrue(capped.contains("생략"));
    assertTrue(capped.getBytes(StandardCharsets.UTF_8).length <= 200);
  }

  @Test
  public void parseNamedTimestamp() {
    assertEquals(1763121780000L, ExitLog.parseNamedTimestamp("crash-1763121780000.txt"));
    assertEquals(42L, ExitLog.parseNamedTimestamp("exit-42.txt"));
    assertEquals(0L, ExitLog.parseNamedTimestamp("last_crash.txt"));
  }

  @Test
  public void rotateKeepsNewest() throws Exception {
    File dir = tmp.newFolder("crash");
    for (int i = 1; i <= 7; i++) {
      File f = new File(dir, "crash-" + i + ".txt");
      Files.write(f.toPath(), ("n" + i).getBytes(StandardCharsets.UTF_8));
      f.setLastModified(1_000_000L + i * 1000L);
    }
    ExitLog.rotatePrefix(dir, "crash-", 5);
    File[] left = dir.listFiles();
    assertNotNull(left);
    assertEquals(5, left.length);
    assertFalse(new File(dir, "crash-1.txt").exists());
    assertFalse(new File(dir, "crash-2.txt").exists());
    assertTrue(new File(dir, "crash-7.txt").exists());
    assertTrue(new File(dir, "crash-3.txt").exists());
  }

  @Test
  public void appendCappedRollsOldLines() throws Exception {
    File f = tmp.newFile("memory.log");
    for (int i = 0; i < 20; i++) {
      ExitLog.appendCapped(f, "line-" + i + "\n", 80);
    }
    String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    assertTrue(text.getBytes(StandardCharsets.UTF_8).length <= 80);
    assertTrue(text.contains("line-19"));
    assertFalse(text.contains("line-0"));
  }

  @Test
  public void trimLabels() {
    assertEquals("RUNNING_CRITICAL", ExitLog.trimLabel(15));
    assertEquals("UI_HIDDEN", ExitLog.trimLabel(20));
    assertEquals("LEVEL_3", ExitLog.trimLabel(3));
  }
}
