package co.kr.nuni.dam;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import androidx.exifinterface.media.ExifInterface;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** 사진 EXIF에서 좌표를 읽고, 지명을 찾아 캐시와 Photo 행에 저장한다. 재생은 기다리지 않는다. */
public final class Places {
  private static final Object NOMINATIM_LOCK = new Object();
  private static long lastNominatimAt;
  private static final long RETRY_GRACE_MS = 6L * 60 * 60 * 1000;

  private Places() {}

  public static void onAppStart(NuniApp a) {
    try {
      scanExistingGps(a);
      rescanForPlaceLog(a);
      fillFromCache(a);
    } catch (Exception ignored) {
    }
    if (!hasNetwork(a)) logPendingAll(a);
    if (hasPending(a)) GeocodeWorker.schedule(a);
  }

  public static void afterImport(NuniApp a, Photo p) {
    if (p == null) return;
    if (!p.hasGps) tryFillGps(a, p);
    else followUpPlace(a, p);
  }

  public static void tryFillGps(NuniApp a, Photo p) {
    if (p == null || p.hasGps) return;
    GpsProbe probe = probeGps(a, p);
    if (!probe.found || probe.exif == null) {
      PlaceLog.record(a, p, "NO_GPS", probe.sourcesNote());
      return;
    }
    applyExif(p, probe.exif);
    if (!p.hasGps) {
      PlaceLog.record(a, p, "NO_GPS", probe.sourcesNote());
      return;
    }
    followUpPlace(a, p);
  }

  private static void followUpPlace(NuniApp a, Photo p) {
    if (p == null || !p.hasGps) return;
    boolean fromCache = applyCachedName(a, p);
    try {
      a.db.photos().update(p);
    } catch (Exception ignored) {
    }
    if (!p.placeName.isEmpty()) {
      PlaceLog.record(
          a,
          p,
          "FOUND",
          PlaceLog.coords(p.lat, p.lon)
              + " "
              + p.placeName
              + (fromCache ? " via=cache" : " via=stored"));
      return;
    }
    if (!hasNetwork(a)) {
      PlaceLog.record(a, p, "PENDING", PlaceLog.coords(p.lat, p.lon));
    }
    GeocodeWorker.schedule(a);
  }

  private static void logPendingAll(NuniApp a) {
    try {
      for (Photo p : a.db.photos().needingPlaceName()) {
        if (p != null && p.hasGps && p.placeName.isEmpty()) {
          PlaceLog.record(a, p, "PENDING", PlaceLog.coords(p.lat, p.lon));
        }
      }
    } catch (Exception ignored) {
    }
  }

  public static void applyExif(Photo p, ExifInterface exif) {
    double[] gps = readGps(exif);
    if (gps == null || p == null) return;
    p.hasGps = true;
    p.lat = gps[0];
    p.lon = gps[1];
  }

  public static double[] readGps(ExifInterface exif) {
    if (exif == null) return null;
    try {
      double[] v = exif.getLatLong();
      if (v != null && v.length >= 2 && valid(v[0], v[1])) return new double[] {v[0], v[1]};
    } catch (Throwable ignored) {
    }
    try {
      float[] ll = new float[2];
      if (exif.getLatLong(ll) && valid(ll[0], ll[1])) return new double[] {ll[0], ll[1]};
    } catch (Exception ignored) {
    }
    return null;
  }

  public static boolean valid(double lat, double lon) {
    return !Double.isNaN(lat)
        && !Double.isNaN(lon)
        && lat >= -90
        && lat <= 90
        && lon >= -180
        && lon <= 180;
  }

  public static void scanExistingGps(NuniApp a) {
    if (a.prefs.raw.getBoolean("gpsExifMigrated", false)) return;
    List<Photo> all = new java.util.ArrayList<>();
    try {
      all.addAll(a.db.photos().active());
      all.addAll(a.db.photos().trash());
    } catch (Exception e) {
      return;
    }
    for (Photo p : all) {
      if (p == null || p.hasGps) continue;
      GpsProbe probe = probeGps(a, p);
      if (!probe.found || probe.exif == null) {
        PlaceLog.record(a, p, "NO_GPS", probe.sourcesNote());
        continue;
      }
      applyExif(p, probe.exif);
      if (p.hasGps) {
        try {
          a.db.photos().update(p);
        } catch (Exception ignored) {
        }
      } else {
        PlaceLog.record(a, p, "NO_GPS", probe.sourcesNote());
      }
    }
    a.prefs.flag("gpsExifMigrated", true);
  }

