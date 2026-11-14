package co.kr.nuni.dam;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 앱이 갑자기 꺼진 원인을 파일로 남긴다. 크래시 핸들러, 시스템 종료 이력(Android 11+), 메모리 부족 이벤트를 기록한다.
 */
public final class ExitLog {
  static final String DIR_NAME = "crash";
  static final String LAST_CRASH = "last_crash.txt";
  static final String MEMORY_LOG = "memory.log";
  static final int MAX_CRASH_FILES = 5;
  static final int MAX_EXIT_FILES = 10;
  static final int MAX_REPORT_BYTES = 64 * 1024;
  static final int MAX_MEMORY_LOG_BYTES = 32 * 1024;
  static final String PREF_LAST_EXIT = "lastRecordedExitAt";
  static final String PREF_NOTICE = "pendingExitNotice";
  static final String PREF_INIT = "exitLogInitialized";

  private static final Object LOCK = new Object();

  private ExitLog() {}

  public static final class Entry {
    public final long timeMs;
    public final String kind;
    public final String title;
    public final String detail;

    Entry(long timeMs, String kind, String title, String detail) {
      this.timeMs = timeMs;
      this.kind = kind;
      this.title = title;
      this.detail = detail;
    }

    public String summaryLine() {
      return formatTime(timeMs) + "  " + title;
    }
  }

