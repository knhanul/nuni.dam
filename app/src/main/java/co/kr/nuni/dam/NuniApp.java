package co.kr.nuni.dam;

import android.app.*;
import android.content.Context;
import android.os.*;
import androidx.room.Room;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public final class NuniApp extends Application {
  public final ExecutorService io = Executors.newSingleThreadExecutor(),
      imports = Executors.newSingleThreadExecutor();
  public final Handler main = new Handler(Looper.getMainLooper());
  public Prefs prefs;
  public NuniDb db;
  public PhotoStore store;
  public StudioRepository studio;
  private StudioServer studioServer;
  private String lastServerError = "";
  public final PowerSaving.Diagnostics powerDiagnostics = new PowerSaving.Diagnostics();
  static final long MEMORY_CHECK_MS = 60 * 60 * 1000L;
  private final long startedAt = android.os.SystemClock.elapsedRealtime();
  private final Runnable memoryCheck =
      new Runnable() {
        public void run() {
          final long up = android.os.SystemClock.elapsedRealtime() - startedAt;
          io.execute(
              () ->
                  ExitLog.logJob(
                      NuniApp.this,
                      "메모리 점검 (켜진 지 "
                          + Core.uptimeLabel(up)
                          + " · 사진 "
                          + FrameSession.shownCount()
                          + "장 표시)"
                          + ExitLog.detailedSnapshot(NuniApp.this)));
          main.postDelayed(this, MEMORY_CHECK_MS);
        }
      };

  public void onCreate() {
    super.onCreate();
    prefs = new Prefs(this);
    db =
        Room.databaseBuilder(this, NuniDb.class, "nunidam.db")
            .addMigrations(NuniDb.MIGRATION_1_2, NuniDb.MIGRATION_2_3, NuniDb.MIGRATION_3_4, NuniDb.MIGRATION_4_5)
            .build();
    store = new PhotoStore(this, db, prefs);
    studio = new StudioRepository(this, db, store, prefs);
    if (Build.VERSION.SDK_INT >= 26)
      getSystemService(NotificationManager.class)
          .createNotificationChannel(
              new NotificationChannel("frame", "액자 시작 안내", NotificationManager.IMPORTANCE_LOW));
    ExitLog.install(this);
    ExitLog.logJob(this, "앱 시작");
    FrameWatchdog.onProcessStart(this);
    // Hourly memory line in 앱 종료 기록, so slow growth over a day becomes visible.
    main.postDelayed(memoryCheck, MEMORY_CHECK_MS);
    io.execute(
        () -> {
          store.cleanup();
          try {
            studio.ensureSeeded();
          } catch (Exception ignored) {
          }
          PublicArchive.onAppStart(NuniApp.this);
          Places.onAppStart(NuniApp.this);
          if (prefs.remoteEnabled()) {
            startStudioServer();
          }
        });
    FolderSync.schedule(this);
  }

  @Override
  public void onTrimMemory(int level) {
    super.onTrimMemory(level);
    MemoryGuard.note(level);
    ExitLog.logTrimMemory(this, level);
    if (Core.shouldReleaseCaches(level)) MemoryGuard.dispatch(level);
  }

  @Override
  public void onLowMemory() {
    super.onLowMemory();
    MemoryGuard.note(15);
    ExitLog.logLowMemory(this);
    MemoryGuard.dispatch(15); // TRIM_MEMORY_RUNNING_CRITICAL
  }

  public synchronized StudioServer studioServer() {
    return studioServer;
  }

  public synchronized boolean isStudioServerRunning() {
    return studioServer != null && studioServer.isAlive();
  }

  public synchronized String lastServerError() {
    return lastServerError;
  }

  public synchronized boolean startStudioServer() {
    stopStudioServer();
    lastServerError = "";
    int preferredPort = prefs.remotePort();
    try {
      StudioServer srv = new StudioServer(this, preferredPort);
      srv.start(30000, true);
      studioServer = srv;
      prefs.flag("remoteEnabled", true);
      return true;
    } catch (Exception firstErr) {
      // Fallback to automatic port if preferred port (8787) is busy
      try {
        StudioServer srv = new StudioServer(this, 0);
        srv.start(30000, true);
        studioServer = srv;
        prefs.flag("remoteEnabled", true);
        return true;
      } catch (Exception secondErr) {
        lastServerError =
            secondErr.getMessage() == null ? "서버를 시작할 수 없습니다." : secondErr.getMessage();
        prefs.flag("remoteEnabled", false);
        return false;
      }
    }
  }

  public synchronized void stopStudioServer() {
    if (studioServer != null) {
      try {
        studioServer.stop();
      } catch (Exception ignored) {
      }
      studioServer = null;
    }
  }

  public static NuniApp of(Context c) {
    return (NuniApp) c.getApplicationContext();
  }
}