  static final String PREF_PLACE_LOG_RESCAN = "placeLogRescan1";

  /** 지명 로그가 생기기 전에 들어온 사진을 한 번 더 살펴 상태를 남긴다. */
  public static void rescanForPlaceLog(NuniApp a) {
    if (a.prefs.raw.getBoolean(PREF_PLACE_LOG_RESCAN, false)) return;
    List<Photo> all;
    try {
      all = a.db.photos().active();
    } catch (Exception e) {
      return;
    }
    boolean net = hasNetwork(a);
    for (Photo p : all) {
      if (p == null) continue;
      if (!p.hasGps) {
        GpsProbe probe = probeGps(a, p);
        if (probe.found && probe.exif != null) {
          applyExif(p, probe.exif);
          if (p.hasGps) {
            try {
              a.db.photos().update(p);
            } catch (Exception ignored) {
            }
          }
        }
        if (!p.hasGps) {
          PlaceLog.record(a, p, "NO_GPS", probe.sourcesNote());
          continue;
        }
      }
      if (p.placeName != null && !p.placeName.isEmpty()) {
        PlaceLog.record(
            a, p, "FOUND", PlaceLog.coords(p.lat, p.lon) + " " + p.placeName + " via=stored");
      } else {
        PlaceLog.record(
            a, p, "PENDING", PlaceLog.coords(p.lat, p.lon) + (net ? " net=on" : " net=off"));
      }
    }
    a.prefs.flag(PREF_PLACE_LOG_RESCAN, true);
  }

  public static String placeSummary(NuniApp a) {
    try {
      return PlaceLog.summaryLine(a.db.photos().active());
    } catch (Exception e) {
      return PlaceLog.summaryLine(0, 0, 0, 0, 0);
    }
  }

  /** 액자 재생 시 지명 상태를 남긴다. DB 필드만 쓰고, 호출 쪽은 백그라운드에서 부른다. */
  public static void logPlayback(Context c, Photo p, boolean showPlace, boolean network) {
    PlaybackOutcome o = playbackOutcome(p, showPlace, network);
    if (o == null) return;
    PlaceLog.record(c, p, o.result, o.detail);
  }

  static PlaybackOutcome playbackOutcome(Photo p, boolean showPlace, boolean network) {
    if (p == null || !showPlace) return null;
    String name = p.placeName == null ? "" : p.placeName;
    if (!name.isEmpty()) {
      String prefix = p.hasGps ? PlaceLog.coords(p.lat, p.lon) + " " : "";
      return new PlaybackOutcome("FOUND", prefix + name + " via=stored");
    }
    if (!p.hasGps) return new PlaybackOutcome("NO_GPS", "");
    String coords = PlaceLog.coords(p.lat, p.lon);
    if (p.geocodedAt > 0) return new PlaybackOutcome("FAILED", coords + " tried");
    return new PlaybackOutcome("PENDING", coords + (network ? " net=on" : " net=off"));
  }

  static final class PlaybackOutcome {
    final String result;
    final String detail;

    PlaybackOutcome(String result, String detail) {
      this.result = result;
      this.detail = detail == null ? "" : detail;
    }
  }

  public static void fillFromCache(NuniApp a) {
    List<Photo> list;
    try {
      list = a.db.photos().needingPlaceName();
    } catch (Exception e) {
      return;
    }
    for (Photo p : list) {
      if (applyCachedName(a, p)) {
        try {
          a.db.photos().update(p);
        } catch (Exception ignored) {
        }
        PlaceLog.record(
            a, p, "FOUND", PlaceLog.coords(p.lat, p.lon) + " " + p.placeName + " via=cache");
      }
    }
  }

  public static boolean applyCachedName(NuniApp a, Photo p) {
    if (p == null || !p.hasGps || !p.placeName.isEmpty()) return false;
    String name = cachedName(a, p.lat, p.lon);
    if (name == null || name.isEmpty()) return false;
    p.placeName = name;
    p.geocodedAt = System.currentTimeMillis();
    return true;
  }

