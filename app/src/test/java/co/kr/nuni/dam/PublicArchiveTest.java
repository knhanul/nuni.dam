package co.kr.nuni.dam;

import static org.junit.Assert.*;

import org.junit.Test;

public class PublicArchiveTest {
  @Test
  public void displayNameContainsFullId() {
    String id = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    assertEquals("nunidam_" + id + ".jpg", PublicArchive.displayName(id));
  }

  @Test
  public void photoIdFromNameAcceptsFullHash() {
    String id = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    assertEquals(id, PublicArchive.photoIdFromName("nunidam_" + id + ".jpg"));
    assertEquals(id, PublicArchive.photoIdFromName("NUNIDAM_" + id.toUpperCase() + ".JPEG"));
    assertEquals("", PublicArchive.photoIdFromName("nunidam_0123456789abcdef.jpg"));
    assertEquals("", PublicArchive.photoIdFromName("vacation.jpg"));
    assertEquals("", PublicArchive.photoIdFromName(null));
  }

  @Test
  public void looksLikeArchiveName() {
    assertTrue(
        PublicArchive.looksLikeArchiveName(
            "nunidam_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef.jpg"));
    assertFalse(PublicArchive.looksLikeArchiveName(".nunidam_backup.json"));
    assertFalse(PublicArchive.looksLikeArchiveName("nunidam_backup.json"));
    assertFalse(PublicArchive.looksLikeArchiveName("IMG_0001.jpg"));
  }

  @Test
  public void sizeText() {
    assertEquals("512B", PublicArchive.sizeText(512));
    assertEquals("2KB", PublicArchive.sizeText(2048));
    assertTrue(PublicArchive.sizeText(2 * 1024 * 1024).contains("MB"));
  }
}
