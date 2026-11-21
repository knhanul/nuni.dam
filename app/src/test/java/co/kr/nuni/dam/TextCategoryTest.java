package co.kr.nuni.dam;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class TextCategoryTest {
  private static TextItem item(String id, String category) {
    TextItem t = new TextItem();
    t.id = id;
    t.body = "글 " + id;
    t.category = category;
    return t;
  }

  @Test
  public void categoryNamesAreNormalised() {
    assertEquals("기타", Core.textCategory(null));
    assertEquals("기타", Core.textCategory("  "));
    assertEquals("인생문장", Core.textCategory(" 인생문장 "));
    assertEquals("성경 말씀", Core.textCategory("cat:성경 말씀"));
  }

  @Test
  public void onlyChosenCategoriesPlay() {
    List<TextItem> all =
        Arrays.asList(
            item("a", "인생문장"), item("b", "성경 말씀"), item("c", "시 한 구절"), item("d", ""));
    List<TextItem> picked = Core.textsInCategories(all, Arrays.asList("성경 말씀"));
    assertEquals(1, picked.size());
    assertEquals("b", picked.get(0).id);

    List<TextItem> two = Core.textsInCategories(all, Arrays.asList("인생문장", "기타"));
    assertEquals(2, two.size());
    assertEquals("a", two.get(0).id);
    assertEquals("d", two.get(1).id);
  }

  @Test
  public void chosenCategoryWithoutTextsGivesNothingNotEverything() {
    List<TextItem> all = Arrays.asList(item("a", "인생문장"), item("b", "성경 말씀"));
    assertTrue(Core.textsInCategories(all, Arrays.asList("아침의 명언")).isEmpty());
    assertTrue(Core.textsInCategories(all, Collections.<String>emptyList()).isEmpty());
  }
}