  public static String cachedName(NuniApp a, double lat, double lon) {
    try {
      GeoCache row = a.db.geoCache().get(PlaceNames.cacheKey(lat, lon));
      if (row != null && row.placeName != null && !row.placeName.isEmpty()) return row.placeName;
    } catch (Exception ignored) {
    }
    return null;
  }

  public static void remember(NuniApp a, double lat, double lon, String name) {
    if (name == null || name.isEmpty()) return;
    GeoCache row = new GeoCache();
    row.key = PlaceNames.cacheKey(lat, lon);
    row.placeName = name;
    row.geocodedAt = System.currentTimeMillis();
    try {
      a.db.geoCache().upsert(row);
    } catch (Exception ignored) {
    }
  }

  /** 네트워크로 지명을 찾아 Photo와 캐시에 저장한다. 실패하면 false. */
  public static boolean lookupAndStore(NuniApp a, Photo p) {
    if (p == null || !p.hasGps) return true;
    if (applyCachedName(a, p)) {
      try {
        a.db.photos().update(p);
      } catch (Exception ignored) {
      }
      PlaceLog.record(
          a, p, "FOUND", PlaceLog.coords(p.lat, p.lon) + " " + p.placeName + " via=cache");
      return true;
    }
    if (!hasNetwork(a)) {
      PlaceLog.record(a, p, "PENDING", PlaceLog.coords(p.lat, p.lon));
      return false;
    }
    Lookup geo = lookupGeocoder(a, p.lat, p.lon);
    if (geo.ok()) {
      p.placeName = geo.name;
      p.geocodedAt = System.currentTimeMillis();
      remember(a, p.lat, p.lon, geo.name);
      try {
        a.db.photos().update(p);
      } catch (Exception ignored) {
      }
      PlaceLog.record(
          a, p, "FOUND", PlaceLog.coords(p.lat, p.lon) + " " + geo.name + " via=geocoder");
      return true;
    }
    PlaceLog.record(
        a, p, geo.kind, PlaceLog.coords(p.lat, p.lon) + (geo.detail.isEmpty() ? "" : " " + geo.detail));
    Lookup nom = lookupNominatim(a, p.lat, p.lon);
    p.geocodedAt = System.currentTimeMillis();
    if (nom.ok()) {
      p.placeName = nom.name;
      remember(a, p.lat, p.lon, nom.name);
    }
    try {
      a.db.photos().update(p);
    } catch (Exception ignored) {
    }
    if (nom.ok()) {
      PlaceLog.record(
          a, p, "FOUND", PlaceLog.coords(p.lat, p.lon) + " " + nom.name + " via=nominatim");
      return true;
    }
    PlaceLog.record(
        a, p, nom.kind, PlaceLog.coords(p.lat, p.lon) + (nom.detail.isEmpty() ? "" : " " + nom.detail));
    return false;
  }

  public static String lookup(Context c, double lat, double lon) {
    if (!valid(lat, lon)) return "";
    Lookup geo = lookupGeocoder(c, lat, lon);
    if (geo.ok()) return geo.name;
    return lookupNominatim(c, lat, lon).name;
  }

  static final class Lookup {
    final String name;
    final String kind;
    final String detail;

    Lookup(String name, String kind, String detail) {
      this.name = name == null ? "" : name;
      this.kind = kind == null ? "" : kind;
      this.detail = detail == null ? "" : detail;
    }

    boolean ok() {
      return !name.isEmpty();
    }

    static Lookup found(String name, String provider) {
      return new Lookup(name, "FOUND", "via=" + provider);
    }

    static Lookup empty(String kind, String detail) {
      return new Lookup("", kind, detail);
    }
  }

