package co.kr.nuni.dam;

import java.util.concurrent.CopyOnWriteArrayList;

/** Forwards memory-trim events so caches and preloaded bitmaps can be dropped. */
public final class MemoryGuard {
  public interface Listener {
    void onTrimMemory(int level);
  }

  private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

  private MemoryGuard() {}

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
