package co.kr.nuni.dam;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;

public final class FrameSession implements PhotoStore.Listener {
  private final NuniApp a;
  private final FrameView view;
  private final Handler h = new Handler(Looper.getMainLooper());
  private final ExecutorService images = Executors.newSingleThreadExecutor();
  private List<Photo> deck = new ArrayList<>();
  private List<Quote> quotes = new ArrayList<>();
  private int index = -1, generation, quoteStep;
  private boolean active, loading, paused, powerPaused;
  private long next;
  private final Runnable tick =
      new Runnable() {
        public void run() {
          if (!active) return;
          view.tick(phrase());
          if (!paused && !powerPaused && !loading && SystemClock.elapsedRealtime() >= next) next();
          h.postDelayed(this, 1000);
        }
      };

  public FrameSession(Context c, FrameView v) {
    a = NuniApp.of(c);
    view = v;
  }

  public void start() {
    active = true;
    view.paused(false);
    a.store.listen(this);
    reload();
    h.post(tick);
  }

  public void stop() {
    active = false;
    generation++;
    h.removeCallbacksAndMessages(null);
    a.store.unlisten(this);
    images.shutdownNow();
    view.clear();
  }

  public boolean paused() {
    return paused;
  }

  public void pause() {
    paused = !paused;
    next = SystemClock.elapsedRealtime() + a.prefs.seconds() * 1000L;
    view.paused(paused);
  }

  public void setPowerPaused(boolean b) {
    if (powerPaused != b) {
      powerPaused = b;
      if (!b) next = SystemClock.elapsedRealtime() + a.prefs.seconds() * 1000L;
    }
  }

  public void previous() {
    if (deck.isEmpty()) return;
    index = (index - 2 + deck.size()) % deck.size();
    quoteStep = Math.max(0, quoteStep - 2);
    next();
  }

  public void next() {
    if (!active || loading || deck.isEmpty()) return;
    index = (index + 1) % deck.size();
    quoteStep++;
    show();
  }

  public void changed() {
    h.post(this::reload);
  }

  private void reload() {
    if (!active) return;
    int request = ++generation;
    loading = true;
    a.io.execute(
        () -> {
          List<Photo> list = a.db.photos().active();
          List<Quote> qs = a.db.photos().quotes();
          if (a.prefs.raw.getBoolean("favourites", false)) {
            Iterator<Photo> i = list.iterator();
            while (i.hasNext()) if (!i.next().favourite) i.remove();
          }
          Collections.shuffle(list);
          if (a.prefs.raw.getBoolean("memories", true))
            Collections.sort(list, (x, y) -> Boolean.compare(anniversary(y), anniversary(x)));
          h.post(
              () -> {
                if (!active || request != generation) return;
                String current =
                    index >= 0 && index < deck.size()
                        ? deck.get(index).id
                        : a.prefs.raw.getString("lastPhoto", "");
                deck = list;
                quotes = qs;
                index = -1;
                for (int i = 0; i < deck.size(); i++)
                  if (deck.get(i).id.equals(current)) {
                    index = i;
                    break;
                  }
                loading = false;
                if (deck.isEmpty()) {
                  view.clear();
                  next = Long.MAX_VALUE;
                } else {
                  if (index < 0) index = 0;
                  show();
                }
              });
        });
  }

  private void show() {
    if (!active || deck.isEmpty()) return;
    Photo p = deck.get(index);
    int ticket = ++generation;
    loading = true;
    images.execute(
        () -> {
          Bitmap b = a.store.load(p, a.prefs.low() ? 1280 : 2048);
          h.post(
              () -> {
                if (!active || ticket != generation) {
                  if (b != null) b.recycle();
                  return;
                }
                loading = false;
                if (b == null) {
                  next = SystemClock.elapsedRealtime() + 2000;
                  view.error();
                  return;
                }
                view.show(p, b, phrase());
                next = SystemClock.elapsedRealtime() + a.prefs.seconds() * 1000L;
                a.prefs.text("lastPhoto", p.id);
              });
        });
  }

  private String phrase() {
    String kind = a.prefs.quoteKind();
    if ("NONE".equals(kind)) return "";
    if (!a.prefs.phrase().isEmpty()) return a.prefs.phrase();
    List<Quote> filtered = new ArrayList<>();
    for (Quote q : quotes)
      if (Core.matchesQuoteKind(Core.parseQuote(q.text), kind)) filtered.add(q);
    if (filtered.isEmpty()) return "눈부신 순간을 담아, 누니담.";
    int idx =
        Core.pickQuoteIndex(
            filtered.size(),
            a.prefs.quoteCycle(),
            quoteStep,
            System.currentTimeMillis(),
            Calendar.getInstance().get(Calendar.DAY_OF_YEAR));
    return filtered.get(idx).text;
  }

  private static boolean anniversary(Photo p) {
    if (p.capturedAt <= 0) return false;
    Calendar n = Calendar.getInstance(), t = Calendar.getInstance();
    t.setTimeInMillis(p.capturedAt);
    return t.get(Calendar.YEAR) < n.get(Calendar.YEAR)
        && t.get(Calendar.MONTH) == n.get(Calendar.MONTH)
        && t.get(Calendar.DAY_OF_MONTH) == n.get(Calendar.DAY_OF_MONTH);
  }
}