  public static boolean hasNetwork(Context c) {
    try {
      ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
      if (cm == null) return false;
      if (Build.VERSION.SDK_INT >= 23) {
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        return caps != null
            && (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
      }
      android.net.NetworkInfo info = cm.getActiveNetworkInfo();
      return info != null && info.isConnected();
    } catch (Exception e) {
      return false;
    }
  }

  static PlaceNames.Parts fromAddress(Address a) {
    if (a == null) return null;
    return new PlaceNames.Parts(
        a.getCountryCode(),
        a.getCountryName(),
        a.getAdminArea(),
        a.getSubAdminArea(),
        a.getLocality(),
        a.getSubLocality());
  }

  static PlaceNames.Parts fromNominatim(JSONObject root) {
    if (root == null) return null;
    JSONObject addr = root.optJSONObject("address");
    if (addr == null) addr = new JSONObject();
    String locality =
        firstNonEmpty(
            addr.optString("city", ""),
            addr.optString("town", ""),
            addr.optString("municipality", ""),
            addr.optString("county", ""));
    String sub =
        firstNonEmpty(
            addr.optString("city_district", ""),
            addr.optString("borough", ""),
            addr.optString("suburb", ""),
            addr.optString("district", ""));
    PlaceNames.Parts p =
        new PlaceNames.Parts(
            addr.optString("country_code", ""),
            addr.optString("country", ""),
            firstNonEmpty(addr.optString("state", ""), addr.optString("region", ""), addr.optString("province", "")),
            addr.optString("county", ""),
            locality,
            sub);
    if (PlaceNames.format(p).isEmpty()) {
      String display = root.optString("display_name", "").trim();
      if (!display.isEmpty()) {
        PlaceNames.Parts fallback = new PlaceNames.Parts();
        fallback.country = display;
        return fallback;
      }
    }
    return p;
  }

  private static Lookup lookupGeocoder(Context c, double lat, double lon) {
    try {
      if (!Geocoder.isPresent()) return Lookup.empty("GEOCODER_EMPTY", "absent");
      Geocoder geocoder = new Geocoder(c, Locale.KOREAN);
      List<Address> list;
      if (Build.VERSION.SDK_INT >= 33) {
        AsyncGeo attempt = geocodeAsync(geocoder, lat, lon);
        if ((attempt.list == null || attempt.list.isEmpty()) && attempt.error != null) {
          return Lookup.empty("GEOCODER_ERROR", attempt.error);
        }
        if (attempt.list == null) return Lookup.empty("GEOCODER_ERROR", "timeout");
        list = attempt.list;
      } else {
        list = geocoder.getFromLocation(lat, lon, 1);
      }
      if (list == null || list.isEmpty()) return Lookup.empty("GEOCODER_EMPTY", "");
      String name = PlaceNames.format(fromAddress(list.get(0)));
      if (name.isEmpty()) return Lookup.empty("GEOCODER_EMPTY", "unformatted");
      return Lookup.found(name, "geocoder");
    } catch (Exception e) {
      return Lookup.empty("GEOCODER_ERROR", errMsg(e));
    }
  }

  private static final class AsyncGeo {
    List<Address> list;
    String error;
  }

  private static AsyncGeo geocodeAsync(Geocoder geocoder, double lat, double lon)
      throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    AsyncGeo out = new AsyncGeo();
    geocoder.getFromLocation(
        lat,
        lon,
        1,
        new Geocoder.GeocodeListener() {
          @Override
          public void onGeocode(@androidx.annotation.NonNull List<Address> addresses) {
            out.list = addresses;
            latch.countDown();
          }

          @Override
          public void onError(String errorMessage) {
            out.error = errorMessage == null || errorMessage.isEmpty() ? "error" : errorMessage;
            latch.countDown();
          }
        });
    if (!latch.await(10, TimeUnit.SECONDS) && out.error == null) out.error = "timeout";
    return out;
  }

