package co.kr.nuni.dam;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** 촬영지 지명을 사람이 읽기 쉬운 한국어 형식으로 만들고, 좌표 캐시 키를 만든다. */
public final class PlaceNames {
  private PlaceNames() {}

  /** 지역 표시 범위. 앨범 값은 FOLLOW(전체 설정 따르기)를 쓸 수 있다. */
  public enum Scope {
    FOLLOW,
    SIDO,
    SIGUNGU,
    DONG;

    public static final Scope DEFAULT = DONG;
    public static final Scope ALBUM_DEFAULT = FOLLOW;

    public static Scope parse(String raw, Scope fallback) {
      if (raw == null) return fallback;
      String s = raw.trim().toUpperCase(Locale.ROOT);
      if (s.isEmpty()) return fallback;
      try {
        return Scope.valueOf(s);
      } catch (IllegalArgumentException e) {
        return fallback;
      }
    }

    /** 앨범 설정. 비어 있거나 알 수 없으면 FOLLOW. */
    public static Scope parseAlbum(String raw) {
      return parse(raw, ALBUM_DEFAULT);
    }

    /** 전체 설정. FOLLOW나 알 수 없는 값은 동·읍·면. */
    public static Scope parseGlobal(String raw) {
      Scope s = parse(raw, DEFAULT);
      return s == FOLLOW ? DEFAULT : s;
    }

    /** 앨범 값이 있으면 그걸, 없으면 전체 설정을 쓴다. */
    public static Scope resolve(String albumScope, String globalScope) {
      Scope album = parseAlbum(albumScope);
      if (album != FOLLOW) return album;
      return parseGlobal(globalScope);
    }

    public static String normalizeAlbum(String raw) {
      return parseAlbum(raw).name();
    }

    public static String normalizeGlobal(String raw) {
      return parseGlobal(raw).name();
    }
  }

  /** 지오코더·Nominatim 결과를 담는 순수 자료. 단위 테스트에서 Address 없이 쓴다. */
  public static final class Parts {
    public String countryCode = "";
    public String country = "";
    public String admin = "";
    public String subAdmin = "";
    public String locality = "";
    public String subLocality = "";
    public String thoroughfare = "";
    public String feature = "";

    public Parts() {}

    public Parts(
        String countryCode,
        String country,
        String admin,
        String subAdmin,
        String locality,
        String subLocality) {
      this(countryCode, country, admin, subAdmin, locality, subLocality, "", "");
    }

    public Parts(
        String countryCode,
        String country,
        String admin,
        String subAdmin,
        String locality,
        String subLocality,
        String thoroughfare,
        String feature) {
      this.countryCode = nz(countryCode);
      this.country = nz(country);
      this.admin = nz(admin);
      this.subAdmin = nz(subAdmin);
      this.locality = nz(locality);
      this.subLocality = nz(subLocality);
      this.thoroughfare = nz(thoroughfare);
      this.feature = nz(feature);
    }

    public boolean isEmpty() {
      return countryCode.isEmpty()
          && country.isEmpty()
          && admin.isEmpty()
          && subAdmin.isEmpty()
          && locality.isEmpty()
          && subLocality.isEmpty()
          && thoroughfare.isEmpty()
          && feature.isEmpty();
    }

    public String toJson() {
      return "{"
          + field("countryCode", countryCode)
          + ","
          + field("country", country)
          + ","
          + field("admin", admin)
          + ","
          + field("subAdmin", subAdmin)
          + ","
          + field("locality", locality)
          + ","
          + field("subLocality", subLocality)
          + ","
          + field("thoroughfare", thoroughfare)
          + ","
          + field("feature", feature)
          + "}";
    }

    public static Parts fromJson(String json) {
      if (json == null) return null;
      String s = json.trim();
      if (s.isEmpty() || s.charAt(0) != '{') return null;
      Parts p = new Parts();
      p.countryCode = jsonString(s, "countryCode");
      p.country = jsonString(s, "country");
      p.admin = jsonString(s, "admin");
      p.subAdmin = jsonString(s, "subAdmin");
      p.locality = jsonString(s, "locality");
      p.subLocality = jsonString(s, "subLocality");
      p.thoroughfare = jsonString(s, "thoroughfare");
      p.feature = jsonString(s, "feature");
      return p.isEmpty() ? null : p;
    }

    private static String field(String key, String value) {
      return "\"" + key + "\":\"" + escape(value) + "\"";
    }

    private static String escape(String v) {
      String s = nz(v);
      return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String jsonString(String json, String key) {
      String needle = "\"" + key + "\"";
      int i = json.indexOf(needle);
      if (i < 0) return "";
      int colon = json.indexOf(':', i + needle.length());
      if (colon < 0) return "";
      int q = colon + 1;
      while (q < json.length() && Character.isWhitespace(json.charAt(q))) q++;
      if (q >= json.length() || json.charAt(q) != '"') return "";
      StringBuilder out = new StringBuilder();
      boolean esc = false;
      for (int k = q + 1; k < json.length(); k++) {
        char c = json.charAt(k);
        if (esc) {
          if (c == 'n') out.append('\n');
          else if (c == 'r') out.append('\r');
          else out.append(c);
          esc = false;
        } else if (c == '\\') {
          esc = true;
        } else if (c == '"') {
          break;
        } else {
          out.append(c);
        }
      }
      return out.toString().trim();
    }
  }

