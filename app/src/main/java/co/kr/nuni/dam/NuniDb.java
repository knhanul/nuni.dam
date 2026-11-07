package co.kr.nuni.dam;

import androidx.annotation.NonNull;
import androidx.room.*;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
    entities = {
      Photo.class,
      SourceLink.class,
      Quote.class,
      Album.class,
      AlbumItem.class,
      TextItem.class,
      TextCollection.class,
      Scene.class,
      SceneAlbum.class,
      SceneTextCollection.class,
      ScheduleRule.class,
      PublishedConfiguration.class,
      AdminSession.class
    },
    version = 2,
    exportSchema = true)
public abstract class NuniDb extends RoomDatabase {
  public abstract PhotoDao photos();

  public abstract StudioDao studio();

  public static final Migration MIGRATION_1_2 =
      new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
          db.execSQL(
              "CREATE TABLE IF NOT EXISTS `albums` (`id` TEXT NOT NULL, `name` TEXT NOT NULL,"
                  + " `description` TEXT NOT NULL, `coverPhotoId` TEXT NOT NULL, `sortOrder` TEXT"
                  + " NOT NULL, `seconds` INTEGER NOT NULL, `shuffle` INTEGER NOT NULL, `isDefault`"
                  + " INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,"
                  + " PRIMARY KEY(`id`))");
          db.execSQL(
              "CREATE TABLE IF NOT EXISTS `album_items` (`albumId` TEXT NOT NULL, `photoId` TEXT"
                  + " NOT NULL, `position` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY"
                  + " KEY(`albumId`, `photoId`), FOREIGN KEY(`albumId`) REFERENCES `albums`(`id`)"
                  + " ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`photoId`) REFERENCES"
                  + " `photos`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)");
          db.execSQL(
              "CREATE INDEX IF NOT EXISTS `index_album_items_albumId` ON `album_items`"
                  + " (`albumId`)");
          db.execSQL(
              "CREATE INDEX IF NOT EXISTS `index_album_items_photoId` ON `album_items`"
                  + " (`photoId`)");
          db.execSQL(
              "CREATE TABLE IF NOT EXISTS `text_items` (`id` TEXT NOT NULL, `title` TEXT NOT NULL,"
                  + " `body` TEXT NOT NULL, `author` TEXT NOT NULL, `source` TEXT NOT NULL, `kind`"
                  + " TEXT NOT NULL, `category` TEXT NOT NULL, `tags` TEXT NOT NULL, `licenseNote`"
                  + " TEXT NOT NULL, `displaySeconds` INTEGER NOT NULL, `sortOrder` INTEGER NOT"
                  + " NULL, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt`"
                  + " INTEGER NOT NULL, PRIMARY KEY(`id`))");
          db.execSQL(
              "CREATE TABLE IF NOT EXISTS `text_collections` (`id` TEXT NOT NULL, `name` TEXT NOT"
                  + " NULL, `description` TEXT NOT NULL, `categoryFilter` TEXT NOT NULL,"
                  + " `kindFilter` TEXT NOT NULL, `itemIdsJson` TEXT NOT NULL, `shuffle` INTEGER"
                  + " NOT NULL, `avoidRepeat` INTEGER NOT NULL, `frequency` INTEGER NOT NULL,"
                  + " `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY"
                  + " KEY(`id`))");
          db.execSQL(
              "CREATE TABLE IF NOT EXISTS `scenes` (`id` TEXT NOT NULL, `name` TEXT NOT NULL,"
                  + " `description` TEXT NOT NULL, `layout` TEXT NOT NULL, `transitionEffect` TEXT"
                  + " NOT NULL, `textDisplayMode` TEXT NOT NULL, `quoteCycle` TEXT NOT NULL,"
                  + " `theme` TEXT NOT NULL, `textColor` TEXT NOT NULL, `textAlign` TEXT NOT NULL,"
                  + " `seconds` INTEGER NOT NULL, `textEveryN` INTEGER NOT NULL, `brightness`"
                  + " INTEGER NOT NULL, `fontScale` INTEGER NOT NULL, `textPadding` INTEGER NOT"
                  + " NULL, `scrimOpacity` INTEGER NOT NULL, `showClock` INTEGER NOT NULL,"
                  + " `showWeather` INTEGER NOT NULL, `showCaptureDate` INTEGER NOT NULL,"
                  + " `shufflePhotos` INTEGER NOT NULL, `isDefault` INTEGER NOT NULL, `nightDim`"
                  + " INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,"
                  + " PRIMARY KEY(`id`))");
          db.execSQL(
              "CREATE TABLE IF NOT EXISTS `scene_albums` (`sceneId` TEXT NOT NULL, `albumId` TEXT"
                  + " NOT NULL, `weight` INTEGER NOT NULL, PRIMARY KEY(`sceneId`, `albumId`),"
                  + " FOREIGN KEY(`sceneId`) REFERENCES `scenes`(`id`) ON UPDATE NO ACTION ON"
                  + " DELETE CASCADE, FOREIGN KEY(`albumId`) REFERENCES `albums`(`id`) ON UPDATE NO"
                  + " ACTION ON DELETE CASCADE)");
          db.execSQL(
              "CREATE INDEX IF NOT EXISTS `index_scene_albums_sceneId` ON `scene_albums`"
                  + " (`sceneId`)");
          db.execSQL(
              "CREATE INDEX IF NOT EXISTS `index_scene_albums_albumId` ON `scene_albums`"
                  + " (`albumId`)");
          db.execSQL(
              "CREATE TABLE IF NOT EXISTS `scene_text_collections` (`sceneId` TEXT NOT NULL,"
                  + " `collectionId` TEXT NOT NULL, PRIMARY KEY(`sceneId`, `collectionId`), FOREIGN"
                  + " KEY(`sceneId`) REFERENCES `scenes`(`id`) ON UPDATE NO ACTION ON DELETE"
                  + " CASCADE, FOREIGN KEY(`collectionId`) REFERENCES `text_collections`(`id`) ON"
                  + " UPDATE NO ACTION ON DELETE CASCADE)");
          db.execSQL(
              "CREATE INDEX IF NOT EXISTS `index_scene_text_collections_sceneId` ON"
                  + " `scene_text_collections` (`sceneId`)");
          db.execSQL(
              "CREATE INDEX IF NOT EXISTS `index_scene_text_collections_collectionId` ON"
                  + " `scene_text_collections` (`collectionId`)");
          db.execSQL(
              "CREATE TABLE IF NOT EXISTS `schedule_rules` (`id` TEXT NOT NULL, `name` TEXT NOT"
                  + " NULL, `ruleType` TEXT NOT NULL, `specificDate` TEXT NOT NULL, `sceneId` TEXT"
                  + " NOT NULL, `daysMask` INTEGER NOT NULL, `startMin` INTEGER NOT NULL, `endMin`"
                  + " INTEGER NOT NULL, `priority` INTEGER NOT NULL, `enabled` INTEGER NOT NULL,"
                  + " `expiresAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt`"
                  + " INTEGER NOT NULL, PRIMARY KEY(`id`))");
          db.execSQL(
              "CREATE TABLE IF NOT EXISTS `published_configurations` (`id` TEXT NOT NULL,"
                  + " `payloadJson` TEXT NOT NULL, `note` TEXT NOT NULL, `version` INTEGER NOT"
                  + " NULL, `publishedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY"
                  + " KEY(`id`))");
          db.execSQL(
              "CREATE TABLE IF NOT EXISTS `admin_sessions` (`sessionId` TEXT NOT NULL, `tokenHash`"
                  + " TEXT NOT NULL, `csrfToken` TEXT NOT NULL, `clientIp` TEXT NOT NULL,"
                  + " `userAgent` TEXT NOT NULL, `label` TEXT NOT NULL, `createdAt` INTEGER NOT"
                  + " NULL, `lastAccessAt` INTEGER NOT NULL, `expiresAt` INTEGER NOT NULL,"
                  + " `revoked` INTEGER NOT NULL, PRIMARY KEY(`sessionId`))");
        }
      };
}
