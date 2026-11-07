package co.kr.nuni.dam;

import androidx.annotation.NonNull;
import androidx.room.*;

@Entity(tableName = "admin_sessions")
public class AdminSession {
  @PrimaryKey @NonNull public String sessionId = "";
  @NonNull
  public String tokenHash = "",
      csrfToken = "",
      clientIp = "",
      userAgent = "",
      label = "관리자 브라우저";

  public long createdAt, lastAccessAt, expiresAt;
  public boolean revoked = false;
}