  public static String format(Parts p) {
    return format(p, Scope.DEFAULT);
  }

  public static String format(Parts p, Scope scope) {
    if (p == null) return "";
    Scope s = scope == null || scope == Scope.FOLLOW ? Scope.DEFAULT : scope;
    if (isKorea(p)) return formatKorea(p, s);
    return formatAbroad(p, s);
  }

  /** 구성 요소가 있으면 범위에 맞춰 다시 만들고, 없으면 예전에 저장해 둔 이름만 보여 준다. */
  public static String display(Parts p, String storedName, Scope scope) {
    String formatted = format(p, scope);
    if (!formatted.isEmpty()) return formatted;
    return nz(storedName);
  }

  public static String display(String partsJson, String storedName, Scope scope) {
    return display(Parts.fromJson(partsJson), storedName, scope);
  }

  public static String formatDate(long capturedAt) {
    if (capturedAt <= 0) return "";
    SimpleDateFormat f = new SimpleDateFormat("yyyy년 M월 d일", Locale.KOREA);
    return f.format(new Date(capturedAt));
  }

  public static String caption(
      long capturedAt, String placeName, boolean showDate, boolean showPlace) {
    String date = showDate ? formatDate(capturedAt) : "";
    if (showDate && date.isEmpty()) date = "소중한 순간";
    String place = showPlace ? nz(placeName) : "";
    if (!date.isEmpty() && !place.isEmpty()) return date + " · " + place;
    if (!date.isEmpty()) return date;
    return place;
  }

  public static double roundCoord(double value) {
    return Math.round(value * 1000.0) / 1000.0;
  }

  public static String cacheKey(double lat, double lon) {
    return String.format(Locale.US, "%.3f,%.3f", roundCoord(lat), roundCoord(lon));
  }

  static boolean isKorea(Parts p) {
    String code = nz(p.countryCode).toUpperCase(Locale.ROOT);
    if ("KR".equals(code) || "KOR".equals(code)) return true;
    String c = nz(p.country);
    return c.contains("대한민국")
        || c.equals("한국")
        || c.equalsIgnoreCase("Korea")
        || c.equalsIgnoreCase("South Korea")
        || c.equalsIgnoreCase("Republic of Korea");
  }

  static String formatKorea(Parts p, Scope scope) {
    KoreaBits bits = koreaBits(p);
    List<String> parts = new ArrayList<>();
    addUnique(parts, bits.sido);
    if (scope == Scope.SIDO) return join(parts);
    addUnique(parts, bits.city);
    addUnique(parts, bits.district);
    if (scope == Scope.SIGUNGU) return join(parts);
    addUnique(parts, bits.town);
    return join(parts);
  }

  static String formatAbroad(Parts p, Scope scope) {
    String country = first(p.country);
    if (isKorea(p)) country = "";
    String city = first(p.locality, p.subAdmin);
    if (city.isEmpty() && !nz(p.admin).equals(country)) city = first(p.admin);
    if (!city.isEmpty() && city.equals(country)) city = "";
    String district = first(p.subLocality);
    if (!district.isEmpty() && (district.equals(city) || district.equals(country))) district = "";
    List<String> parts = new ArrayList<>();
    addUnique(parts, country);
    if (scope == Scope.SIDO) return join(parts);
    addUnique(parts, city);
    if (scope == Scope.SIGUNGU) return join(parts);
    addUnique(parts, district);
    return join(parts);
  }

  static final class KoreaBits {
    String sido = "";
    String city = "";
    String district = "";
    String town = "";
  }

  static KoreaBits koreaBits(Parts p) {
    KoreaBits b = new KoreaBits();
    if (p == null) return b;
    List<String> tokens = new ArrayList<>();
    collectTokens(tokens, p.admin);
    collectTokens(tokens, p.subAdmin);
    collectTokens(tokens, p.locality);
    collectTokens(tokens, p.subLocality);
    collectTokens(tokens, dongCandidate(p.thoroughfare));
    collectTokens(tokens, dongCandidate(p.feature));
    for (String t : tokens) {
      if (t.isEmpty() || isKoreaCountryName(t)) continue;
      if (b.sido.isEmpty() && isSido(t)) {
        b.sido = t;
        continue;
      }
      if (isSido(t)) continue;
      if (b.city.isEmpty() && isCity(t) && !t.equals(b.sido)) {
        b.city = t;
        continue;
      }
      if (b.district.isEmpty() && isDistrict(t) && !t.equals(b.city) && !t.equals(b.sido)) {
        b.district = t;
        continue;
      }
      if (b.town.isEmpty() && isTown(t) && !t.equals(b.district) && !t.equals(b.city)) {
        b.town = t;
      }
    }
    if (b.sido.isEmpty()) {
      String admin = first(p.admin, p.locality);
      if (!admin.isEmpty() && !isKoreaCountryName(admin) && !isTown(admin) && !isDistrict(admin)) {
        b.sido = admin;
      }
    }
    return b;
  }

