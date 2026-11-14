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
import java.util.concurrent.atomic.AtomicReference;
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
      fillFromCache(a);
    } catch (Exception ignored) {
    }
    if (hasPending(a)) GeocodeWorker.schedule(a);
  }

  public static void afterImport(NuniApp a, Photo p) {
    if (p == null) return;
    if (!p.hasGps) tryFillGps(a, p);
    if (!p.hasGps) return;
    applyCachedName(a, p);
    try {
      a.db.photos().update(p);
    } catch (Exception ignored) {
    }
    if (p.placeName.isEmpty()) GeocodeWorker.schedule(a);
  }

  public static void tryFillGps(NuniApp a, Photo p) {
    if (p == null || p.hasGps) return;
    ExifInterface exif = openExif(a, p);
    if (exif == null) return;
    applyExif(p, exif);
    if (!p.hasGps) return;
    applyCachedName(a, p);
    try {
      a.db.photos().update(p);
    } catch (Exception ignored) {
    }
    if (p.placeName.isEmpty()) GeocodeWorker.schedule(a);
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
      ExifInterface exif = openExif(a, p);
      if (exif == null) continue;
      applyExif(p, exif);
      if (p.hasGps) {
        try {
          a.db.photos().update(p);
        } catch (Exception ignored) {
        }
      }
    }
    a.prefs.flag("gpsExifMigrated", true);
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
      return true;
    }
    String name = lookup(a, p.lat, p.lon);
    p.geocodedAt = System.currentTimeMillis();
    if (name != null && !name.isEmpty()) {
      p.placeName = name;
      remember(a, p.lat, p.lon, name);
    }
    try {
      a.db.photos().update(p);
    } catch (Exception ignored) {
    }
    return name != null && !name.isEmpty();
  }

  public static String lookup(Context c, double lat, double lon) {
    if (!valid(lat, lon)) return "";
    String geocoderName = lookupGeocoder(c, lat, lon);
    if (geocoderName != null && !geocoderName.isEmpty()) return geocoderName;
    return lookupNominatim(c, lat, lon);
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

  private static String lookupGeocoder(Context c, double lat, double lon) {
    try {
      if (!Geocoder.isPresent()) return "";
      Geocoder geocoder = new Geocoder(c, Locale.KOREAN);
      List<Address> list;
      if (Build.VERSION.SDK_INT >= 33) {
        list = geocodeAsync(geocoder, lat, lon);
      } else {
        list = geocoder.getFromLocation(lat, lon, 1);
      }
      if (list == null || list.isEmpty()) return "";
      return PlaceNames.format(fromAddress(list.get(0)));
    } catch (Exception e) {
      return "";
    }
  }

  private static List<Address> geocodeAsync(Geocoder geocoder, double lat, double lon)
      throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<List<Address>> ref = new AtomicReference<>();
    geocoder.getFromLocation(
        lat,
        lon,
        1,
        new Geocoder.GeocodeListener() {
          @Override
          public void onGeocode(@androidx.annotation.NonNull List<Address> addresses) {
            ref.set(addresses);
            latch.countDown();
          }

          @Override
          public void onError(String errorMessage) {
            latch.countDown();
          }
        });
    latch.await(10, TimeUnit.SECONDS);
    return ref.get();
  }

  private static String lookupNominatim(Context c, double lat, double lon) {
    synchronized (NOMINATIM_LOCK) {
      long wait = 1000 - (System.currentTimeMillis() - lastNominatimAt);
      if (wait > 0) {
        try {
          Thread.sleep(wait);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return "";
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
        if (in == null || code < 200 || code >= 300) return "";
        byte[] raw = readLimited(in, 256 * 1024);
        JSONObject json = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        return PlaceNames.format(fromNominatim(json));
      } catch (Exception e) {
        return "";
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

  private static ExifInterface openExif(NuniApp a, Photo p) {
    try {
      File orig = a.store.originalFile(p.id);
      if (orig.isFile()) return new ExifInterface(orig);
    } catch (Exception ignored) {
    }
    if (p.publicUri != null && !p.publicUri.isEmpty()) {
      ExifInterface fromUri = exifFromUri(a, p.publicUri);
      if (fromUri != null) return fromUri;
    }
    try {
      Uri existing = PublicArchive.findExisting(a, p.id);
      if (existing != null) {
        ExifInterface fromUri = exifFromUri(a, existing.toString());
        if (fromUri != null) return fromUri;
      }
    } catch (Exception ignored) {
    }
    try {
      File f = a.store.file(p.id);
      if (f.isFile()) return new ExifInterface(f);
    } catch (Exception ignored) {
    }
    return null;
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
}
