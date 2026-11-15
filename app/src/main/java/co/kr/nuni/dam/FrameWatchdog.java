package co.kr.nuni.dam;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

/**
 * If the process is killed while the frame was showing, brings FrameActivity back. Uses a 10-minute
 * AlarmManager watchdog plus a 15-minute WorkManager backup. Does not use a foreground service.
 */
public final class FrameWatchdog {
  static final String ACTION = "co.kr.nuni.dam.ACTION_FRAME_WATCHDOG";
  static final String PREF_DESIRED = "frameDesired";
  static final String PREF_PROCESS_START = "processStartMs";
  static final String WORK_NAME = "frame-watchdog";
  static final String CHANNEL_ID = "frame-restart";
  static final int ALARM_REQ = 21;
  static final int NOTIFY_ID = 11;
  static final long INTERVAL_MS = 10 * 60 * 1000L;

  private FrameWatchdog() {}

  public static void onProcessStart(Context context) {
    Context app = context.getApplicationContext();
    SharedPreferences p = prefs(app);
    boolean desired = p.getBoolean(PREF_DESIRED, false);
    long prevStart = p.getLong(PREF_PROCESS_START, 0L);
    long lastCrash = p.getLong("lastCrash", 0L);
    boolean recentCrash = lastCrash > 0L && prevStart > 0L && lastCrash >= prevStart;
    String msg = Core.abnormalExitMessage(desired, recentCrash, ExitLog.lastTrimLabel(app));
    if (msg != null) {
      ExitLog.logLine(app, msg);
      ExitLog.markNotice(app);
    }
    try {
      p.edit().putLong(PREF_PROCESS_START, System.currentTimeMillis()).commit();
    } catch (Exception ignored) {
    }
    if (desired) schedule(app);
  }

  public static void onFrameStarted(Context context) {
    Context app = context.getApplicationContext();
    try {
      prefs(app).edit().putBoolean(PREF_DESIRED, true).commit();
    } catch (Exception ignored) {
    }
    schedule(app);
  }

  public static void onUserLeftFrame(Context context) {
    Context app = context.getApplicationContext();
    try {
      prefs(app).edit().putBoolean(PREF_DESIRED, false).commit();
    } catch (Exception ignored) {
    }
    cancel(app);
  }

  public static boolean isDesired(Context context) {
    try {
      return prefs(context).getBoolean(PREF_DESIRED, false);
    } catch (Exception e) {
      return false;
    }
  }

  public static boolean shouldOpenFrameNow(Context context) {
    return Core.shouldRelaunchFrame(
        isDesired(context), FrameActivity.isAlive(), isScheduledOff(context));
  }

  public static void onAlarm(Context context) {
    tick(context);
    if (isDesired(context)) scheduleAlarm(context.getApplicationContext());
  }

  public static void tick(Context context) {
    final Context app = context.getApplicationContext();
    Runnable run =
        () -> {
          if (!shouldOpenFrameNow(app)) return;
          ExitLog.logLine(app, "자동 다시 시작 (메모리 부족으로 종료 추정)");
          launch(app);
        };
    if (Looper.myLooper() == Looper.getMainLooper()) run.run();
    else new Handler(Looper.getMainLooper()).post(run);
  }

  public static void schedule(Context context) {
    Context app = context.getApplicationContext();
    scheduleAlarm(app);
    scheduleWork(app);
  }

  public static void cancel(Context context) {
    Context app = context.getApplicationContext();
    try {
      AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
      if (am != null) am.cancel(alarmIntent(app));
    } catch (Exception ignored) {
    }
    try {
      WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME);
    } catch (Exception ignored) {
    }
  }

  static void launch(Context app) {
    Intent i = frameIntent(app);
    boolean started = false;
    if (Build.VERSION.SDK_INT < 29) {
      started = startFrame(app, i);
    }
    if (!started) {
      // Android 10+ blocks background activity starts; fall back to a high-priority notification
      // with a full-screen intent. Also try startActivity in case an exemption applies.
      startFrame(app, i);
      notifyRelaunch(app, i);
    }
  }

  private static boolean startFrame(Context app, Intent i) {
    try {
      app.startActivity(i);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  static Intent frameIntent(Context app) {
    Intent i = new Intent(app, FrameActivity.class);
    i.addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_CLEAR_TOP
            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    i.putExtra("autoRestart", true);
    return i;
  }

  private static void notifyRelaunch(Context app, Intent i) {
    try {
      ensureChannel(app);
      PendingIntent pi =
          PendingIntent.getActivity(
              app, NOTIFY_ID, i, PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
      Notification.Builder b =
          Build.VERSION.SDK_INT >= 26
              ? new Notification.Builder(app, CHANNEL_ID)
              : new Notification.Builder(app);
      b.setSmallIcon(R.drawable.ic_nuni)
          .setContentTitle("누니담 액자를 다시 켭니다")
          .setContentText("메모리 부족으로 종료된 것으로 보여, 액자를 다시 열어요.")
          .setContentIntent(pi)
          .setAutoCancel(true)
          .setPriority(Notification.PRIORITY_HIGH);
      if (Build.VERSION.SDK_INT >= 21) b.setFullScreenIntent(pi, true);
      NotificationManager nm =
          (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
      if (nm != null) nm.notify(NOTIFY_ID, b.build());
    } catch (Exception ignored) {
    }
  }

  private static void ensureChannel(Context app) {
    if (Build.VERSION.SDK_INT < 26) return;
    try {
      NotificationManager nm = app.getSystemService(NotificationManager.class);
      if (nm == null) return;
      NotificationChannel ch =
          new NotificationChannel(CHANNEL_ID, "액자 다시 시작", NotificationManager.IMPORTANCE_HIGH);
      ch.setDescription("액자가 메모리 부족으로 꺼졌을 때 다시 켭니다.");
      nm.createNotificationChannel(ch);
    } catch (Exception ignored) {
    }
  }

  private static void scheduleAlarm(Context app) {
    try {
      AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
      if (am == null) return;
      PendingIntent pi = alarmIntent(app);
      long at = SystemClock.elapsedRealtime() + INTERVAL_MS;
      if (Build.VERSION.SDK_INT >= 23) {
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
      } else {
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
      }
    } catch (Exception ignored) {
    }
  }

  private static void scheduleWork(Context app) {
    try {
      PeriodicWorkRequest w =
          new PeriodicWorkRequest.Builder(FrameWatchdogWorker.class, 15, TimeUnit.MINUTES).build();
      WorkManager.getInstance(app)
          .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, w);
    } catch (Exception ignored) {
    }
  }

  private static PendingIntent alarmIntent(Context app) {
    Intent i = new Intent(app, FrameWatchdogReceiver.class);
    i.setAction(ACTION);
    return PendingIntent.getBroadcast(
        app, ALARM_REQ, i, PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
  }

  private static int immutableFlag() {
    return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
  }

  static boolean isScheduledOff(Context context) {
    try {
      return NuniApp.of(context).prefs.night();
    } catch (Exception e) {
      return false;
    }
  }

  private static SharedPreferences prefs(Context context) {
    return context.getApplicationContext().getSharedPreferences("nunidam", 0);
  }
}
