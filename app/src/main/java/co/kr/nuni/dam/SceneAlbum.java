package co.kr.nuni.dam;

import androidx.annotation.NonNull;
import androidx.room.*;

@Entity(
    tableName = "scene_albums",
    primaryKeys = {"sceneId", "albumId"},
    indices = {@Index("sceneId"), @Index("albumId")},
    foreignKeys = {
      @ForeignKey(
          entity = Scene.class,
          parentColumns = "id",
          childColumns = "sceneId",
          onDelete = ForeignKey.CASCADE),
      @ForeignKey(
          entity = Album.class,
          parentColumns = "id",
          childColumns = "albumId",
          onDelete = ForeignKey.CASCADE)
    })
public class SceneAlbum {
  @NonNull public String sceneId = "", albumId = "";
  public int weight = 1;
}
