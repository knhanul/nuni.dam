package co.kr.nuni.dam;

import android.content.*;
import java.util.Calendar;
import org.json.*;

public final class Prefs {
  public final SharedPreferences raw;

  public Prefs(Context c) {
    raw = c.getSharedPreferences("nunidam", 0);
  }

  public String mode() {
    return raw.getString("mode", "MAGAZINE");
  }

  public String theme() {
    return raw.getString("theme", "MILK");
  }

  public int seconds() {
    return raw.getInt("seconds", 45);
  }

  public int quota() {
    return raw.getInt("quota", 1024);
  }

  public boolean low() {
    return raw.getBoolean("low", false);
  }

  public boolean animate() {
    return raw.getBoolean("animate", true) && !low();
  }

  public boolean ai() {
    return raw.getBoolean("ai", true);
  }

  public int brightness() {
    return raw.getInt("brightness", 65);
  }

  public int start() {
    return raw.getInt("nightStart", 1320);
  }

  public int end() {
    return raw.getInt("nightEnd", 420);
  }

  public String folder() {
    return raw.getString("folder", "");
  }

  public String phrase() {
    return raw.getString("phrase", "");
  }

  public String quoteKind() {
    return raw.getString("quoteKind", "ALL");
  }

  public String quoteCycle() {
    return raw.getString("quoteCycle", "PHOTO");
  }

  public boolean quoteOnPhoto() {
    return raw.getBoolean("quoteOnPhoto", true);
  }

  public boolean showClock() {
    return raw.getBoolean("showClock", true);
  }

  public boolean showCaptureDate() {
    return raw.getBoolean("showCaptureDate", true);
  }

  public int fontScale() {
    return raw.getInt("fontScale", 100);
  }

  // --- Remote Management & Image Storage Settings (Sections 3 & 4.2) ---

  public boolean remoteEnabled() {
    return raw.getBoolean("remoteEnabled", false);
  }

  public int remotePort() {
    return raw.getInt("remotePort", 8787);
  }

  public String adminPinHash() {
    return raw.getString("adminPinHash", "");
  }

  public String adminPinSalt() {
    String s = raw.getString("adminPinSalt", "");
    if (s.isEmpty()) {
      s = new Core.Token(0, 1000).value.substring(0, 16);
      raw.edit().putString("adminPinSalt", s).apply();
    }
    return s;
  }

  public void setAdminPin(String plainPin) {
    if (plainPin == null || plainPin.trim().isEmpty()) {
      raw.edit().putString("adminPinHash", "").apply();
    } else {
      String salt = adminPinSalt();
      raw.edit().putString("adminPinHash", Core.hashSecret(plainPin.trim(), salt)).apply();
    }
  }

  public boolean verifyAdminPin(String plainPin) {
    String hash = adminPinHash();
    if (hash.isEmpty()) return true;
    if (plainPin == null) return false;
    String candidate = Core.hashSecret(plainPin.trim(), adminPinSalt());
    return java.security.MessageDigest.isEqual(
        hash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        candidate.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  }

  public boolean keepOriginals() {
    return raw.getBoolean("keepOriginals", false);
  }

  public boolean useOptimizedImages() {
    return raw.getBoolean("useOptimizedImages", true);
  }

  public long lastRemoteAccessAt() {
    return raw.getLong("lastRemoteAccessAt", 0L);
  }

  public void recordRemoteAccess(long ts) {
    raw.edit().putLong("lastRemoteAccessAt", ts).apply();
  }

  public boolean studioScheduleEnabled() {
    return raw.getBoolean("studioScheduleEnabled", true);
  }

  // Power saving / motion settings (default OFF on new install per Section 7.3)
  public boolean motionEnabled() {
    return raw.getBoolean("motionEnabled", false);
  }

  public int idleMinutes() {
    return raw.getInt("idleMinutes", 5);
  }

  public String idleAction() {
    return raw.getString("idleAction", "DIM");
  }

  public boolean nightReact() {
    return raw.getBoolean("nightReact", false);
  }

  public int motionSensitivity() {
    return raw.getInt("motionSensitivity", 2);
  }

  public float roiLeft() {
    return raw.getFloat("roiLeft", 0.1f);
  }

  public float roiTop() {
    return raw.getFloat("roiTop", 0.1f);
  }

  public float roiRight() {
    return raw.getFloat("roiRight", 0.9f);
  }

  public float roiBottom() {
    return raw.getFloat("roiBottom", 0.9f);
  }

  public void saveRoi(float l, float t, float r, float b) {
    raw.edit()
        .putFloat("roiLeft", Math.max(0f, Math.min(0.8f, l)))
        .putFloat("roiTop", Math.max(0f, Math.min(0.8f, t)))
        .putFloat("roiRight", Math.max(l + 0.15f, Math.min(1f, r)))
        .putFloat("roiBottom", Math.max(t + 0.15f, Math.min(1f, b)))
        .apply();
  }

  public boolean night() {
    Calendar c = Calendar.getInstance();
    return raw.getBoolean("night", true)
        && Core.night(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), start(), end());
  }

