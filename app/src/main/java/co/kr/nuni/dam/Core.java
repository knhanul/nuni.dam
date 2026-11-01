package co.kr.nuni.dam;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Locale;

/** Pure Java policy and geometry; no Android framework required for tests. */
public final class Core {
  private Core() {}

  public static final long MAX_INPUT = 40L * 1024 * 1024, RESERVE = 96L * 1024 * 1024;

  public static boolean night(int minute, int start, int end) {
    if (minute < 0 || minute >= 1440 || start < 0 || start >= 1440 || end < 0 || end >= 1440)
      throw new IllegalArgumentException("Invalid time");
    return start != end
        && (start < end ? minute >= start && minute < end : minute >= start || minute < end);
  }

  public static int time(String s) {
    if (s == null || !s.matches("\\d{2}:\\d{2}"))
      throw new IllegalArgumentException("HH:mm 형식으로 입력하세요.");
    int h = Integer.parseInt(s.substring(0, 2)), m = Integer.parseInt(s.substring(3));
    if (h > 23 || m > 59) throw new IllegalArgumentException("시간 범위를 확인하세요.");
    return h * 60 + m;
  }

  public static String timeText(int m) {
    return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60);
  }

  public static int sample(int w, int h, int target) {
    if (w <= 0 || h <= 0 || target <= 0 || (long) w * h > 160_000_000L)
      throw new IllegalArgumentException("지원하지 않는 이미지 크기입니다.");
    int s = 1;
    while (Math.max(w, h) / (double) s > target) s *= 2;
    return s;
  }

  public static boolean fits(long used, long add, long quota, long free) {
    return used >= 0
        && add >= 0
        && used <= quota
        && add <= quota - used
        && free >= RESERVE
        && add <= free - RESERVE;
  }

  public static boolean reconcile(boolean complete, boolean error, boolean mirror, int seen) {
    return complete && !error && mirror && seen > 0;
  }

  public static boolean mayTrash(boolean owned, boolean favourite, int references) {
    return !owned && !favourite && references == 0;
  }

  public static long copy(InputStream in, OutputStream out, long max) throws IOException {
    byte[] b = new byte[16384];
    long total = 0;
    int n;
    while ((n = in.read(b)) != -1) {
      total += n;
      if (total > max) throw new IOException("파일 크기 제한을 초과했습니다.");
      out.write(b, 0, n);
    }
    return total;
  }

  public static byte[] read(InputStream in, long max) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    copy(in, b, max);
    return b.toByteArray();
  }

  public static final class ExactStream extends InputStream {
    private final InputStream in;
    private long left;

    public ExactStream(InputStream i, long length) {
      if (length < 0) throw new IllegalArgumentException();
      in = i;
      left = length;
    }

    public int read() throws IOException {
      if (left == 0) return -1;
      int v = in.read();
      if (v < 0) throw new EOFException("전송이 끊겼습니다.");
      left--;
      return v;
    }

    public int read(byte[] b, int off, int n) throws IOException {
      if (n == 0) return 0;
      if (left == 0) return -1;
      int r = in.read(b, off, (int) Math.min(left, n));
      if (r < 0) throw new EOFException("전송이 끊겼습니다.");
      left -= r;
      return r;
    }
  }

  public static final class Token {
    public final String value;
    public final long expires;

    public Token(long now, long lifetime) {
      byte[] b = new byte[24];
      new SecureRandom().nextBytes(b);
      StringBuilder s = new StringBuilder();
      for (byte v : b) s.append(String.format(Locale.ROOT, "%02x", v & 255));
      value = s.toString();
      expires = now + lifetime;
    }

    public boolean accepts(String s, long now) {
      return now < expires
          && s != null
          && MessageDigest.isEqual(
              value.getBytes(StandardCharsets.US_ASCII), s.getBytes(StandardCharsets.US_ASCII));
    }
  }

  public static final class Crop {
    public final double x, y, w, h;
    public final boolean contain;

    Crop(double xx, double yy, double ww, double hh, boolean fit) {
      x = xx;
      y = yy;
      w = ww;
      h = hh;
      contain = fit;
    }
  }

  private static boolean finite(double n) {
    return !Double.isNaN(n) && !Double.isInfinite(n);
  }

  private static double clamp(double n, double a, double b) {
    return Math.max(a, Math.min(b, n));
  }

  public static final class QuoteEntry {
    public final String kind, body, author;

    public QuoteEntry(String k, String b, String a) {
      kind = k;
      body = b;
      author = a;
    }

    public boolean isPoem() {
      return "POEM".equals(kind);
    }

    public String encode() {
      String cleanKind = isPoem() ? "POEM" : "QUOTE";
      String cleanBody = body.replace("\r\n", "\n").replace("|", "·").trim().replace("\n", "\\n");
      String cleanAuthor = author == null ? "" : author.replace("\r", "").replace("\n", " ").replace("|", "·").trim();
      return cleanKind + "|" + cleanBody + "|" + cleanAuthor;
    }
  }

  public static QuoteEntry parseQuote(String raw) {
    if (raw == null) return new QuoteEntry("QUOTE", "", "");
    String s = raw.trim();
    String kind = "QUOTE", body = s, author = "";
    int first = s.indexOf('|');
    if (first >= 0) {
      String head = s.substring(0, first).trim();
      if (head.equals("POEM") || head.equals("QUOTE")) {
        kind = head;
        String rest = s.substring(first + 1);
        int second = rest.indexOf('|');
        if (second >= 0) {
          body = rest.substring(0, second);
          author = rest.substring(second + 1).trim();
        } else {
          body = rest;
        }
      } else {
        body = head;
        author = s.substring(first + 1).trim();
      }
    }
    body = body.replace("\\n", "\n").trim();
    if (!kind.equals("POEM") && body.indexOf('\n') >= 0) kind = "POEM";
    return new QuoteEntry(kind, body, author);
  }

  public static boolean matchesQuoteKind(QuoteEntry e, String filter) {
    if (e == null || e.body.isEmpty() || "NONE".equals(filter)) return false;
    if ("POEM".equals(filter)) return e.isPoem();
    if ("QUOTE".equals(filter)) return !e.isPoem();
    return true;
  }

  public static int pickQuoteIndex(
      int size, String cycle, int photoStep, long nowMillis, int dayOfYear) {
    if (size <= 0) return -1;
    int step;
    if ("PHOTO".equals(cycle)) step = Math.max(0, photoStep);
    else if ("MINUTES_15".equals(cycle)) step = (int) (Math.max(0L, nowMillis) / 900_000L);
    else if ("HOUR".equals(cycle)) step = (int) (Math.max(0L, nowMillis) / 3_600_000L);
    else step = Math.max(0, dayOfYear);
    return Math.floorMod(step, size);
  }

  // --- Power Saving & Presence Pure State Machine (Section 7) ---

  public enum PowerState {
    ACTIVE,
    IDLE_PENDING,
    DIMMED,
    SCREEN_OFF,
    UNAVAILABLE
  }

  public enum PresenceKind {
    CAMERA_MOTION,
    PROXIMITY,
    TOUCH,
    EXTERNAL,
    SYSTEM
  }

  public enum SourceHealth {
    OK,
    UNKNOWN,
    ERROR,
    PERMISSION_DENIED,
    UNSUPPORTED
  }

  public static final class PresenceEvent {
    public final long timestampMs;
    public final PresenceKind kind;
    public final boolean detected;
    public final Float confidence;
    public final Float distanceMeters;
    public final SourceHealth health;

    public PresenceEvent(
        long ts,
        PresenceKind k,
        boolean det,
        Float conf,
        Float distMeters,
        SourceHealth h) {
      timestampMs = ts;
      kind = k;
      detected = det;
      confidence = conf;
      distanceMeters = distMeters;
      health = h;
    }

    public static PresenceEvent touch(long nowMs) {
      return new PresenceEvent(nowMs, PresenceKind.TOUCH, true, null, null, SourceHealth.OK);
    }

    public static PresenceEvent motion(long nowMs, boolean detected, float ratio) {
      return new PresenceEvent(
          nowMs, PresenceKind.CAMERA_MOTION, detected, ratio, null, SourceHealth.OK);
    }

    public static PresenceEvent failure(long nowMs, PresenceKind k, SourceHealth health) {
      return new PresenceEvent(nowMs, k, false, null, null, health);
    }
  }

  public static final class PowerConfig {
    public final boolean nightEnabled;
    public final int nightStartMin, nightEndMin;
    public final boolean motionEnabled;
    public final long idleTimeoutMs;
    public final boolean reactAtNight;
    public final boolean allowScreenOff;
    public final boolean screenOffWakeVerified;
    public final boolean sourceAvailable;

    public PowerConfig(
        boolean nightEnabled,
        int nightStartMin,
        int nightEndMin,
        boolean motionEnabled,
        long idleTimeoutMs,
        boolean reactAtNight,
        boolean allowScreenOff,
        boolean screenOffWakeVerified,
        boolean sourceAvailable) {
      this.nightEnabled = nightEnabled;
      this.nightStartMin = nightStartMin;
      this.nightEndMin = nightEndMin;
      this.motionEnabled = motionEnabled;
      this.idleTimeoutMs = Math.max(10_000L, idleTimeoutMs);
      this.reactAtNight = reactAtNight;
      this.allowScreenOff = allowScreenOff;
      this.screenOffWakeVerified = screenOffWakeVerified;
      this.sourceAvailable = sourceAvailable;
    }
  }

  public static final class PowerSavingStateMachine {
    public static final long MIN_HOLD_MS = 30_000L;
    public static final long DEBOUNCE_MS = 1_500L;
    public static final long TOUCH_NIGHT_WAKE_MS = 60_000L;

    private PowerState state = PowerState.ACTIVE;
    private SourceHealth lastHealth = SourceHealth.OK;
    private long lastValidActivityMs;
    private long lastWakeMs;
    private long lastMotionEventMs = -10_000L;
    private long lastTouchWakeAtNightMs = -100_000L;
    private boolean inhibited;
    private boolean observedScreenOff;

    public PowerSavingStateMachine(long initialElapsedMs) {
      lastValidActivityMs = initialElapsedMs;
      lastWakeMs = initialElapsedMs;
    }

    public PowerState state() {
      return state;
    }

    public SourceHealth health() {
      return lastHealth;
    }

    public boolean inhibited() {
      return inhibited;
    }

    public void setInhibited(boolean b, long nowElapsedMs) {
      inhibited = b;
      if (b) {
        lastValidActivityMs = nowElapsedMs;
        if (state == PowerState.IDLE_PENDING || state == PowerState.DIMMED) {
          state = PowerState.ACTIVE;
          lastWakeMs = nowElapsedMs;
        }
      }
    }

    public void notifyObservedScreenOff(boolean isOff, long nowElapsedMs, PowerConfig cfg) {
      observedScreenOff = isOff;
      if (isOff && cfg.allowScreenOff && cfg.screenOffWakeVerified && state == PowerState.DIMMED) {
        state = PowerState.SCREEN_OFF;
      } else if (!isOff && state == PowerState.SCREEN_OFF) {
        state = PowerState.ACTIVE;
        lastValidActivityMs = nowElapsedMs;
        lastWakeMs = nowElapsedMs;
      }
    }

    public PowerState onEvent(PresenceEvent ev, int currentMinuteOfDay, PowerConfig cfg) {
      if (ev == null) return state;
      if (ev.health != SourceHealth.OK) {
        lastHealth = ev.health;
        // Camera/sensor errors or permission revocation are UNKNOWN/ERROR: never treat as immediate absence!
        if (ev.health == SourceHealth.UNSUPPORTED || ev.health == SourceHealth.PERMISSION_DENIED) {
          if (cfg.motionEnabled) state = PowerState.UNAVAILABLE;
        }
        return state;
      }
      lastHealth = SourceHealth.OK;
      boolean isNight =
          cfg.nightEnabled && night(currentMinuteOfDay, cfg.nightStartMin, cfg.nightEndMin);
      if (ev.kind == PresenceKind.TOUCH) {
        lastValidActivityMs = ev.timestampMs;
        lastWakeMs = ev.timestampMs;
        if (isNight) lastTouchWakeAtNightMs = ev.timestampMs;
        observedScreenOff = false;
        state = PowerState.ACTIVE;
        return state;
      }
      if (!ev.detected) return state;
      if (ev.timestampMs - lastMotionEventMs < DEBOUNCE_MS) return state;
      lastMotionEventMs = ev.timestampMs;
      if (isNight && !cfg.reactAtNight) {
        // Night schedule takes priority over motion unless reactAtNight is enabled
        return state;
      }
      lastValidActivityMs = ev.timestampMs;
      if (state == PowerState.IDLE_PENDING
          || state == PowerState.DIMMED
          || state == PowerState.SCREEN_OFF
          || state == PowerState.UNAVAILABLE) {
        state = PowerState.ACTIVE;
        lastWakeMs = ev.timestampMs;
        observedScreenOff = false;
      }
      return state;
    }

    public PowerState evaluate(long nowElapsedMs, int currentMinuteOfDay, PowerConfig cfg) {
      if (inhibited) {
        state = PowerState.ACTIVE;
        lastValidActivityMs = nowElapsedMs;
        return state;
      }
      boolean isNight =
          cfg.nightEnabled && night(currentMinuteOfDay, cfg.nightStartMin, cfg.nightEndMin);
      if (isNight) {
        boolean recentTouch = (nowElapsedMs - lastTouchWakeAtNightMs) < TOUCH_NIGHT_WAKE_MS;
        boolean recentNightMotion =
            cfg.motionEnabled
                && cfg.reactAtNight
                && lastHealth == SourceHealth.OK
                && (nowElapsedMs - lastValidActivityMs) < Math.min(cfg.idleTimeoutMs, 60_000L);
        if (recentTouch || recentNightMotion) {
          state = PowerState.ACTIVE;
        } else {
          state =
              (observedScreenOff && cfg.allowScreenOff && cfg.screenOffWakeVerified)
                  ? PowerState.SCREEN_OFF
                  : PowerState.DIMMED;
        }
        return state;
      }
      if (!cfg.motionEnabled) {
        state = PowerState.ACTIVE;
        return state;
      }
      if (!cfg.sourceAvailable
          || lastHealth == SourceHealth.UNSUPPORTED
          || lastHealth == SourceHealth.PERMISSION_DENIED) {
        state = PowerState.UNAVAILABLE;
        return state;
      }
      if (lastHealth == SourceHealth.UNKNOWN || lastHealth == SourceHealth.ERROR) {
        // Do not transition to absence when sensor/camera is in UNKNOWN/ERROR state
        if (state == PowerState.IDLE_PENDING) state = PowerState.ACTIVE;
        return state;
      }
      long elapsedSinceActivity = nowElapsedMs - lastValidActivityMs;
      long elapsedSinceWake = nowElapsedMs - lastWakeMs;
      if (elapsedSinceWake < MIN_HOLD_MS) {
        state = PowerState.ACTIVE;
        return state;
      }
      if (elapsedSinceActivity < cfg.idleTimeoutMs / 2) {
        state = PowerState.ACTIVE;
      } else if (elapsedSinceActivity < cfg.idleTimeoutMs) {
        state = PowerState.IDLE_PENDING;
      } else {
        if (observedScreenOff && cfg.allowScreenOff && cfg.screenOffWakeVerified) {
          state = PowerState.SCREEN_OFF;
        } else {
          state = PowerState.DIMMED;
        }
      }
      return state;
    }
  }

  /**
   * Computes normalized luma motion score inside an ROI [left, top, right, bottom] (0..1),
   * subtracting global illumination shift to suppress auto-exposure / screen reflection false positives.
   */
  public static float lumaMotionScore(
      byte[] prevLuma,
      byte[] currLuma,
      int width,
      int height,
      float roiLeft,
      float roiTop,
      float roiRight,
      float roiBottom,
      int pixelDiffThreshold) {
    if (prevLuma == null
        || currLuma == null
        || width <= 0
        || height <= 0
        || prevLuma.length < width * height
        || currLuma.length < width * height) return 0f;
    int x0 = (int) clamp(Math.floor(roiLeft * width), 0, width - 1);
    int y0 = (int) clamp(Math.floor(roiTop * height), 0, height - 1);
    int x1 = (int) clamp(Math.ceil(roiRight * width), x0 + 1, width);
    int y1 = (int) clamp(Math.ceil(roiBottom * height), y0 + 1, height);
    long sumPrev = 0, sumCurr = 0;
    int count = 0;
    for (int y = y0; y < y1; y++) {
      int row = y * width;
      for (int x = x0; x < x1; x++) {
        sumPrev += prevLuma[row + x] & 0xff;
        sumCurr += currLuma[row + x] & 0xff;
        count++;
      }
    }
    if (count == 0) return 0f;
    int meanShift = (int) ((sumCurr - sumPrev) / count);
    int changed = 0;
    for (int y = y0; y < y1; y++) {
      int row = y * width;
      for (int x = x0; x < x1; x++) {
        int p = prevLuma[row + x] & 0xff;
        int c = (currLuma[row + x] & 0xff) - meanShift;
        if (Math.abs(c - p) >= pixelDiffThreshold) changed++;
      }
    }
    return changed / (float) count;
  }

  public static Crop crop(
      int iw, int ih, double vw, double vh, double[] faces, double fx, double fy, double zoom) {
    if (iw <= 0 || ih <= 0 || vw <= 0 || vh <= 0) return new Crop(0, 0, 1, 1, true);
    double ratio = (vw / vh) / ((double) iw / ih),
        w = Math.min(1, ratio),
        h = Math.min(1, 1 / ratio);
    zoom = clamp(zoom, 1, 1.08);
    w /= zoom;
    h /= zoom;
    boolean manual = fx >= 0 && fy >= 0 && finite(fx) && finite(fy), found = false;
    double l = 1, t = 1, r = 0, b = 0;
    if (!manual && faces != null)
      for (int i = 0; i + 3 < faces.length; i += 4) {
        double x = faces[i], y = faces[i + 1], xx = faces[i + 2], yy = faces[i + 3];
        if (!finite(x) || !finite(y) || !finite(xx) || !finite(yy) || xx <= x || yy <= y) continue;
        double px = (xx - x) * .2, py = (yy - y) * .38;
        l = Math.min(l, clamp(x - px, 0, 1));
        r = Math.max(r, clamp(xx + px, 0, 1));
        t = Math.min(t, clamp(y - py, 0, 1));
        b = Math.max(b, clamp(yy + py * .55, 0, 1));
        found = true;
      }
    if (!manual && (!found || r - l > w || b - t > h)) return new Crop(0, 0, 1, 1, true);
    double x = clamp((manual ? clamp(fx, 0, 1) : (l + r) / 2) - w / 2, 0, 1 - w),
        y = clamp((manual ? clamp(fy, 0, 1) : (t + b) / 2) - h / 2, 0, 1 - h);
    if (found) {
      x = clamp(x, Math.max(0, r - w), Math.min(l, 1 - w));
      y = clamp(y, Math.max(0, b - h), Math.min(t, 1 - h));
    }
    return new Crop(x, y, w, h, false);
  }
}
