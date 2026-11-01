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
  public final PowerSaving.Diagnostics powerDiagnostics = new PowerSaving.Diagnostics();

  public void onCreate() {
    super.onCreate();
    prefs = new Prefs(this);
    db = Room.databaseBuilder(this, NuniDb.class, "nunidam.db").build();
    store = new PhotoStore(this, db, prefs);
    if (Build.VERSION.SDK_INT >= 26)
      getSystemService(NotificationManager.class)
          .createNotificationChannel(
              new NotificationChannel("frame", "액자 시작 안내", NotificationManager.IMPORTANCE_LOW));
    Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          try {
            prefs.raw.edit().putLong("lastCrash", System.currentTimeMillis()).commit();
          } catch (Exception ignored) {
          }
          if (original != null) original.uncaughtException(thread, error);
        });
    io.execute(
        () -> {
          store.cleanup();
          try (InputStream in = getAssets().open("phrases.txt")) {
            String text = new String(Core.read(in, 128 * 1024), StandardCharsets.UTF_8);
            int id = 0;
            for (String line : text.split("\\r?\\n"))
              if (!line.trim().isEmpty()) {
                Quote q = new Quote();
                q.id = id++;
                q.text = line.trim();
                db.photos().quote(q);
              }
          } catch (Exception ignored) {
          }
        });
    FolderSync.schedule(this);
  }

  public static NuniApp of(Context c) {
    return (NuniApp) c.getApplicationContext();
  }
}
