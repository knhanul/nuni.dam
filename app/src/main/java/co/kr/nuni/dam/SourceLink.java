package co.kr.nuni.dam;

import androidx.annotation.NonNull;
import androidx.room.*;

@Entity(
    tableName = "sources",
    primaryKeys = {"root", "document"},
    indices = @Index("photoId"),
    foreignKeys =
        @ForeignKey(
            entity = Photo.class,
            parentColumns = "id",
            childColumns = "photoId",
            onDelete = ForeignKey.CASCADE))
public class SourceLink {
  @NonNull public String root = "", document = "", photoId = "";
  public long modified, size, scan;
}
