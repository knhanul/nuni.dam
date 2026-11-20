package co.kr.nuni.dam;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;
import java.util.concurrent.CopyOnWriteArrayList;

/** Forwards memory-trim events so caches and preloaded bitmaps can be dropped. */
public final class MemoryGuard {
  public interface Listener {
    void onTrimMemory(int level);
  }

  private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

  private static volatile int lastLevel;
  private static volatile long lastAt;

  private MemoryGuard() {}

  /** Remembers the latest trim level so background work can back off. */
  public static void note(int level) {
    lastLevel = level;
    lastAt = SystemClock.elapsedRealtime();
  }

  /** True when the system asked us to trim at RUNNING_LOW or worse within the last few minutes. */
  public static boolean underPressure() {
    long at = lastAt;
    if (at == 0L) return false;
    return Core.memoryPressure(lastLevel, SystemClock.elapsedRealtime() - at);
  }

  /** True when the app was recently trimmed hard, or Android itself reports low memory. */
  public static boolean shouldBackOff(Context c) {
    if (underPressure()) return true;
    try {
      ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
      if (am == null) return false;
      ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
      am.getMemoryInfo(mi);
      return mi.lowMemory;
    } catch (Exception e) {
      return false;
    }
  }

  public static void register(Listener listener) {
    if (listener != null && !listeners.contains(listener)) listeners.add(listener);
  }

  public static void unregister(Listener listener) {
    listeners.remove(listener);
  }

  public static void dispatch(int level) {
    for (Listener listener : listeners) {
      try {
        listener.onTrimMemory(level);
      } catch (Throwable ignored) {
      }
    }
  }
}
