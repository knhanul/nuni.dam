package co.kr.nuni.dam;

import androidx.annotation.NonNull;
import androidx.room.*;

@Entity(tableName = "photos")
public class Photo {
  @PrimaryKey @NonNull public String id = "";
  @NonNull public String name = "사진", note = "", faces = "[]", publicUri = "", placeName = "";
  public long importedAt, capturedAt, bytes, trashedAt, geocodedAt;
  public int width, height, accent = 0xff526951;
  public boolean owned, favourite, hasGps;
  public float focusX = -1, focusY = -1;
  public double lat, lon;
}
