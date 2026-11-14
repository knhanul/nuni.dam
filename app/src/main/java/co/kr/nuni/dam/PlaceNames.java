package co.kr.nuni.dam;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 촬영지 지명을 사람이 읽기 쉬운 한국어 형식으로 만들고, 좌표 캐시 키를 만든다. */
public final class PlaceNames {
  private PlaceNames() {}

  /** 지오코더·Nominatim 결과를 담는 순수 자료. 단위 테스트에서 Address 없이 쓴다. */
  public static final class Parts {
    public String countryCode = "";
    public String country = "";
    public String admin = "";
    public String subAdmin = "";
    public String locality = "";
    public String subLocality = "";

    public Parts() {}

    public Parts(
        String countryCode,
        String country,
        String admin,
        String subAdmin,
        String locality,
        String subLocality) {
      this.countryCode = nz(countryCode);
      this.country = nz(country);
      this.admin = nz(admin);
      this.subAdmin = nz(subAdmin);
      this.locality = nz(locality);
      this.subLocality = nz(subLocality);
    }
  }

  public static String format(Parts p) {
    if (p == null) return "";
    if (isKorea(p)) return formatKorea(p);
    return formatAbroad(p);
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

  static String formatKorea(Parts p) {
    String admin = shortenKoreaAdmin(first(p.admin, p.locality));
    String city = koreaCity(p, admin);
    if (!admin.isEmpty() && !city.isEmpty() && !city.equals(admin)) return admin + " " + city;
    if (!city.isEmpty()) return city;
    return admin;
  }

  static String formatAbroad(Parts p) {
    String country = first(p.country);
    if (isKorea(p)) country = ""; // should not reach
    String city = first(p.locality, p.subAdmin, p.subLocality);
    if (city.isEmpty() && !nz(p.admin).equals(country)) city = first(p.admin);
    if (!country.isEmpty() && !city.isEmpty() && !city.equals(country)) {
      return country + " " + city;
    }
    if (!city.isEmpty()) return city;
    return country;
  }

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

  private static String koreaCity(Parts p, String shortAdmin) {
    String admin = nz(p.admin);
    for (String cand : new String[] {p.locality, p.subAdmin, p.subLocality}) {
      String t = nz(cand);
      if (t.isEmpty()) continue;
      if (!admin.isEmpty() && t.startsWith(admin)) t = t.substring(admin.length()).trim();
      if (!shortAdmin.isEmpty() && t.startsWith(shortAdmin)) {
        t = t.substring(shortAdmin.length()).trim();
      }
      if (t.isEmpty() || t.equals(admin) || t.equals(shortAdmin)) continue;
      if (isKoreaCountryName(t)) continue;
      return t;
    }
    return "";
  }

  private static boolean isKoreaCountryName(String s) {
    return "대한민국".equals(s)
        || "한국".equals(s)
        || "Korea".equalsIgnoreCase(s)
        || "South Korea".equalsIgnoreCase(s);
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
