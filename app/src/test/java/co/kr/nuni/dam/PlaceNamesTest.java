package co.kr.nuni.dam;

import static org.junit.Assert.*;

import org.junit.Test;

public class PlaceNamesTest {
  private static PlaceNames.Parts kr(
      String admin, String subAdmin, String locality, String subLocality) {
    return new PlaceNames.Parts("KR", "대한민국", admin, subAdmin, locality, subLocality);
  }

  private static PlaceNames.Parts krFull(
      String admin,
      String subAdmin,
      String locality,
      String subLocality,
      String thoroughfare,
      String feature) {
    return new PlaceNames.Parts(
        "KR", "대한민국", admin, subAdmin, locality, subLocality, thoroughfare, feature);
  }

  @Test
  public void koreaJejuSeogwipo() {
    PlaceNames.Parts p = kr("제주특별자치도", "", "서귀포시", "");
    assertEquals("제주특별자치도 서귀포시", PlaceNames.format(p));
    assertEquals("제주특별자치도", PlaceNames.format(p, PlaceNames.Scope.SIDO));
    assertEquals("제주특별자치도 서귀포시", PlaceNames.format(p, PlaceNames.Scope.SIGUNGU));
    assertEquals("제주특별자치도 서귀포시", PlaceNames.format(p, PlaceNames.Scope.DONG));
  }

  @Test
  public void koreaGangwonSokcho() {
    PlaceNames.Parts p = kr("강원특별자치도", "", "속초시", "");
    assertEquals("강원특별자치도 속초시", PlaceNames.format(p));
  }

  @Test
  public void koreaSeoulDistrict() {
    PlaceNames.Parts p = kr("서울특별시", "", "", "중구");
    assertEquals("서울특별시 중구", PlaceNames.format(p));
    assertEquals("서울특별시", PlaceNames.format(p, PlaceNames.Scope.SIDO));
    assertEquals("서울특별시 중구", PlaceNames.format(p, PlaceNames.Scope.SIGUNGU));
  }

  @Test
  public void koreaBusanDistrict() {
    PlaceNames.Parts p = kr("부산광역시", "", "해운대구", "");
    assertEquals("부산광역시 해운대구", PlaceNames.format(p));
  }

  @Test
  public void koreaSejongOnly() {
    PlaceNames.Parts p = kr("세종특별자치시", "", "", "");
    assertEquals("세종특별자치시", PlaceNames.format(p));
    assertEquals("세종특별자치시", PlaceNames.format(p, PlaceNames.Scope.SIDO));
    assertEquals("세종특별자치시", PlaceNames.format(p, PlaceNames.Scope.SIGUNGU));
  }

  @Test
  public void koreaSuwonYeongtongMaetanAllLevels() {
    PlaceNames.Parts p = krFull("경기도", "수원시", "영통구", "매탄동", "매탄로", "123");
    assertEquals("경기도", PlaceNames.format(p, PlaceNames.Scope.SIDO));
    assertEquals("경기도 수원시 영통구", PlaceNames.format(p, PlaceNames.Scope.SIGUNGU));
    assertEquals("경기도 수원시 영통구 매탄동", PlaceNames.format(p, PlaceNames.Scope.DONG));
    assertEquals("경기도 수원시 영통구 매탄동", PlaceNames.format(p));
  }

  @Test
  public void koreaDongInFeatureAndGuInLocality() {
    PlaceNames.Parts p = krFull("서울특별시", "", "중구", "", "충무로", "명동");
    assertEquals("서울특별시", PlaceNames.format(p, PlaceNames.Scope.SIDO));
    assertEquals("서울특별시 중구", PlaceNames.format(p, PlaceNames.Scope.SIGUNGU));
    assertEquals("서울특별시 중구 명동", PlaceNames.format(p, PlaceNames.Scope.DONG));
  }

  @Test
  public void koreaCombinedSubLocalityTokens() {
    PlaceNames.Parts p = kr("경기도", "", "수원시", "영통구 매탄동");
    assertEquals("경기도 수원시 영통구", PlaceNames.format(p, PlaceNames.Scope.SIGUNGU));
    assertEquals("경기도 수원시 영통구 매탄동", PlaceNames.format(p, PlaceNames.Scope.DONG));
  }

