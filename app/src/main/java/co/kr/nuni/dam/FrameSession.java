package co.kr.nuni.dam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;

public final class FrameSession implements PhotoStore.Listener {
  private final NuniApp a;
  private final Context ctx;
  private final FrameView view;
  private final Handler h = new Handler(Looper.getMainLooper());
  private final ExecutorService images = Executors.newSingleThreadExecutor();
  private List<Photo> deck = new ArrayList<>();
  private List<Quote> quotes = new ArrayList<>();
  private List<TextItem> sceneTexts = new ArrayList<>();
  private Scene activeScene;
  private String currentSceneId = "";
  private int index = -1, generation, quoteStep;
  private boolean active, loading, paused, powerPaused;
  private long next, lastScheduleCheckMs;
  private boolean receiverRegistered;

  private final BroadcastReceiver timeReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          if (!active) return;
          checkScheduleTransition(true);
        }
      };

  private final Runnable tick =
      new Runnable() {
        public void run() {
          if (!active) return;
          long nowElapsed = SystemClock.elapsedRealtime();
          if (nowElapsed - lastScheduleCheckMs >= 15_000L) {
            lastScheduleCheckMs = nowElapsed;
            checkScheduleTransition(false);
          }
          view.tick(phrase());
          if (!paused && !powerPaused && !loading && nowElapsed >= next) next();
          h.postDelayed(this, 1000);
        }
      };

  public FrameSession(Context c, FrameView v) {
    ctx = c;
    a = NuniApp.of(c);
    view = v;
  }

  public Scene activeScene() {
    return activeScene;
  }

  public String currentSceneId() {
    return currentSceneId;
  }

  public void start() {
    active = true;
    view.paused(false);
    a.store.listen(this);
    registerTimeReceiver();
    reload();
    h.post(tick);
  }

  public void stop() {
    active = false;
    generation++;
    h.removeCallbacksAndMessages(null);
    unregisterTimeReceiver();
    a.store.unlisten(this);
    images.shutdownNow();
    view.clear();
  }

  private void registerTimeReceiver() {
    if (receiverRegistered) return;
    try {
      IntentFilter f = new IntentFilter();
      f.addAction(Intent.ACTION_TIME_CHANGED);
      f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
      f.addAction(Intent.ACTION_DATE_CHANGED);
      ctx.registerReceiver(timeReceiver, f);
      receiverRegistered = true;
    } catch (Exception ignored) {
    }
  }

  private void unregisterTimeReceiver() {
    if (!receiverRegistered) return;
    try {
      ctx.unregisterReceiver(timeReceiver);
    } catch (Exception ignored) {
    }
    receiverRegistered = false;
  }

  private void checkScheduleTransition(boolean immediate) {
    if (!active || a.studio == null || !a.prefs.studioScheduleEnabled()) return;
    a.io.execute(
        () -> {
          Core.ScheduleEvaluation eval =
              a.studio.evaluateCurrentSchedule(System.currentTimeMillis());
          if (!eval.activeSceneId.isEmpty() && !eval.activeSceneId.equals(currentSceneId)) {
            h.post(
                () -> {
                  if (!active) return;
                  if (immediate) {
                    reload();
                  } else {
                    // Apply at natural transition boundary
                    reload();
                  }
                });
          }
        });
  }

  public boolean paused() {
    return paused;
  }

  public void pause() {
    paused = !paused;
    next = SystemClock.elapsedRealtime() + currentDurationSec() * 1000L;
    view.paused(paused);
  }

  public void setPowerPaused(boolean b) {
    if (powerPaused != b) {
      powerPaused = b;
      if (!b) next = SystemClock.elapsedRealtime() + currentDurationSec() * 1000L;
    }
  }

  private int currentDurationSec() {
    if (activeScene != null && activeScene.seconds >= 5) return activeScene.seconds;
    return a.prefs.seconds();
  }

  public void previous() {
    if (deck.isEmpty()) return;
    index = (index - 2 + deck.size()) % deck.size();
    quoteStep = Math.max(0, quoteStep - 2);
    next();
  }

  public void next() {
    if (!active || loading) return;
    if (deck.isEmpty()) {
      quoteStep++;
      view.tick(phrase());
      next = SystemClock.elapsedRealtime() + currentDurationSec() * 1000L;
      return;
    }
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
          Scene resolvedScene = null;
          List<Photo> list;
          List<TextItem> tItems = new ArrayList<>();
          if (a.studio != null) {
            a.studio.ensureSeeded();
            if (a.prefs.studioScheduleEnabled()) {
              Core.ScheduleEvaluation eval =
                  a.studio.evaluateCurrentSchedule(System.currentTimeMillis());
              resolvedScene = a.studio.dao().scene(eval.activeSceneId);
              if (resolvedScene != null) {
                list = a.studio.resolvePhotosForScene(resolvedScene.id);
                tItems = a.studio.resolveTextsForScene(resolvedScene.id);
              } else {
                list = a.db.photos().active();
                tItems = a.studio.dao().enabledTextItems();
              }
            } else {
              list = a.db.photos().active();
              tItems = a.studio.dao().enabledTextItems();
            }
          } else {
            list = a.db.photos().active();
          }

          List<Quote> qs = a.db.photos().quotes();
          if (resolvedScene == null && a.prefs.raw.getBoolean("favourites", false)) {
            Iterator<Photo> i = list.iterator();
            while (i.hasNext()) if (!i.next().favourite) i.remove();
          }
          if (resolvedScene == null) {
            Collections.shuffle(list);
          }
          if (a.prefs.raw.getBoolean("memories", true))
            Collections.sort(list, (x, y) -> Boolean.compare(anniversary(y), anniversary(x)));

          Scene finalScene = resolvedScene;
          List<Photo> finalList = list;
          List<TextItem> finalTexts = tItems;
          h.post(
              () -> {
                if (!active || request != generation) return;
                activeScene = finalScene;
                currentSceneId = finalScene != null ? finalScene.id : "";
                sceneTexts = finalTexts;
                if (finalScene != null) {
                  view.setOverrideConfig(FrameView.Config.fromScene(finalScene, a.prefs));
                } else {
                  view.setOverrideConfig(null);
                }
                String current =
                    index >= 0 && index < deck.size()
                        ? deck.get(index).id
                        : a.prefs.raw.getString("lastPhoto", "");
                deck = finalList;
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
                  view.tick(phrase());
                  next = SystemClock.elapsedRealtime() + currentDurationSec() * 1000L;
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
                next = SystemClock.elapsedRealtime() + currentDurationSec() * 1000L;
                a.prefs.text("lastPhoto", p.id);
              });
        });
  }

  private String phrase() {
    if (activeScene != null) {
      if ("NONE".equals(activeScene.textDisplayMode)) return "";
      int everyN = Math.max(1, activeScene.textEveryN);
      if (everyN > 1 && (quoteStep % everyN) != 0 && !"DEDICATED".equals(activeScene.textDisplayMode)) {
        return "";
      }
      if (!sceneTexts.isEmpty()) {
        int idx =
            Core.pickQuoteIndex(
                sceneTexts.size(),
                activeScene.quoteCycle,
                quoteStep / everyN,
                System.currentTimeMillis(),
                Calendar.getInstance().get(Calendar.DAY_OF_YEAR));
        if (idx >= 0 && idx < sceneTexts.size()) {
          TextItem ti = sceneTexts.get(idx);
          String authorStr = ti.author;
          if (!ti.title.isEmpty() && !ti.author.contains(ti.title)) {
            authorStr = ti.author.isEmpty() ? ti.title : (ti.author + " · " + ti.title);
          }
          String k = "POEM".equals(ti.kind) || ti.body.indexOf('\n') >= 0 ? "POEM" : "QUOTE";
          return new Core.QuoteEntry(k, ti.body, authorStr).encode();
        }
      }
    }
    String kind = a.prefs.quoteKind();
    if ("NONE".equals(kind)) return "";
    if (!a.prefs.phrase().isEmpty()) return a.prefs.phrase();
    if (!sceneTexts.isEmpty()) {
      List<TextItem> filteredTexts = new ArrayList<>();
      for (TextItem ti : sceneTexts) {
        String k = "POEM".equals(ti.kind) || ti.body.indexOf('\n') >= 0 ? "POEM" : "QUOTE";
        if ("ALL".equals(kind) || kind.equals(k)) filteredTexts.add(ti);
      }
      if (!filteredTexts.isEmpty()) {
        int idx =
            Core.pickQuoteIndex(
                filteredTexts.size(),
                a.prefs.quoteCycle(),
                quoteStep,
                System.currentTimeMillis(),
                Calendar.getInstance().get(Calendar.DAY_OF_YEAR));
        if (idx >= 0 && idx < filteredTexts.size()) {
          TextItem ti = filteredTexts.get(idx);
          String authorStr = ti.author;
          if (!ti.title.isEmpty() && !ti.author.contains(ti.title)) {
            authorStr = ti.author.isEmpty() ? ti.title : (ti.author + " · " + ti.title);
          }
          String k = "POEM".equals(ti.kind) || ti.body.indexOf('\n') >= 0 ? "POEM" : "QUOTE";
          return new Core.QuoteEntry(k, ti.body, authorStr).encode();
        }
      }
    }
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
