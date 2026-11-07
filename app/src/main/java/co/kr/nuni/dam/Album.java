package co.kr.nuni.dam;

import androidx.annotation.NonNull;
import androidx.room.*;

@Entity(tableName = "albums")
public class Album {
  @PrimaryKey @NonNull public String id = "";
  @NonNull public String name = "새 앨범", description = "", coverPhotoId = "", sortOrder = "CUSTOM";
  public int seconds = 30;
  public boolean shuffle = true, isDefault = false;
  public long createdAt, updatedAt;
}
