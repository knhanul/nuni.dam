package co.kr.nuni.dam;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface GeoCacheDao {
  @Query("SELECT * FROM geo_cache WHERE `key`=:key")
  GeoCache get(String key);

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  void upsert(GeoCache row);
}
