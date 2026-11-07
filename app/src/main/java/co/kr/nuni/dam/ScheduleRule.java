package co.kr.nuni.dam;

import androidx.annotation.NonNull;
import androidx.room.*;

@Entity(tableName = "schedule_rules")
public class ScheduleRule {
  @PrimaryKey @NonNull public String id = "";
  @NonNull
  public String name = "시간표 규칙",
      ruleType = "WEEKLY", // TEMP, DATE, ANNIVERSARY, WEEKLY, DEFAULT
      specificDate = "", // YYYY-MM-DD (for DATE) or MM-DD (for ANNIVERSARY)
      sceneId = "";

  public int daysMask = 127; // Bitmask: bit 0=Sun(1), bit 1=Mon(2), ..., bit 6=Sat(64). 127=Everyday, 62=Mon-Fri, 65=Sat-Sun
  public int startMin = 360, endMin = 540; // 0..1439. Supports overnight when startMin > endMin
  public int priority = 3; // 1=TEMP, 2=DATE/ANNIVERSARY, 3=WEEKLY, 4=DEFAULT
  public boolean enabled = true;
  public long expiresAt = 0, createdAt, updatedAt;
}
