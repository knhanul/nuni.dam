package co.kr.nuni.dam;

import static org.junit.Assert.*;

import org.junit.Test;

public class PlaceNamesTest {
  @Test
  public void koreaJejuSeogwipo() {
    PlaceNames.Parts p =
        new PlaceNames.Parts("KR", "대한민국", "제주특별자치도", "", "서귀포시", "");
    assertEquals("제주 서귀포시", PlaceNames.format(p));
  }

  @Test
  public void koreaGangwonSokcho() {
    PlaceNames.Parts p =
        new PlaceNames.Parts("kr", "대한민국", "강원특별자치도", "", "속초시", "");
    assertEquals("강원 속초시", PlaceNames.format(p));
  }

  @Test
  public void koreaSeoulDistrict() {
    PlaceNames.Parts p = new PlaceNames.Parts("KR", "대한민국", "서울특별시", "", "", "중구");
    assertEquals("서울 중구", PlaceNames.format(p));
  }

  @Test
  public void koreaBusanDistrict() {
    PlaceNames.Parts p =
        new PlaceNames.Parts("KR", "대한민국", "부산광역시", "", "해운대구", "");
    assertEquals("부산 해운대구", PlaceNames.format(p));
  }

  @Test
  public void koreaSejongOnly() {
    PlaceNames.Parts p = new PlaceNames.Parts("KR", "대한민국", "세종특별자치시", "", "", "");
    assertEquals("세종", PlaceNames.format(p));
  }

  @Test
  public void japanKyoto() {
    PlaceNames.Parts p = new PlaceNames.Parts("JP", "일본", "교토부", "", "교토시", "");
    assertEquals("일본 교토시", PlaceNames.format(p));
  }

  @Test
  public void franceParis() {
    PlaceNames.Parts p = new PlaceNames.Parts("FR", "프랑스", "일드프랑스", "", "파리", "");
    assertEquals("프랑스 파리", PlaceNames.format(p));
  }

  @Test
  public void abroadFallsBackToEnglishIfNoKorean() {
    PlaceNames.Parts p = new PlaceNames.Parts("US", "United States", "California", "", "Los Angeles", "");
    assertEquals("United States Los Angeles", PlaceNames.format(p));
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