  public void text(String key, String v) {
    raw.edit().putString(key, v).apply();
  }

  public void flag(String key, boolean v) {
    raw.edit().putBoolean(key, v).apply();
  }

  public void number(String key, int v) {
    raw.edit().putInt(key, v).apply();
  }

  public JSONObject exportJson() throws JSONException {
    return new JSONObject()
        .put("schemaVersion", 2)
        .put("mode", mode())
        .put("theme", theme())
        .put("seconds", seconds())
        .put("quota", quota())
        .put("brightness", brightness())
        .put("nightStart", start())
        .put("nightEnd", end())
        .put("phrase", phrase())
        .put("low", low())
        .put("animate", raw.getBoolean("animate", true))
        .put("ai", ai())
        .put("night", raw.getBoolean("night", true))
        .put("favourites", raw.getBoolean("favourites", false))
        .put("memories", raw.getBoolean("memories", true))
        .put("quoteKind", quoteKind())
        .put("quoteCycle", quoteCycle())
        .put("quoteOnPhoto", quoteOnPhoto())
        .put("showClock", showClock())
        .put("showCaptureDate", showCaptureDate())
        .put("fontScale", fontScale())
        .put("motionEnabled", motionEnabled())
        .put("idleMinutes", idleMinutes())
        .put("idleAction", idleAction())
        .put("nightReact", nightReact())
        .put("motionSensitivity", motionSensitivity());
  }

  public void restore(JSONObject o) throws Exception {
    int ver = o.getInt("schemaVersion");
    if (ver != 1 && ver != 2) throw new IllegalArgumentException("지원하지 않는 설정 버전입니다.");
    String mode = o.getString("mode"),
        theme = o.getString("theme"),
        phrase = o.getString("phrase"),
        quoteKind = o.optString("quoteKind", "ALL"),
        quoteCycle = o.optString("quoteCycle", "PHOTO"),
        idleAction = o.optString("idleAction", "DIM");
    if (!mode.matches("PHOTO|MAGAZINE|BOARD")
        || !theme.matches("MILK|INK|SAGE")
        || !quoteKind.matches("ALL|QUOTE|POEM|NONE")
        || !quoteCycle.matches("PHOTO|MINUTES_15|HOUR|DAY")
        || !idleAction.matches("DIM|SCREEN_OFF")
        || phrase.length() > 400) throw new IllegalArgumentException("화면 설정 값을 확인하세요.");
    int secs = range(o, "seconds", 10, 3600),
        q = range(o, "quota", 128, 16384),
        b = range(o, "brightness", 10, 100),
        s = range(o, "nightStart", 0, 1439),
        e = range(o, "nightEnd", 0, 1439),
        fs = o.has("fontScale") ? range(o, "fontScale", 85, 160) : 100,
        im = o.has("idleMinutes") ? range(o, "idleMinutes", 1, 60) : 5,
        ms = o.has("motionSensitivity") ? range(o, "motionSensitivity", 1, 3) : 2;
    boolean low = o.getBoolean("low"),
        animate = o.getBoolean("animate"),
        ai = o.getBoolean("ai"),
        night = o.getBoolean("night"),
        f = o.optBoolean("favourites", false),
        m = o.optBoolean("memories", true),
        qp = o.optBoolean("quoteOnPhoto", true),
        sc = o.optBoolean("showClock", true),
        sd = o.optBoolean("showCaptureDate", true),
        me = o.optBoolean("motionEnabled", false),
        nr = o.optBoolean("nightReact", false);
    // Note: Device-specific verification flags (such as screenOffWakeVerified or camera permissions)
    // are intentionally NOT trusted or copied from JSON backup per Section 9.
    raw.edit()
        .putString("mode", mode)
        .putString("theme", theme)
        .putString("phrase", phrase)
        .putString("quoteKind", quoteKind)
        .putString("quoteCycle", quoteCycle)
        .putString("idleAction", idleAction)
        .putInt("seconds", secs)
        .putInt("quota", q)
        .putInt("brightness", b)
        .putInt("nightStart", s)
        .putInt("nightEnd", e)
        .putInt("fontScale", fs)
        .putInt("idleMinutes", im)
        .putInt("motionSensitivity", ms)
        .putBoolean("low", low)
        .putBoolean("animate", animate)
        .putBoolean("ai", ai)
        .putBoolean("night", night)
        .putBoolean("favourites", f)
        .putBoolean("memories", m)
        .putBoolean("quoteOnPhoto", qp)
        .putBoolean("showClock", sc)
        .putBoolean("showCaptureDate", sd)
        .putBoolean("motionEnabled", me)
        .putBoolean("nightReact", nr)
        .apply();
  }

  private static int range(JSONObject o, String k, int min, int max) throws Exception {
    Object n = o.get(k);
    if (!(n instanceof Number) || ((Number) n).doubleValue() != ((Number) n).intValue())
      throw new IllegalArgumentException(k + "는 정수여야 합니다.");
    int v = ((Number) n).intValue();
    if (v < min || v > max) throw new IllegalArgumentException(k + " 범위를 확인하세요.");
    return v;
  }
}
