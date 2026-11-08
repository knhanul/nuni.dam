package co.kr.nuni.dam;

import androidx.annotation.NonNull;
import androidx.room.*;

@Entity(tableName = "albums")
public class Album {
  public static final String ALL_PHOTOS_ID = "album_default";
  public static final String ALL_PHOTOS_NAME = "모든 사진";

  @PrimaryKey @NonNull public String id = "";
  @NonNull public String name = "새 앨범", description = "", coverPhotoId = "", sortOrder = "CUSTOM";
  public int seconds = 30;
  public boolean shuffle = true, isDefault = false;
  public long createdAt, updatedAt;

  public static boolean isAllPhotos(@NonNull String id) {
    return ALL_PHOTOS_ID.equals(id);
  }
}
