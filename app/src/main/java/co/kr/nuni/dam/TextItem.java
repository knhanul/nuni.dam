package co.kr.nuni.dam;

import androidx.annotation.NonNull;
import androidx.room.*;

@Entity(tableName = "text_items")
public class TextItem {
  @PrimaryKey @NonNull public String id = "";
  @NonNull
  public String title = "",
      body = "",
      author = "",
      source = "",
      kind = "QUOTE", // QUOTE, POEM, CUSTOM, SHORT, ANNIVERSARY
      category = "아침의 명언",
      tags = "",
      licenseNote = "";

  public int displaySeconds = 15, sortOrder = 0;
  public boolean enabled = true;
  public long createdAt, updatedAt;
}