  @Test
  public void koreaEupAndGun() {
    PlaceNames.Parts p = kr("경기도", "양평군", "", "양평읍");
    assertEquals("경기도", PlaceNames.format(p, PlaceNames.Scope.SIDO));
    assertEquals("경기도 양평군", PlaceNames.format(p, PlaceNames.Scope.SIGUNGU));
    assertEquals("경기도 양평군 양평읍", PlaceNames.format(p, PlaceNames.Scope.DONG));
  }

  @Test
  public void koreaNumberedDongAllowedButStreetRejected() {
    PlaceNames.Parts p = krFull("서울특별시", "", "강남구", "역삼1동", "테헤란로", "427");
    assertEquals("서울특별시 강남구 역삼1동", PlaceNames.format(p, PlaceNames.Scope.DONG));
    assertFalse(PlaceNames.format(p, PlaceNames.Scope.DONG).contains("테헤란로"));
    assertFalse(PlaceNames.format(p, PlaceNames.Scope.DONG).contains("427"));
  }

  @Test
  public void koreaCityInSubAdminGuInLocality() {
    PlaceNames.Parts p = kr("경기도", "수원시", "영통구", "");
    assertEquals("경기도 수원시 영통구", PlaceNames.format(p, PlaceNames.Scope.SIGUNGU));
  }

  @Test
  public void koreaGwangjuSiNotConfusedWithMetro() {
    PlaceNames.Parts p = kr("경기도", "", "광주시", "오포읍");
    assertEquals("경기도", PlaceNames.format(p, PlaceNames.Scope.SIDO));
    assertEquals("경기도 광주시", PlaceNames.format(p, PlaceNames.Scope.SIGUNGU));
    assertEquals("경기도 광주시 오포읍", PlaceNames.format(p, PlaceNames.Scope.DONG));
  }

  @Test
  public void japanKyotoLevels() {
    PlaceNames.Parts p = new PlaceNames.Parts("JP", "일본", "교토부", "", "교토시", "히가시야마구");
    assertEquals("일본", PlaceNames.format(p, PlaceNames.Scope.SIDO));
    assertEquals("일본 교토시", PlaceNames.format(p, PlaceNames.Scope.SIGUNGU));
    assertEquals("일본 교토시 히가시야마구", PlaceNames.format(p, PlaceNames.Scope.DONG));
    assertEquals("일본 교토시 히가시야마구", PlaceNames.format(p));
  }

  @Test
  public void franceParis() {
    PlaceNames.Parts p = new PlaceNames.Parts("FR", "프랑스", "일드프랑스", "", "파리", "");
    assertEquals("프랑스", PlaceNames.format(p, PlaceNames.Scope.SIDO));
    assertEquals("프랑스 파리", PlaceNames.format(p, PlaceNames.Scope.SIGUNGU));
    assertEquals("프랑스 파리", PlaceNames.format(p, PlaceNames.Scope.DONG));
  }

  @Test
  public void abroadFallsBackToEnglishIfNoKorean() {
    PlaceNames.Parts p =
        new PlaceNames.Parts("US", "United States", "California", "", "Los Angeles", "Hollywood");
    assertEquals("United States", PlaceNames.format(p, PlaceNames.Scope.SIDO));
    assertEquals("United States Los Angeles", PlaceNames.format(p, PlaceNames.Scope.SIGUNGU));
    assertEquals("United States Los Angeles Hollywood", PlaceNames.format(p, PlaceNames.Scope.DONG));
  }

  @Test
  public void abroadIgnoresStreetThoroughfare() {
    PlaceNames.Parts p =
        new PlaceNames.Parts("JP", "일본", "교토부", "", "교토시", "", "Shijo-dori", "123");
    assertEquals("일본 교토시", PlaceNames.format(p, PlaceNames.Scope.DONG));
  }

