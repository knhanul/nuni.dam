package co.kr.nuni.dam;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "geo_cache")
public class GeoCache {
  @PrimaryKey @NonNull public String key = "";
  @NonNull public String placeName = "";
  public long geocodedAt;
}
