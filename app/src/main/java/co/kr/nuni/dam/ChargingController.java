package co.kr.nuni.dam;

import android.content.*;
import android.os.*;
import java.net.*;
import java.util.concurrent.*;

/** Optional foreground-only assist. HTTP acceptance is not physical state confirmation. */
public final class ChargingController {
  private final Context c;
  private final NuniApp a;
  private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
  private volatile boolean active;
  private String last = "";
  private long attempted = -120000;

  public ChargingController(Context c) {
    this.c = c.getApplicationContext();
    a = NuniApp.of(c);
  }

  public void start() {
    active = true;
    executor.scheduleWithFixedDelay(this::tick, 5, 60, TimeUnit.SECONDS);
  }

  public void stop() {
    active = false;
    executor.shutdownNow();
  }

  private void tick() {
    if (!active || !a.prefs.raw.getBoolean("plugEnabled", false)) return;
    Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    if (b == null) return;
    int l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
        s = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
    if (l < 0 || s <= 0) return;
    int percent = l * 100 / s;
    boolean plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    String command = percent <= 30 && !plugged ? "on" : percent >= 80 && plugged ? "off" : "";
    if (command.isEmpty()) {
      if (percent > 35 && percent < 75) last = "";
      return;
    }
    if (command.equals(last) || SystemClock.elapsedRealtime() - attempted < 120000) return;
    attempted = SystemClock.elapsedRealtime();
    HttpURLConnection connection = null;
    try {
      URL u = validate(a.prefs.raw.getString(command.equals("on") ? "plugOn" : "plugOff", ""));
      if (!active) return;
      connection = (HttpURLConnection) u.openConnection();
      connection.setInstanceFollowRedirects(false);
      connection.setRequestMethod("POST");
      connection.setConnectTimeout(5000);
      connection.setReadTimeout(5000);
      connection.setDoOutput(true);
      connection.setFixedLengthStreamingMode(0);
      connection.getOutputStream().close();
      int code = connection.getResponseCode();
      if (code < 200 || code >= 300) throw new java.io.IOException();
      last = command;
      a.prefs.text("plugStatus", "충전 " + command + " 요청 수락 · 실제 플러그 상태 미확인");
    } catch (Exception e) {
      a.prefs.text("plugStatus", "충전 요청 실패 · 플러그 자체 복구 일정을 확인하세요.");
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  public static URL validate(String address) throws Exception {
    URL u = new URL(address);
    String h = u.getHost();
    boolean local =
        h.matches("10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")
            || h.matches("192\\.168\\.\\d{1,3}\\.\\d{1,3}")
            || h.matches("172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}");
    if (u.getUserInfo() != null
        || u.getRef() != null
        || h.isEmpty()
        || (!u.getProtocol().equals("https") && !(u.getProtocol().equals("http") && local)))
      throw new IllegalArgumentException("HTTPS 또는 내부망 IP의 HTTP 웹훅만 지원합니다.");
    return u;
  }
}