  @Test
  public void displayFallsBackToStoredNameWhenPartsMissing() {
    assertEquals(
        "제주 서귀포시",
        PlaceNames.display((PlaceNames.Parts) null, "제주 서귀포시", PlaceNames.Scope.SIDO));
    assertEquals(
        "제주 서귀포시", PlaceNames.display("", "제주 서귀포시", PlaceNames.Scope.DONG));
    PlaceNames.Parts p = kr("경기도", "수원시", "영통구", "매탄동");
    assertEquals("경기도", PlaceNames.display(p, "옛이름", PlaceNames.Scope.SIDO));
  }

  @Test
  public void partsJsonRoundTrip() {
    PlaceNames.Parts p = krFull("경기도", "수원시", "영통구", "매탄동", "매탄로", "카페 \"별\"");
    PlaceNames.Parts back = PlaceNames.Parts.fromJson(p.toJson());
    assertNotNull(back);
    assertEquals(p.admin, back.admin);
    assertEquals(p.subAdmin, back.subAdmin);
    assertEquals(p.locality, back.locality);
    assertEquals(p.subLocality, back.subLocality);
    assertEquals(p.thoroughfare, back.thoroughfare);
    assertEquals(p.feature, back.feature);
    assertEquals("경기도 수원시 영통구 매탄동", PlaceNames.format(back, PlaceNames.Scope.DONG));
  }

  @Test
  public void resolveAlbumOverridesGlobal() {
    assertEquals(
        PlaceNames.Scope.DONG, PlaceNames.Scope.resolve("", "DONG"));
    assertEquals(
        PlaceNames.Scope.DONG, PlaceNames.Scope.resolve("FOLLOW", "DONG"));
    assertEquals(
        PlaceNames.Scope.DONG, PlaceNames.Scope.resolve(null, null));
    assertEquals(
        PlaceNames.Scope.SIDO, PlaceNames.Scope.resolve("FOLLOW", "SIDO"));
    assertEquals(
        PlaceNames.Scope.SIGUNGU, PlaceNames.Scope.resolve("SIGUNGU", "SIDO"));
    assertEquals(
        PlaceNames.Scope.DONG, PlaceNames.Scope.resolve("FOLLOW", "FOLLOW"));
    assertEquals(
        PlaceNames.Scope.DONG, PlaceNames.Scope.resolve("nope", "also-nope"));
    assertEquals(
        PlaceNames.Scope.SIDO, PlaceNames.Scope.resolve("", "sido"));
    assertEquals("FOLLOW", PlaceNames.Scope.normalizeAlbum(""));
    assertEquals("DONG", PlaceNames.Scope.normalizeGlobal(""));
    assertEquals("SIDO", PlaceNames.Scope.normalizeAlbum("sido"));
  }

  @Test
  public void formatDateKorean() {
    // 2024-05-03 12:00 KST
    assertEquals("2024년 5월 3일", PlaceNames.formatDate(1714705200000L));
  }

  @Test
  public void captionJoinsDateAndPlace() {
    long t = 1714705200000L;
    assertEquals("2024년 5월 3일 · 제주 서귀포시", PlaceNames.caption(t, "제주 서귀포시", true, true));
    assertEquals("2024년 5월 3일", PlaceNames.caption(t, "제주 서귀포시", true, false));
    assertEquals("제주 서귀포시", PlaceNames.caption(t, "제주 서귀포시", false, true));
    assertEquals("소중한 순간", PlaceNames.caption(0, "", true, true));
    assertEquals("소중한 순간 · 제주 서귀포시", PlaceNames.caption(0, "제주 서귀포시", true, true));
    assertEquals("", PlaceNames.caption(t, "제주 서귀포시", false, false));
  }

  @Test
  public void cacheKeyRoundsToThreeDecimals() {
    assertEquals("37.483,126.931", PlaceNames.cacheKey(37.48349, 126.93051));
    assertEquals("3.142,0.000", PlaceNames.cacheKey(3.14159, 0.0004));
    assertEquals("-122.419,37.775", PlaceNames.cacheKey(-122.4194, 37.7749));
    assertEquals(37.484, PlaceNames.roundCoord(37.4836), 0.0);
  }
}