  public static void install(Context context) {
    final Context app = context.getApplicationContext();
    final Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          try {
            writeCrash(app, thread, error);
          } catch (Throwable ignored) {
          }
          if (original != null) original.uncaughtException(thread, error);
        });
    try {
      collectHistoricalExits(app);
    } catch (Throwable ignored) {
    }
  }

  public static void writeCrash(Context context, Thread thread, Throwable error) {
    long now = System.currentTimeMillis();
    String report = buildCrashReport(context, thread, error, now);
    synchronized (LOCK) {
      File dir = dir(context);
      if (!dir.isDirectory() && !dir.mkdirs()) return;
      byte[] bytes = utf8(cap(report, MAX_REPORT_BYTES));
      writeFully(new File(dir, LAST_CRASH), bytes);
      File named = new File(dir, "crash-" + now + ".txt");
      writeFully(named, bytes);
      rotatePrefix(dir, "crash-", MAX_CRASH_FILES);
    }
    try {
      prefs(context)
          .edit()
          .putLong("lastCrash", now)
          .putBoolean(PREF_NOTICE, true)
          .commit();
    } catch (Exception ignored) {
    }
  }

  public static String buildCrashReport(
      Context context, Thread thread, Throwable error, long now) {
    StringBuilder sb = new StringBuilder(2048);
    sb.append("[크래시]\n");
    sb.append("시각: ").append(formatTime(now)).append(" (Asia/Seoul)\n");
    sb.append("스레드: ").append(thread == null ? "?" : thread.getName()).append('\n');
    appendAppAndDevice(sb, context);
    appendMemory(sb);
    sb.append('\n');
    if (error == null) {
      sb.append("(예외 정보 없음)\n");
    } else {
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      error.printStackTrace(pw);
      pw.flush();
      sb.append(sw.toString());
    }
    return sb.toString();
  }

  public static void collectHistoricalExits(Context context) {
    if (Build.VERSION.SDK_INT < 30) return;
    ActivityManager am = context.getSystemService(ActivityManager.class);
    if (am == null) return;
    List<ApplicationExitInfo> list;
    try {
      list = am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 5);
    } catch (Exception e) {
      return;
    }
    if (list == null || list.isEmpty()) {
      markInitialized(context);
      return;
    }
    SharedPreferences p = prefs(context);
    boolean first = !p.getBoolean(PREF_INIT, false);
    long lastSeen = p.getLong(PREF_LAST_EXIT, 0L);
    long maxTs = lastSeen;
    long newestAbnormal = 0L;
    synchronized (LOCK) {
      File dir = dir(context);
      if (!dir.isDirectory() && !dir.mkdirs()) return;
      for (ApplicationExitInfo info : list) {
        long ts = info.getTimestamp();
        if (ts <= lastSeen) continue;
        maxTs = Math.max(maxTs, ts);
        String report = buildExitReport(info);
        writeFully(new File(dir, "exit-" + ts + ".txt"), utf8(cap(report, MAX_REPORT_BYTES)));
        if (isAbnormal(info.getReason())) newestAbnormal = Math.max(newestAbnormal, ts);
      }
      rotatePrefix(dir, "exit-", MAX_EXIT_FILES);
    }
    SharedPreferences.Editor ed = p.edit();
    if (maxTs > lastSeen) ed.putLong(PREF_LAST_EXIT, maxTs);
    ed.putBoolean(PREF_INIT, true);
    if (newestAbnormal > 0L) {
      long age = System.currentTimeMillis() - newestAbnormal;
      if (!first || (age >= 0 && age <= 24L * 60 * 60 * 1000)) {
        ed.putBoolean(PREF_NOTICE, true);
      }
    }
    ed.commit();
  }

  static String buildExitReport(ApplicationExitInfo info) {
    long ts = info.getTimestamp();
    int reason = info.getReason();
    StringBuilder sb = new StringBuilder(512);
    sb.append("[종료] ").append(reasonLabel(reason)).append('\n');
    sb.append("시각: ").append(formatTime(ts)).append(" (Asia/Seoul)\n");
    sb.append("이유 코드: ").append(reason).append('\n');
    String desc = info.getDescription();
    if (desc != null && !desc.isEmpty()) sb.append("설명: ").append(desc).append('\n');
    sb.append("프로세스: pid=").append(info.getPid());
    if (Build.VERSION.SDK_INT >= 30) {
      try {
        sb.append(" · 중요도=").append(importanceLabel(info.getImportance()));
        sb.append(" · 상태=").append(info.getStatus());
        long pss = info.getPss();
        long rss = info.getRss();
        if (pss > 0 || rss > 0) {
          sb.append(" · PSS=").append(pss).append("KB");
          sb.append(" · RSS=").append(rss).append("KB");
        }
      } catch (Exception ignored) {
      }
    }
    sb.append('\n');
    if (reason == ApplicationExitInfo.REASON_ANR) {
      String trace = readTracePreview(info);
      if (!trace.isEmpty()) {
        sb.append('\n').append("ANR 추적 (일부):\n").append(trace);
      }
    }
    return sb.toString();
  }

  public static void logTrimMemory(Context context, int level) {
    appendMemoryLine(context, formatTime(System.currentTimeMillis()) + "  메모리 정리 요청 (" + trimLabel(level) + ")");
  }

  public static void logLowMemory(Context context) {
    appendMemoryLine(context, formatTime(System.currentTimeMillis()) + "  메모리 부족 (onLowMemory)");
  }

  public static List<Entry> recentEntries(Context context) {
    List<Entry> out = new ArrayList<>();
    synchronized (LOCK) {
      File dir = dir(context);
      File[] files = dir.listFiles();
      if (files == null) return out;
      for (File f : files) {
        String name = f.getName();
        if (!name.startsWith("crash-") && !name.startsWith("exit-")) continue;
        if (!name.endsWith(".txt")) continue;
        long ts = parseNamedTimestamp(name);
        if (ts <= 0L) ts = f.lastModified();
        String text = readFile(f, MAX_REPORT_BYTES);
        if (text.isEmpty()) continue;
        boolean crash = name.startsWith("crash-");
        out.add(new Entry(ts, crash ? "CRASH" : "EXIT", titleOf(text, crash), text));
      }
    }
    Collections.sort(
        out,
        new Comparator<Entry>() {
          @Override
          public int compare(Entry a, Entry b) {
            return Long.compare(b.timeMs, a.timeMs);
          }
        });
    return out;
  }

  public static String fullText(Context context) {
    StringBuilder sb = new StringBuilder();
    List<Entry> entries = recentEntries(context);
    if (entries.isEmpty()) {
      sb.append("저장된 종료 기록이 없습니다.\n");
    } else {
      for (int i = 0; i < entries.size(); i++) {
        if (i > 0) sb.append("\n--------------------\n\n");
        sb.append(entries.get(i).detail);
        if (!endsWithNewline(entries.get(i).detail)) sb.append('\n');
      }
    }
    String mem;
    synchronized (LOCK) {
      mem = readFile(new File(dir(context), MEMORY_LOG), MAX_MEMORY_LOG_BYTES);
    }
    if (!mem.isEmpty()) {
      sb.append("\n--------------------\n\n[메모리 기록]\n").append(mem);
      if (!endsWithNewline(mem)) sb.append('\n');
    }
    if (Build.VERSION.SDK_INT < 30) {
      sb.append("\n(시스템 종료 이유는 Android 11 이상에서만 확인할 수 있습니다.)\n");
    }
    return sb.toString();
  }

  public static String listSummary(Context context) {
    List<Entry> entries = recentEntries(context);
    if (entries.isEmpty()) return "기록 없음";
    return entries.get(0).summaryLine();
  }

  public static void clear(Context context) {
    synchronized (LOCK) {
      File dir = dir(context);
      File[] files = dir.listFiles();
      if (files != null) {
        for (File f : files) {
          //noinspection ResultOfMethodCallIgnored
          f.delete();
        }
      }
    }
    try {
      prefs(context).edit().putBoolean(PREF_NOTICE, false).commit();
    } catch (Exception ignored) {
    }
  }

  public static boolean hasPendingNotice(Context context) {
    try {
      return prefs(context).getBoolean(PREF_NOTICE, false);
    } catch (Exception e) {
      return false;
    }
  }

  public static void consumePendingNotice(Context context) {
    try {
      prefs(context).edit().putBoolean(PREF_NOTICE, false).apply();
    } catch (Exception ignored) {
    }
  }

  public static String reasonLabel(int reason) {
    switch (reason) {
      case 3: // REASON_LOW_MEMORY
        return "메모리 부족(LOW_MEMORY)";
      case 4: // REASON_CRASH
        return "크래시";
      case 5: // REASON_CRASH_NATIVE
        return "크래시(네이티브)";
      case 6: // REASON_ANR
        return "ANR";
      case 9: // REASON_EXCESSIVE_RESOURCE_USAGE
        return "과도한 자원 사용";
      case 10: // REASON_USER_REQUESTED
        return "사용자 요청";
      case 11: // REASON_USER_STOPPED
        return "사용자 요청";
      case 1: // REASON_EXIT_SELF
        return "앱이 직접 종료";
      case 2: // REASON_SIGNALED
        return "시스템이 종료";
      case 13: // REASON_OTHER
        return "시스템이 종료";
      case 14: // REASON_FREEZER
        return "시스템이 종료";
      case 7: // REASON_INITIALIZATION_FAILURE
        return "시작 실패";
      case 8: // REASON_PERMISSION_CHANGE
        return "권한 변경";
      case 12: // REASON_DEPENDENCY_DIED
        return "의존 프로세스 종료";
      case 15: // REASON_PACKAGE_STATE_CHANGE
        return "앱 상태 변경";
      case 16: // REASON_PACKAGE_UPDATED
        return "앱 업데이트";
      default:
        return "기타";
    }
  }

  public static boolean isAbnormal(int reason) {
    switch (reason) {
      case 1: // EXIT_SELF
      case 8: // PERMISSION_CHANGE
      case 10: // USER_REQUESTED
      case 11: // USER_STOPPED
      case 15: // PACKAGE_STATE_CHANGE
      case 16: // PACKAGE_UPDATED
        return false;
      default:
        return true;
    }
  }

  public static String trimLabel(int level) {
    // ComponentCallbacks2 / Application TRIM_MEMORY_* 상수 값
    switch (level) {
      case 5:
        return "RUNNING_MODERATE";
      case 10:
        return "RUNNING_LOW";
      case 15:
        return "RUNNING_CRITICAL";
      case 20:
        return "UI_HIDDEN";
      case 40:
        return "BACKGROUND";
      case 60:
        return "MODERATE";
      case 80:
        return "COMPLETE";
      default:
        return "LEVEL_" + level;
    }
  }

  public static String formatTime(long timeMs) {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA);
    f.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
    return f.format(new Date(timeMs));
  }

  static String cap(String text, int maxBytes) {
    if (text == null) return "";
    byte[] bytes = utf8(text);
    if (bytes.length <= maxBytes) return text;
    int keep = maxBytes - 40;
    if (keep < 16) keep = maxBytes / 2;
    // Walk back to a char boundary
    int end = keep;
    while (end > 0 && (bytes[end] & 0xC0) == 0x80) end--;
    return new String(bytes, 0, end, StandardCharsets.UTF_8) + "\n… (이후 생략)\n";
  }

  static void rotatePrefix(File dir, String prefix, int keep) {
    File[] files = dir.listFiles((d, name) -> name.startsWith(prefix) && name.endsWith(".txt"));
    if (files == null || files.length <= keep) return;
    Arrays.sort(
        files,
        new Comparator<File>() {
          @Override
          public int compare(File a, File b) {
            return Long.compare(b.lastModified(), a.lastModified());
          }
        });
    for (int i = keep; i < files.length; i++) {
      //noinspection ResultOfMethodCallIgnored
      files[i].delete();
    }
  }

  static long parseNamedTimestamp(String name) {
    int dash = name.lastIndexOf('-');
    int dot = name.lastIndexOf('.');
    if (dash < 0 || dot <= dash + 1) return 0L;
    try {
      return Long.parseLong(name.substring(dash + 1, dot));
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  private static void appendMemoryLine(Context context, String line) {
    synchronized (LOCK) {
      File dir = dir(context);
      if (!dir.isDirectory() && !dir.mkdirs()) return;
      File f = new File(dir, MEMORY_LOG);
      appendCapped(f, line + "\n", MAX_MEMORY_LOG_BYTES);
    }
  }

  static void appendCapped(File f, String chunk, int maxBytes) {
    byte[] add = utf8(chunk);
    byte[] existing = f.isFile() ? readBytes(f, maxBytes * 2) : new byte[0];
    byte[] merged = new byte[existing.length + add.length];
    System.arraycopy(existing, 0, merged, 0, existing.length);
    System.arraycopy(add, 0, merged, existing.length, add.length);
    if (merged.length > maxBytes) {
      int start = merged.length - maxBytes;
      while (start < merged.length && (merged[start] & 0xC0) == 0x80) start++;
      // drop a partial first line
      int nl = start;
      while (nl < merged.length && merged[nl] != (byte) '\n') nl++;
      if (nl < merged.length - 1) start = nl + 1;
      byte[] trimmed = new byte[merged.length - start];
      System.arraycopy(merged, start, trimmed, 0, trimmed.length);
      merged = trimmed;
    }
    writeFully(f, merged);
  }

  private static void appendAppAndDevice(StringBuilder sb, Context context) {
    String versionName = "?";
    long versionCode = -1;
    try {
      PackageInfo pi =
          context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
      versionName = pi.versionName == null ? "?" : pi.versionName;
      if (Build.VERSION.SDK_INT >= 28) versionCode = pi.getLongVersionCode();
      else versionCode = pi.versionCode;
    } catch (Exception ignored) {
    }
    sb.append("앱 버전: ").append(versionName).append(" (").append(versionCode).append(")\n");
    sb.append("기기: ")
        .append(Build.MANUFACTURER)
        .append(' ')
        .append(Build.MODEL)
        .append(" / Android ")
        .append(Build.VERSION.RELEASE)
        .append(" (SDK ")
        .append(Build.VERSION.SDK_INT)
        .append(")\n");
  }

  private static void appendMemory(StringBuilder sb) {
    Runtime r = Runtime.getRuntime();
    sb.append("메모리: max ")
        .append(mb(r.maxMemory()))
        .append(" · total ")
        .append(mb(r.totalMemory()))
        .append(" · free ")
        .append(mb(r.freeMemory()))
        .append('\n');
  }

  private static String mb(long bytes) {
    return (bytes / (1024 * 1024)) + "MB";
  }

  private static String importanceLabel(int importance) {
    switch (importance) {
      case 100:
        return "포그라운드";
      case 125:
        return "포그라운드 서비스";
      case 200:
        return "화면에 보임";
      case 230:
        return "지각 가능";
      case 300:
        return "서비스";
      case 325:
        return "상위 백그라운드";
      case 350:
        return "백그라운드 못지않음";
      case 400:
        return "백그라운드";
      case 500:
        return "비어 있음";
      case 1000:
        return "사라진 상태";
      default:
        return String.valueOf(importance);
    }
  }

  private static String readTracePreview(ApplicationExitInfo info) {
    InputStream in = null;
    try {
      in = info.getTraceInputStream();
      if (in == null) return "";
      byte[] buf = new byte[8192];
      int n = in.read(buf);
      if (n <= 0) return "";
      String s = new String(buf, 0, n, StandardCharsets.UTF_8);
      if (n == buf.length) s += "\n… (이후 생략)\n";
      return s;
    } catch (Exception e) {
      return "";
    } finally {
      if (in != null) {
        try {
          in.close();
        } catch (Exception ignored) {
        }
      }
    }
  }

  private static String titleOf(String text, boolean crash) {
    if (crash) return "크래시";
    String first = firstLine(text);
    if (first.startsWith("[종료] ")) return first.substring(5).trim();
    if (first.startsWith("[크래시]")) return "크래시";
    return first.isEmpty() ? "종료" : first;
  }

  private static String firstLine(String text) {
    int n = text.indexOf('\n');
    return n < 0 ? text.trim() : text.substring(0, n).trim();
  }

  private static boolean endsWithNewline(String s) {
    return s != null && !s.isEmpty() && s.charAt(s.length() - 1) == '\n';
  }

  private static void markInitialized(Context context) {
    try {
      prefs(context).edit().putBoolean(PREF_INIT, true).apply();
    } catch (Exception ignored) {
    }
  }

  static File dir(Context context) {
    return new File(context.getFilesDir(), DIR_NAME);
  }

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences("nunidam", 0);
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

  private static String readFile(File file, int maxBytes) {
    if (file == null || !file.isFile()) return "";
    byte[] data = readBytes(file, maxBytes);
    if (data.length == 0) return "";
    return new String(data, StandardCharsets.UTF_8);
  }

  private static byte[] readBytes(File file, int maxBytes) {
    FileInputStream in = null;
    try {
      in = new FileInputStream(file);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int total = 0;
      int n;
      while ((n = in.read(buf)) > 0) {
        int allow = Math.min(n, Math.max(0, maxBytes - total));
        if (allow > 0) out.write(buf, 0, allow);
        total += n;
        if (total >= maxBytes) break;
      }
      return out.toByteArray();
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

  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }
}
