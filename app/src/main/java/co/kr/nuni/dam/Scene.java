package co.kr.nuni.dam;

import androidx.annotation.NonNull;
import androidx.room.*;

@Entity(tableName = "scenes")
public class Scene {
  @PrimaryKey @NonNull public String id = "";
  @NonNull
  public String name = "새 장면",
      description = "",
      layout = "MAGAZINE", // PHOTO, MAGAZINE, BOARD, TEXT_ONLY, SPLIT
      transitionEffect = "ZOOM", // ZOOM, FADE, NONE
      textDisplayMode = "OVERLAY", // OVERLAY, DEDICATED, SPLIT, NONE
      quoteCycle = "PHOTO", // PHOTO, EVERY_N, MINUTES_15, HOUR, DAY
      theme = "MILK", // MILK, INK, SAGE
      textColor = "",
      textAlign = "LEFT"; // LEFT, CENTER, RIGHT

  public int seconds = 30,
      textEveryN = 1,
      brightness = 70,
      fontScale = 100,
      textPadding = 24,
      scrimOpacity = 70;
  public boolean showClock = true,
      showWeather = false,
      showCaptureDate = true,
      shufflePhotos = true,
      isDefault = false,
      nightDim = false;
  public long createdAt, updatedAt;
}
