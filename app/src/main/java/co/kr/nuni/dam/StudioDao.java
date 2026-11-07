package co.kr.nuni.dam;

import androidx.room.*;
import java.util.List;

@Dao
public interface StudioDao {
  // --- Albums ---
  @Query("SELECT * FROM albums ORDER BY isDefault DESC, createdAt ASC")
  List<Album> albums();

  @Query("SELECT * FROM albums WHERE id=:id")
  Album album(String id);

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  void saveAlbum(Album a);

  @Query("DELETE FROM albums WHERE id=:id")
  void deleteAlbum(String id);

  @Query("SELECT * FROM album_items WHERE albumId=:albumId ORDER BY position ASC, addedAt ASC")
  List<AlbumItem> albumItems(String albumId);

  @Query("SELECT * FROM album_items ORDER BY albumId, position ASC")
  List<AlbumItem> allAlbumItems();

  @Query("SELECT COUNT(*) FROM album_items a JOIN photos p ON a.photoId=p.id WHERE a.albumId=:albumId AND p.trashedAt=0")
  int albumPhotoCount(String albumId);

  @Query("SELECT p.* FROM photos p JOIN album_items a ON p.id=a.photoId WHERE a.albumId=:albumId AND p.trashedAt=0 ORDER BY a.position ASC, a.addedAt ASC")
  List<Photo> albumPhotos(String albumId);

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  void saveAlbumItem(AlbumItem item);

  @Query("DELETE FROM album_items WHERE albumId=:albumId AND photoId=:photoId")
  void removeAlbumItem(String albumId, String photoId);

  @Query("DELETE FROM album_items WHERE albumId=:albumId")
  void clearAlbumItems(String albumId);

  // --- TextItems & TextCollections ---
  @Query("SELECT * FROM text_items ORDER BY sortOrder ASC, createdAt DESC")
  List<TextItem> textItems();

  @Query("SELECT * FROM text_items WHERE enabled=1 ORDER BY sortOrder ASC, createdAt DESC")
  List<TextItem> enabledTextItems();

  @Query("SELECT * FROM text_items WHERE id=:id")
  TextItem textItem(String id);

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  void saveTextItem(TextItem item);

  @Query("DELETE FROM text_items WHERE id=:id")
  void deleteTextItem(String id);

  @Query("SELECT * FROM text_collections ORDER BY createdAt ASC")
  List<TextCollection> textCollections();

  @Query("SELECT * FROM text_collections WHERE id=:id")
  TextCollection textCollection(String id);

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  void saveTextCollection(TextCollection col);

  @Query("DELETE FROM text_collections WHERE id=:id")
  void deleteTextCollection(String id);

  // --- Scenes ---
  @Query("SELECT * FROM scenes ORDER BY isDefault DESC, createdAt ASC")
  List<Scene> scenes();

  @Query("SELECT * FROM scenes WHERE id=:id")
  Scene scene(String id);

  @Query("SELECT * FROM scenes WHERE isDefault=1 LIMIT 1")
  Scene defaultScene();

  @Query("UPDATE scenes SET isDefault=0")
  void clearDefaultScenes();

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  void saveScene(Scene s);

  @Query("DELETE FROM scenes WHERE id=:id")
  void deleteScene(String id);

  @Query("SELECT * FROM scene_albums WHERE sceneId=:sceneId")
  List<SceneAlbum> sceneAlbums(String sceneId);

  @Query("SELECT * FROM scene_albums")
  List<SceneAlbum> allSceneAlbums();

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  void saveSceneAlbum(SceneAlbum sa);

  @Query("DELETE FROM scene_albums WHERE sceneId=:sceneId")
  void clearSceneAlbums(String sceneId);

  @Query("SELECT * FROM scene_text_collections WHERE sceneId=:sceneId")
  List<SceneTextCollection> sceneTextCollections(String sceneId);

  @Query("SELECT * FROM scene_text_collections")
  List<SceneTextCollection> allSceneTextCollections();

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  void saveSceneTextCollection(SceneTextCollection stc);

  @Query("DELETE FROM scene_text_collections WHERE sceneId=:sceneId")
  void clearSceneTextCollections(String sceneId);

  // --- Schedule Rules ---
  @Query("SELECT * FROM schedule_rules ORDER BY priority ASC, startMin ASC")
  List<ScheduleRule> scheduleRules();

  @Query("SELECT * FROM schedule_rules WHERE enabled=1 ORDER BY priority ASC, startMin ASC")
  List<ScheduleRule> enabledScheduleRules();

  @Query("SELECT * FROM schedule_rules WHERE id=:id")
  ScheduleRule scheduleRule(String id);

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  void saveScheduleRule(ScheduleRule r);

  @Query("DELETE FROM schedule_rules WHERE id=:id")
  void deleteScheduleRule(String id);

  @Query("DELETE FROM schedule_rules WHERE ruleType='TEMP'")
  void clearTempScheduleRules();

  // --- PublishedConfiguration ---
  @Query("SELECT * FROM published_configurations WHERE id=:id")
  PublishedConfiguration config(String id);

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  void saveConfig(PublishedConfiguration cfg);

  // --- AdminSessions ---
  @Query("SELECT * FROM admin_sessions WHERE revoked=0 AND expiresAt>:now ORDER BY lastAccessAt DESC")
  List<AdminSession> activeSessions(long now);

  @Query("SELECT * FROM admin_sessions WHERE sessionId=:id")
  AdminSession session(String id);

  @Query("SELECT * FROM admin_sessions WHERE tokenHash=:hash AND revoked=0 AND expiresAt>:now LIMIT 1")
  AdminSession sessionByTokenHash(String hash, long now);

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  void saveSession(AdminSession s);

  @Query("UPDATE admin_sessions SET lastAccessAt=:now WHERE sessionId=:id")
  void touchSession(String id, long now);

  @Query("UPDATE admin_sessions SET revoked=1 WHERE sessionId=:id")
  void revokeSession(String id);

  @Query("UPDATE admin_sessions SET revoked=1")
  void revokeAllSessions();
}
