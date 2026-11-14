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

  public void onCreate() {
    super.onCreate();
    prefs = new Prefs(this);
    db =
        Room.databaseBuilder(this, NuniDb.class, "nunidam.db")
            .addMigrations(NuniDb.MIGRATION_1_2)
            .build();
    store = new PhotoStore(this, db, prefs);
    studio = new StudioRepository(this, db, store, prefs);
    if (Build.VERSION.SDK_INT >= 26)
      getSystemService(NotificationManager.class)
          .createNotificationChannel(
              new NotificationChannel("frame", "액자 시작 안내", NotificationManager.IMPORTANCE_LOW));
    ExitLog.install(this);
    io.execute(
        () -> {
          store.cleanup();
          try {
            studio.ensureSeeded();
          } catch (Exception ignored) {
          }
          if (prefs.remoteEnabled()) {
            startStudioServer();
          }
        });
    FolderSync.schedule(this);
  }

  @Override
  public void onTrimMemory(int level) {
    super.onTrimMemory(level);
    ExitLog.logTrimMemory(this, level);
  }

  @Override
  public void onLowMemory() {
    super.onLowMemory();
    ExitLog.logLowMemory(this);
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
