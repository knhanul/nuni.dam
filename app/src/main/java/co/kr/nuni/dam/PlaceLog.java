package co.kr.nuni.dam;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 사진마다 지명 찾기 결과를 한 줄씩 남기는 진단 로그. 설정 &gt; 앱 종료 기록의 ‘자세히 보기’에서
 * 함께 확인할 수 있다. 좌표는 소수 둘째 자리까지만 적는다.
 */
public final class PlaceLog {
  static final String FILE_NAME = "place_log.txt";
  static final String OLD_NAME = "place_log.old";
  static final String STATE_NAME = "place_log.state";
  static final int MAX_BYTES = 256 * 1024;

  private static final Object LOCK = new Object();
  private static final Map<String, String> LAST = new HashMap<>();
  private static File loadedFrom;

  private PlaceLog() {}

  public static void record(Context context, Photo photo, String result, String detail) {
    if (context == null || result == null || result.isEmpty()) return;
    String id = photo == null ? "" : photo.id;
    record(context, id, result, detail);
  }

  public static void record(Context context, String photoId, String result, String detail) {
    if (context == null || result == null || result.isEmpty()) return;
    synchronized (LOCK) {
      File dir = ExitLog.dir(context);
      if (!dir.isDirectory() && !dir.mkdirs()) return;
      loadState(dir);
      String id = photoId == null ? "" : photoId;
      String key = outcomeKey(result, detail);
      if (key.equals(LAST.get(id))) return;
      LAST.put(id, key);
      String line =
          ExitLog.formatTime(System.currentTimeMillis())
              + "  "
              + shortId(id)
              + "  "
              + result
              + (detail == null || detail.isEmpty() ? "" : "  " + sanitize(detail))
              + "\n";
      appendRolling(new File(dir, FILE_NAME), new File(dir, OLD_NAME), line, MAX_BYTES);
      saveState(dir);
    }
  }

  public static String read(Context context) {
    if (context == null) return "";
    synchronized (LOCK) {
      File f = new File(ExitLog.dir(context), FILE_NAME);
      return f.isFile() ? readUtf8(f) : "";
    }
  }

  public static void resetMemory() {
    synchronized (LOCK) {
      LAST.clear();
      loadedFrom = null;
    }
  }

  static String shortId(String id) {
    if (id == null || id.isEmpty()) return "-";
    return id.length() <= 8 ? id : id.substring(0, 8);
  }

  static String coords(double lat, double lon) {
    return String.format(Locale.US, "~%.2f,%.2f", lat, lon);
  }

  static String outcomeKey(String result, String detail) {
    if ("FOUND".equals(result)) {
      return "FOUND|" + (detail == null ? "" : detail.trim());
    }
    return result;
  }

  static String sanitize(String detail) {
    if (detail == null) return "";
    String one = detail.replace('\n', ' ').replace('\r', ' ').trim();
    if (one.length() > 240) return one.substring(0, 240);
    return one;
  }

  /**
   * 파일이 {@code maxBytes}를 넘으면 앞의 절반을 {@code oldFile}로 옮기고 최근 절반만 남긴다.
   */
  static void appendRolling(File file, File oldFile, String chunk, int maxBytes) {
    byte[] add = chunk.getBytes(StandardCharsets.UTF_8);
    byte[] existing = file.isFile() ? readBytes(file) : new byte[0];
    byte[] merged = new byte[existing.length + add.length];
    System.arraycopy(existing, 0, merged, 0, existing.length);
    System.arraycopy(add, 0, merged, existing.length, add.length);
    if (merged.length > maxBytes) {
      int mid = merged.length / 2;
      while (mid < merged.length && merged[mid] != (byte) '\n') mid++;
      if (mid < merged.length) mid++;
      if (oldFile != null && mid > 0) {
        byte[] dropped = new byte[mid];
        System.arraycopy(merged, 0, dropped, 0, mid);
        writeFully(oldFile, dropped);
      }
      byte[] kept = new byte[merged.length - mid];
      System.arraycopy(merged, mid, kept, 0, kept.length);
      merged = kept;
    }
    writeFully(file, merged);
  }

  private static void loadState(File dir) {
    if (dir.equals(loadedFrom)) return;
    LAST.clear();
    loadedFrom = dir;
    File state = new File(dir, STATE_NAME);
    if (!state.isFile()) return;
    String text = readUtf8(state);
    for (String line : text.split("\n")) {
      int tab = line.indexOf('\t');
      if (tab <= 0) continue;
      LAST.put(line.substring(0, tab), line.substring(tab + 1));
    }
  }

  private static void saveState(File dir) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> e : LAST.entrySet()) {
      if (e.getKey() == null || e.getKey().isEmpty()) continue;
      sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
    }
    writeFully(new File(dir, STATE_NAME), sb.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static String readUtf8(File f) {
    byte[] b = readBytes(f);
    return b.length == 0 ? "" : new String(b, StandardCharsets.UTF_8);
  }

  private static byte[] readBytes(File f) {
    FileInputStream in = null;
    try {
      in = new FileInputStream(f);
      byte[] buf = new byte[(int) Math.min(f.length(), MAX_BYTES * 2L + 4096)];
      int n, off = 0;
      while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
      if (off == buf.length) return buf;
      byte[] out = new byte[off];
      System.arraycopy(buf, 0, out, 0, off);
      return out;
    } catch (Exception e) {
      return new byte[0];
    } finally {
      if (in != null) {
        try {
          in.close();
        } catch (Exception ignored) {
        }
      }
    }
  }

  private static void writeFully(File file, byte[] bytes) {
    FileOutputStream out = null;
    try {
      out = new FileOutputStream(file);
      out.write(bytes);
      out.flush();
      try {
        out.getFD().sync();
      } catch (Exception ignored) {
      }
    } catch (Exception ignored) {
    } finally {
      if (out != null) {
        try {
          out.close();
        } catch (Exception ignored) {
        }
      }
    }
  }
}
