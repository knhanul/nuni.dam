package co.kr.nuni.dam;

import androidx.annotation.NonNull;
import androidx.room.*;

@Entity(
    tableName = "scene_text_collections",
    primaryKeys = {"sceneId", "collectionId"},
    indices = {@Index("sceneId"), @Index("collectionId")},
    foreignKeys = {
      @ForeignKey(
          entity = Scene.class,
          parentColumns = "id",
          childColumns = "sceneId",
          onDelete = ForeignKey.CASCADE),
      @ForeignKey(
          entity = TextCollection.class,
          parentColumns = "id",
          childColumns = "collectionId",
          onDelete = ForeignKey.CASCADE)
    })
public class SceneTextCollection {
  @NonNull public String sceneId = "", collectionId = "";
}