  private static Lookup lookupNominatim(Context c, double lat, double lon) {
    synchronized (NOMINATIM_LOCK) {
      long wait = 1000 - (System.currentTimeMillis() - lastNominatimAt);
      if (wait > 0) {
        try {
          Thread.sleep(wait);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return Lookup.empty("NOMINATIM_ERROR", "interrupted");
        }
      }
      lastNominatimAt = System.currentTimeMillis();
      HttpURLConnection conn = null;
      try {
        String url =
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat="
                + lat
                + "&lon="
                + lon
                + "&zoom=10&accept-language=ko";
        conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", userAgent(c));
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Accept-Language", "ko");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        if (in == null || code < 200 || code >= 300) {
          String msg = "";
          if (in != null) {
            try {
              msg = PlaceLog.sanitize(new String(readLimited(in, 512), StandardCharsets.UTF_8));
              if (msg.length() > 80) msg = msg.substring(0, 80);
            } catch (Exception ignored) {
            }
          }
          return Lookup.empty(
              "NOMINATIM_ERROR", "http=" + code + (msg.isEmpty() ? "" : " " + msg));
        }
        byte[] raw = readLimited(in, 256 * 1024);
        JSONObject json = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        String name = PlaceNames.format(fromNominatim(json));
        if (name.isEmpty()) return Lookup.empty("NOMINATIM_ERROR", "empty");
        return Lookup.found(name, "nominatim");
      } catch (Exception e) {
        return Lookup.empty("NOMINATIM_ERROR", errMsg(e));
      } finally {
        if (conn != null) conn.disconnect();
      }
    }
  }

  static String userAgent(Context c) {
    String ver = "0.1.0";
    try {
      ver = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
      if (ver == null || ver.isEmpty()) ver = "0.1.0";
    } catch (Exception ignored) {
    }
    return "NuniDam/" + ver + " (nuni.co.kr)";
  }

  private static byte[] readLimited(InputStream in, int max) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int n, total = 0;
    while ((n = in.read(buf)) > 0) {
      total += n;
      if (total > max) break;
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  static final class GpsProbe {
    ExifInterface exif;
    boolean found;
    final StringBuilder sources = new StringBuilder();

    void note(String label) {
      if (sources.length() > 0) sources.append(',');
      sources.append(label);
    }

    String sourcesNote() {
      return "sources=" + (sources.length() == 0 ? "none" : sources.toString());
    }

    void consider(String name, ExifInterface ex, String missingLabel) {
      if (found) {
        note(name + "(checked)");
        return;
      }
      if (ex == null) {
        note(name + "(" + missingLabel + ")");
        return;
      }
      if (Places.readGps(ex) != null) {
        exif = ex;
        found = true;
        note(name + "(gps)");
      } else {
        note(name + "(no_gps)");
      }
    }
  }

  static GpsProbe probeGps(NuniApp a, Photo p) {
    GpsProbe r = new GpsProbe();
    try {
      File orig = a.store.originalFile(p.id);
      if (orig.isFile()) r.consider("original", new ExifInterface(orig), "ok");
      else r.note("original(missing)");
    } catch (Exception e) {
      r.note("original(error)");
    }
    ExifInterface pub = null;
    String pubState = "missing";
    if (p.publicUri != null && !p.publicUri.isEmpty()) {
      pub = exifFromUri(a, p.publicUri);
      pubState = pub == null ? "unreadable" : "ok";
    }
    if (pub == null) {
      try {
        Uri existing = PublicArchive.findExisting(a, p.id);
        if (existing != null) {
          pub = exifFromUri(a, existing.toString());
          pubState = pub == null ? "unreadable" : "ok";
        }
      } catch (Exception ignored) {
      }
    }
    r.consider("public", pub, pubState);
    try {
      File f = a.store.file(p.id);
      if (f.isFile()) r.consider("internal", new ExifInterface(f), "ok");
      else r.note("internal(missing)");
    } catch (Exception e) {
      r.note("internal(error)");
    }
    return r;
  }

  private static ExifInterface exifFromUri(Context c, String uri) {
    try (InputStream in = c.getContentResolver().openInputStream(Uri.parse(uri))) {
      if (in == null) return null;
      return new ExifInterface(in);
    } catch (Exception e) {
      return null;
    }
  }

  private static boolean hasPending(NuniApp a) {
    try {
      for (Photo p : a.db.photos().needingPlaceName()) {
        if (p != null
            && p.hasGps
            && p.placeName.isEmpty()
            && (p.geocodedAt <= 0
                || System.currentTimeMillis() - p.geocodedAt >= RETRY_GRACE_MS)) {
          return true;
        }
      }
    } catch (Exception ignored) {
    }
    return false;
  }

  private static String firstNonEmpty(String... values) {
    for (String v : values) {
      if (v != null && !v.trim().isEmpty()) return v.trim();
    }
    return "";
  }

  static String errMsg(Throwable e) {
    if (e == null) return "";
    String m = e.getMessage();
    if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
    return PlaceLog.sanitize(m);
  }
}
