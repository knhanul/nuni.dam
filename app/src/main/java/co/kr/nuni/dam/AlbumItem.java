package co.kr.nuni.dam;

import androidx.annotation.NonNull;
import androidx.room.*;

@Entity(
    tableName = "album_items",
    primaryKeys = {"albumId", "photoId"},
    indices = {@Index("albumId"), @Index("photoId")},
    foreignKeys = {
      @ForeignKey(
          entity = Album.class,
          parentColumns = "id",
          childColumns = "albumId",
          onDelete = ForeignKey.CASCADE),
      @ForeignKey(
          entity = Photo.class,
          parentColumns = "id",
          childColumns = "photoId",
          onDelete = ForeignKey.CASCADE)
    })
public class AlbumItem {
  @NonNull public String albumId = "", photoId = "";
  public int position;
  public long addedAt;
}
