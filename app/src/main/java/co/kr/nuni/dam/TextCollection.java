package co.kr.nuni.dam;

import androidx.annotation.NonNull;
import androidx.room.*;

@Entity(tableName = "text_collections")
public class TextCollection {
  @PrimaryKey @NonNull public String id = "";
  @NonNull
  public String name = "기본 글귀 모음",
      description = "",
      categoryFilter = "",
      kindFilter = "ALL",
      itemIdsJson = "[]";

  public boolean shuffle = true, avoidRepeat = true;
  public int frequency = 1; // Show text every N photos
  public long createdAt, updatedAt;
}