  private static void collectTokens(List<String> out, String raw) {
    String s = nz(raw);
    if (s.isEmpty()) return;
    for (String piece : s.split("[\\s,/]+")) {
      String t = nz(piece);
      if (t.isEmpty() || isStreetOrNumber(t)) continue;
      out.add(t);
    }
  }

  /** 도로명·번지는 쓰지 않고, 동·읍·면처럼 보이는 값만 남긴다. */
  static String dongCandidate(String raw) {
    String s = nz(raw);
    if (s.isEmpty() || isStreetOrNumber(s)) return "";
    if (isTown(s)) return s;
    String last = s;
    int sp = s.lastIndexOf(' ');
    if (sp >= 0) last = s.substring(sp + 1).trim();
    if (isTown(last) && !isStreetOrNumber(last)) return last;
    return "";
  }

  static boolean isStreetOrNumber(String s) {
    String t = nz(s);
    if (t.isEmpty()) return true;
    if (t.endsWith("번지") || t.endsWith("번길") || t.endsWith("대로") || t.endsWith("로") || t.endsWith("길")) {
      return true;
    }
    if (t.matches(".*\\d.*") && !t.matches(".*[동읍면]$")) return true;
    return false;
  }

  static boolean isSido(String s) {
    String t = nz(s);
    if (t.isEmpty()) return false;
    if (t.endsWith("특별자치도")
        || t.endsWith("특별자치시")
        || t.endsWith("광역시")
        || t.endsWith("특별시")) {
      return true;
    }
    if (t.endsWith("도") && t.length() >= 2) return true;
    String[] bare = {
      "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "경기", "강원", "충북", "충남",
      "전북", "전남", "경북", "경남", "제주"
    };
    for (String n : bare) if (t.equals(n)) return true;
    return false;
  }

  static boolean isCity(String s) {
    String t = nz(s);
    if (t.isEmpty() || isSido(t)) return false;
    return t.endsWith("시") || t.endsWith("군");
  }

  static boolean isDistrict(String s) {
    String t = nz(s);
    return t.endsWith("구") && t.length() >= 2 && !isSido(t);
  }

  static boolean isTown(String s) {
    String t = nz(s);
    if (t.length() < 2) return false;
    return t.endsWith("동") || t.endsWith("읍") || t.endsWith("면");
  }

  private static void addUnique(List<String> parts, String value) {
    String t = nz(value);
    if (t.isEmpty()) return;
    for (String e : parts) if (e.equals(t)) return;
    parts.add(t);
  }

  private static String join(List<String> parts) {
    if (parts == null || parts.isEmpty()) return "";
    LinkedHashSet<String> uniq = new LinkedHashSet<>();
    for (String p : parts) {
      String t = nz(p);
      if (!t.isEmpty()) uniq.add(t);
    }
    StringBuilder sb = new StringBuilder();
    for (String p : uniq) {
      if (sb.length() > 0) sb.append(' ');
      sb.append(p);
    }
    return sb.toString();
  }

  // kept for older call sites / tests that still mention shortened admins
  static String shortenKoreaAdmin(String admin) {
    String s = nz(admin);
    if (s.isEmpty()) return "";
    String[][] map =
        new String[][] {
          {"서울", "서울"},
          {"부산", "부산"},
          {"대구", "대구"},
          {"인천", "인천"},
          {"광주", "광주"},
          {"대전", "대전"},
          {"울산", "울산"},
          {"세종", "세종"},
          {"경기", "경기"},
          {"강원", "강원"},
          {"충청북", "충북"},
          {"충북", "충북"},
          {"충청남", "충남"},
          {"충남", "충남"},
          {"전라북", "전북"},
          {"전북", "전북"},
          {"전라남", "전남"},
          {"전남", "전남"},
          {"경상북", "경북"},
          {"경북", "경북"},
          {"경상남", "경남"},
          {"경남", "경남"},
          {"제주", "제주"}
        };
    for (String[] row : map) {
      if (s.startsWith(row[0])) return row[1];
    }
    s = s.replace("특별자치도", "").replace("특별자치시", "").replace("광역시", "").replace("특별시", "");
    if (s.endsWith("도") && s.length() > 1) s = s.substring(0, s.length() - 1);
    if (s.endsWith("시") && s.length() > 1 && s.length() <= 3) s = s.substring(0, s.length() - 1);
    return s.trim();
  }

  private static boolean isKoreaCountryName(String s) {
    return "대한민국".equals(s)
        || "한국".equals(s)
        || "Korea".equalsIgnoreCase(s)
        || "South Korea".equalsIgnoreCase(s)
        || "Republic of Korea".equalsIgnoreCase(s);
  }

  private static String first(String... values) {
    if (values == null) return "";
    for (String v : values) {
      String t = nz(v);
      if (!t.isEmpty()) return t;
    }
    return "";
  }

  private static String nz(String s) {
    return s == null ? "" : s.trim();
  }
}
