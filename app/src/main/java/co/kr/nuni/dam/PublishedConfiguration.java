package co.kr.nuni.dam;

import androidx.annotation.NonNull;
import androidx.room.*;

@Entity(tableName = "published_configurations")
public class PublishedConfiguration {
  @PrimaryKey @NonNull public String id = "ACTIVE"; // "DRAFT" or "ACTIVE"
  @NonNull public String payloadJson = "{}", note = "";
  public int version = 1;
  public long publishedAt, updatedAt;
}
