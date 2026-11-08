package co.kr.nuni.dam;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.text.*;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.*;

public final class FrameView extends View {
  /** Shared rendering configuration for fullscreen playback and live previews (Section 6.4 & 10). */
  public static final class Config {
    public String mode = "MAGAZINE"; // PHOTO, MAGAZINE, BOARD, TEXT_ONLY, SPLIT
    public String theme = "MILK"; // MILK, INK, SAGE
    public boolean showClock = true;
    public boolean showWeather = false;
    public boolean showCaptureDate = true;
    public boolean quoteOnPhoto = true;
    public String textDisplayMode = "OVERLAY"; // OVERLAY, DEDICATED, SPLIT, NONE
    public String quoteKind = "ALL";
    public String quoteCycle = "PHOTO";
    public int fontScale = 100;
    public int seconds = 45;
    public boolean animate = true;
    public String transitionEffect = "ZOOM"; // ZOOM, FADE, NONE
    public String textAlign = "LEFT"; // LEFT, CENTER, RIGHT
    public int scrimOpacity = 70;
    public String customTextColor = "";

    public static Config from(Prefs p) {
      Config c = new Config();
      c.mode = p.mode();
      c.theme = p.theme();
      c.showClock = p.showClock();
      c.showCaptureDate = p.showCaptureDate();
      c.quoteOnPhoto = p.quoteOnPhoto();
      c.textDisplayMode = p.quoteOnPhoto() ? "OVERLAY" : "NONE";
      c.quoteKind = p.quoteKind();
      c.quoteCycle = p.quoteCycle();
      c.fontScale = p.fontScale();
      c.seconds = p.seconds();
      c.animate = p.animate();
      c.transitionEffect = c.animate ? "ZOOM" : "NONE";
      return c;
    }

    public static Config fromScene(Scene sc, Prefs fallback) {
      if (sc == null) return from(fallback);
      Config c = new Config();
      c.mode = sc.layout;
      c.theme = sc.theme;
      c.showClock = sc.showClock;
      c.showWeather = sc.showWeather;
      c.showCaptureDate = sc.showCaptureDate;
      c.textDisplayMode = sc.textDisplayMode;
      c.quoteOnPhoto = !"NONE".equals(sc.textDisplayMode);
      c.quoteKind = "ALL";
      c.quoteCycle = sc.quoteCycle;
      c.fontScale = sc.fontScale;
      c.seconds = sc.seconds;
      c.transitionEffect = sc.transitionEffect;
      c.animate = !"NONE".equals(sc.transitionEffect) && !fallback.low();
      c.textAlign = sc.textAlign;
      c.scrimOpacity = sc.scrimOpacity;
      c.customTextColor = sc.textColor;
      return c;
    }

    public void applyTo(Prefs p) {
      p.raw
          .edit()
          .putString("mode", mode)
          .putString("theme", theme)
          .putBoolean("showClock", showClock)
          .putBoolean("showCaptureDate", showCaptureDate)
          .putBoolean("quoteOnPhoto", quoteOnPhoto)
          .putString("quoteKind", quoteKind)
          .putString("quoteCycle", quoteCycle)
          .putInt("fontScale", fontScale)
          .putInt("seconds", seconds)
          .putBoolean("animate", animate)
          .apply();
    }
  }

  private final Paint p = new Paint(3);
  private static final Typeface SERIF = Typeface.create("serif", Typeface.NORMAL);
  private static final Typeface SANS = Typeface.create("sans-serif-light", Typeface.NORMAL);
  private static final String[] WEEK = {"일", "월", "화", "수", "목", "금", "토"};
  private final RectF photoBox = new RectF(), boardBox = new RectF(), target = new RectF();
  private final Rect src = new Rect();
  private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm", Locale.ROOT);
  private final SimpleDateFormat dayFormat = new SimpleDateFormat("M월 d일 EEEE", Locale.KOREAN);
  private final SimpleDateFormat captureFormat = new SimpleDateFormat("yyyy. MM. dd", Locale.KOREA);
  private final android.util.LruCache<String, StaticLayout> layouts =
      new android.util.LruCache<>(16);
  private long minuteStamp = -1;
  private String clockText = "", dayText = "", captureText = "";
  private int monthStart, monthLast, monthToday;
  private LinearGradient scrim, deepScrim;
  private final Prefs prefs;
  private Config overrideConfig;
  private boolean previewMode;
  private boolean samplePreview;
  private boolean dimmedOverlay;
  private String dimmedStatus = "";
  private Photo photo;
  private Bitmap image;
  private Bitmap brandKrLogo;
  private double[] faces = new double[0];
  private String phrase = "", error = "";
  private long began;
  private boolean paused;

  public FrameView(Context c) {
    super(c);
    prefs = NuniApp.of(c).prefs;
    setContentDescription("누니담 사진 액자");
    setFocusable(true);
    try {
      brandKrLogo = BitmapFactory.decodeResource(c.getResources(), R.drawable.nunidam_kr_logo);
    } catch (Throwable ignored) {
    }
  }

  public void setPreviewMode(boolean preview) {
    previewMode = preview;
    invalidate();
  }

  public void setOverrideConfig(Config cfg) {
    overrideConfig = cfg;
    layouts.evictAll();
    invalidate();
  }

  public void setDimmedOverlay(boolean dimmed, String statusText) {
    if (dimmedOverlay != dimmed || !Objects.equals(dimmedStatus, statusText)) {
      dimmedOverlay = dimmed;
      dimmedStatus = statusText == null ? "" : statusText;
      invalidate();
    }
  }

  public boolean isDimmedOverlay() {
    return dimmedOverlay;
  }

  public void show(Photo data, Bitmap bitmap, String text) {
    samplePreview = false;
    photo = data;
    image = bitmap;
    faces = data != null ? FaceAnalyzer.boxes(data.faces) : new double[0];
    captureText =
        data != null && data.capturedAt > 0
            ? captureFormat.format(new Date(data.capturedAt))
            : "소중한 순간";
    phrase = text == null ? "" : text;
    error = "";
    began = SystemClock.elapsedRealtime();
    invalidate();
  }

  public void showSample(String text) {
    samplePreview = true;
    photo = new Photo();
    photo.id = "sample";
    photo.name = "예시 미리보기";
    photo.width = 1600;
    photo.height = 1200;
    photo.accent = Ui.PRIMARY;
    image = null;
    faces = new double[0];
    captureText = "예시 미리보기 · 보관함에 추가되지 않음";
    phrase = text == null || text.isEmpty() ? "눈이 머무는 곳에 마음도 잠시 쉬어갑니다.|누니담" : text;
    error = "";
    began = SystemClock.elapsedRealtime();
    invalidate();
  }

  public void tick(String text) {
    phrase = text == null ? "" : text;
    invalidate();
  }

  public void paused(boolean b) {
    paused = b;
    invalidate();
  }

  public void error() {
    error = "일부 사진을 읽지 못해 다음 사진을 준비합니다.";
    invalidate();
  }

  public void clear() {
    samplePreview = false;
    photo = null;
    image = null;
    invalidate();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    scrim =
        new LinearGradient(
            0, h * .60f, 0, Math.max(1, h), 0x00000000, 0xb8000000, Shader.TileMode.CLAMP);
    deepScrim =
        new LinearGradient(
            0, h * .40f, 0, Math.max(1, h), 0x00000000, 0xcc000000, Shader.TileMode.CLAMP);
    layouts.evictAll();
  }

  protected void onDraw(Canvas c) {
    super.onDraw(c);
    float w = getWidth(), h = getHeight(), s = Math.min(w, h) / 700f;
    if (w <= 0 || h <= 0) return;
    Config cfg = overrideConfig != null ? overrideConfig : Config.from(prefs);
    float fs = Math.max(0.85f, Math.min(1.6f, cfg.fontScale / 100f));
    boolean dark = "INK".equals(cfg.theme);
    boolean sage = "SAGE".equals(cfg.theme);
    int background = dark ? Ui.DARK_BG : sage ? 0xffe4f0ff : Ui.BG,
        ink = dark ? Ui.DARK_TEXT : Ui.INK,
        muted = dark ? Ui.DARK_SECONDARY : Ui.MUTED;
    updateClock();
    c.drawColor(background);

    boolean hasContent =
        samplePreview
            || (photo != null && image != null)
            || ("DEDICATED".equals(cfg.textDisplayMode) && !phrase.isEmpty())
            || ("TEXT_ONLY".equals(cfg.mode) && !phrase.isEmpty());
    if (!hasContent) {
      if (brandKrLogo != null && !brandKrLogo.isRecycled()) {
        float krH = 74f * s;
        float krW = krH * 3f;
        float x0 = w * .09f;
        float y0 = h * .28f;
        p.reset();
        p.setFilterBitmap(true);
        target.set(x0, y0, x0 + krW, y0 + krH);
        c.drawBitmap(brandKrLogo, null, target, p);
      } else {
        text(c, "누니담", w * .09f, h * .38f, 68 * s, ink, true);
      }
      wrap(
          c,
          "사진을 담으면, 이곳에 우리의 풍경이 펼쳐집니다.",
          w * .09f,
          h * .47f,
          w * .82f,
          22 * s * fs,
          muted,
          3);
      text(
          c,
          previewMode ? "아래에서 사진을 선택해 첫 액자를 시작해 보세요" : "화면을 터치해 메뉴를 열어주세요",
          w * .09f,
          h * .72f,
          17 * s,
          muted,
          false);
    } else if ("TEXT_ONLY".equals(cfg.mode) || "DEDICATED".equals(cfg.textDisplayMode)) {
      drawDedicatedTextScreen(c, w, h, s, fs, ink, muted, cfg);
    } else if ("PHOTO".equals(cfg.mode)
        || ("MAGAZINE".equals(cfg.mode)
            && !"SPLIT".equals(cfg.textDisplayMode)
            && photo != null
            && photo.width >= photo.height
            && w >= h * .85f)) {
      photoBox.set(0, 0, w, h);
      drawPhotoOrSample(c, photoBox, cfg);
      String rawCaption =
          photo != null && !photo.note.isEmpty()
              ? photo.note
              : (cfg.quoteOnPhoto && !"NONE".equals(cfg.quoteKind) && !"NONE".equals(cfg.textDisplayMode) ? phrase : "");
      Core.QuoteEntry entry = Core.parseQuote(rawCaption);
      boolean hasText = !entry.body.isEmpty() || cfg.showClock || cfg.showCaptureDate;
      if (hasText) {
        boolean tallScrim = !entry.body.isEmpty() && (entry.isPoem() || entry.body.length() > 45);
        p.setShader(tallScrim ? deepScrim : scrim);
        c.drawRect(0, h * (tallScrim ? .40f : .60f), w, h, p);
        p.setShader(null);
      }
      float sh = previewMode ? 0 : shift(s);
      if (w > h && !entry.body.isEmpty() && cfg.showClock) {
        text(c, time(), w * .055f + sh, h * .88f + sh, 48 * s * fs, Color.WHITE, false);
        if (cfg.showCaptureDate)
          text(c, captureText, w * .055f + sh, h * .935f + sh, 14 * s * fs, 0xd8ffffff, false);
        drawLiteraryOverlay(
            c, entry, w * .34f + sh, w * .945f, h * .935f + sh, s * fs, 0xffffffff, 0xd8e8ede5);
      } else {
        float bottomY = h * .93f + sh;
        if (cfg.showCaptureDate) {
          text(c, captureText, w * .055f + sh, bottomY, 13.5f * s * fs, 0xd0ffffff, false);
          bottomY -= 22 * s * fs;
        }
        if (!entry.body.isEmpty()) {
          bottomY =
              drawLiteraryOverlay(
                      c,
                      entry,
                      w * .055f + sh,
                      w * .92f,
                      bottomY,
                      s * fs,
                      0xffffffff,
                      0xd8e8ede5)
                  - 16 * s * fs;
        }
        if (cfg.showClock)
          text(c, time(), w * .055f + sh, bottomY, 46 * s * fs, Color.WHITE, false);
      }
    } else {
      boolean landscape = w > h;
      if (landscape) {
        photoBox.set(24 * s, 24 * s, w * .60f - 12 * s, h - 24 * s);
        boardBox.set(w * .63f, 36 * s, w - 32 * s, h - 36 * s);
      } else {
        photoBox.set(20 * s, 20 * s, w - 20 * s, h * .54f);
        boardBox.set(32 * s, h * .58f, w - 32 * s, h - 24 * s);
      }
      drawPhotoOrSample(c, photoBox, cfg);
      board(c, boardBox, ink, muted, s, fs, cfg, "BOARD".equals(cfg.mode), landscape);
    }

    if (samplePreview) {
      float bw = 180 * s, bh = 28 * s;
      target.set(16 * s, 16 * s, 16 * s + bw, 16 * s + bh);
      p.setColor(0xb80b1528);
      c.drawRoundRect(target, 8 * s, 8 * s, p);
      text(c, "예시 화면 미리보기", 28 * s, 35 * s, 13 * s, Color.WHITE, false);
    }

    if (!error.isEmpty()) {
      p.setColor(0xb0000000);
      c.drawRect(0, 0, w, 34 * s, p);
      text(c, error, 16 * s, 24 * s, 16 * s, Color.WHITE, false);
    }
    boolean isDim = !previewMode && (prefs.night() || dimmedOverlay);
    if (isDim) {
      p.setColor(0xe5000000);
      c.drawRect(0, 0, w, h, p);
      String msg =
          !dimmedStatus.isEmpty()
              ? dimmedStatus
              : "잠시 쉬는 중 · 화면을 터치하면 밝아집니다.";
      text(c, msg, w * .06f, h * .92f, 15 * s, 0x99edefe8, false);
    }
    if (!previewMode && cfg.animate && !isDim && !paused && image != null) {
      postInvalidateDelayed(40);
    }
  }

  private void drawPhotoOrSample(Canvas c, RectF dst, Config cfg) {
    if (samplePreview || image == null) {
      c.save();
      c.clipRect(dst);
      LinearGradient g =
          new LinearGradient(
              dst.left,
              dst.top,
              dst.right,
              dst.bottom,
              0xff026ef8,
              0xff7ad2ff,
              Shader.TileMode.CLAMP);
      p.setShader(g);
      c.drawRect(dst, p);
      p.setShader(null);
      p.setColor(0x44ffffff);
      c.drawCircle(dst.left + dst.width() * .74f, dst.top + dst.height() * .28f, dst.height() * .14f, p);
      Path hills = new Path();
      hills.moveTo(dst.left, dst.bottom);
      hills.lineTo(dst.left, dst.top + dst.height() * .68f);
      hills.quadTo(
          dst.left + dst.width() * .32f,
          dst.top + dst.height() * .50f,
          dst.left + dst.width() * .62f,
          dst.top + dst.height() * .66f);
      hills.quadTo(
          dst.left + dst.width() * .82f,
          dst.top + dst.height() * .56f,
          dst.right,
          dst.top + dst.height() * .64f);
      hills.lineTo(dst.right, dst.bottom);
      hills.close();
      p.setColor(0x440b1528);
      c.drawPath(hills, p);
      c.restore();
      return;
    }
    drawPhoto(c, dst, cfg);
  }

  private void drawPhoto(Canvas c, RectF dst, Config cfg) {
    p.setColor(0xff0b1528);
    c.drawRect(dst, p);
    if (image == null) return;
    double
        progress =
            Math.min(1, (SystemClock.elapsedRealtime() - began) / (Math.max(10, cfg.seconds) * 1000d)),
        zoom =
            !previewMode && cfg.animate && !paused && !prefs.night() && !dimmedOverlay
                ? 1 + .025 * (.5 - .5 * Math.cos(progress * Math.PI))
                : 1;
    Core.Crop crop =
        Core.crop(
            image.getWidth(),
            image.getHeight(),
            dst.width(),
            dst.height(),
            faces,
            photo.focusX,
            photo.focusY,
            zoom);
    target.set(dst);
    if (crop.contain) {
      float scale = Math.min(dst.width() / image.getWidth(), dst.height() / image.getHeight()),
          w = image.getWidth() * scale,
          h = image.getHeight() * scale;
      target.set(
          dst.centerX() - w / 2,
          dst.centerY() - h / 2,
          dst.centerX() + w / 2,
          dst.centerY() + h / 2);
      src.set(0, 0, image.getWidth(), image.getHeight());
    } else
      src.set(
          (int) Math.round(crop.x * image.getWidth()),
          (int) Math.round(crop.y * image.getHeight()),
          (int) Math.round((crop.x + crop.w) * image.getWidth()),
          (int) Math.round((crop.y + crop.h) * image.getHeight()));
    c.save();
    c.clipRect(dst);
    p.setColor(Color.WHITE);
    c.drawBitmap(image, src, target, p);
    c.restore();
  }

  private void board(
      Canvas c,
      RectF b,
      int ink,
      int muted,
      float s,
      float fs,
      Config cfg,
      boolean calendar,
      boolean landscape) {
    c.save();
    c.clipRect(b);
    float sh = previewMode ? 0 : shift(s);
    float x = b.left + sh, y = b.top + sh, width = b.width() - 8 * s;
    String rawText =
        !photo.note.isEmpty() ? photo.note : ("NONE".equals(cfg.quoteKind) ? "" : phrase);
    Core.QuoteEntry entry = Core.parseQuote(rawText);
    boolean compact = b.height() < 260 * s || width < 210 * s;
    if (calendar && !landscape && !compact) {
      float col = width * .45f;
      float cy = cfg.showClock ? clock(c, x, y, col, ink, muted, s, fs) : y + 18 * s;
      float calY =
          calendar(c, x + col + 18 * s, y + 18 * s, width - col - 18 * s, ink, muted, s * .85f);
      float topY = Math.max(cy, calY) + 12 * s;
      drawBoardQuote(c, entry, x, topY, width, b.bottom - 28 * s, s * fs, ink, muted);
    } else {
      if (cfg.showClock) {
        y = clock(c, x, y, width, ink, muted, s, fs);
        p.setColor(Ui.PRIMARY);
        c.drawRect(x, y, x + Math.min(64 * s, width * .25f), y + 3 * s, p);
        y += 20 * s;
      }
      if (calendar && !compact) y = calendar(c, x, y, width, ink, muted, s) + 18 * s;
      drawBoardQuote(c, entry, x, y, width, b.bottom - 28 * s, s * fs, ink, muted);
    }
    if (cfg.showCaptureDate) {
      String foot =
          captureText
              + (calendar ? "  ·  이번 달 달력" : "")
              + (paused ? "  ·  일시 정지" : "");
      text(c, foot, x, b.bottom - 8 * s, 12.5f * s, muted, false);
    }
    c.restore();
  }

  private void drawDedicatedTextScreen(
      Canvas c, float w, float h, float s, float fs, int ink, int muted, Config cfg) {
    float sh = previewMode ? 0 : shift(s);
    float marginX = w * .11f + sh;
    float boxW = w * .78f;
    // Subtle decorative frame
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(Math.max(1f, 1.5f * s));
    p.setColor(0x33626d65);
    c.drawRoundRect(
        w * .055f, h * .07f, w * .945f, h * .93f, 20 * s, 20 * s, p);
    p.setStyle(Paint.Style.FILL);

    if (cfg.showClock) {
      text(c, time() + "  ·  " + dayText, marginX, h * .16f + sh, 16 * s * fs, muted, false);
    }
    Core.QuoteEntry entry = Core.parseQuote(phrase);
    java.util.List<String> pages = Core.paginatePoem(entry.body, 7);
    int pageIdx =
        pages.size() <= 1
            ? 0
            : (int) ((Math.max(0L, SystemClock.elapsedRealtime() - began) / 6000L) % pages.size());
    String pageBody = pages.get(pageIdx);
    float fontSize = (entry.isPoem() ? 22f : 26f) * s * fs;
    StaticLayout l = layout(pageBody, boxW, fontSize, ink, 8);
    float topY = Math.max(h * .23f, (h - l.getHeight()) * .46f) + sh;
    c.save();
    c.translate(marginX, topY);
    l.draw(c);
    c.restore();
    float afterY = topY + l.getHeight() + 26 * s * fs;
    if (!entry.author.isEmpty()) {
      text(c, "— " + entry.author, marginX, Math.min(h * .86f, afterY), 16f * s * fs, muted, true);
    }
    if (pages.size() > 1) {
      text(
          c,
          (pageIdx + 1) + " / " + pages.size(),
          w * .88f,
          h * .88f,
          13f * s,
          muted,
          false);
    }
  }

  private void drawBoardQuote(
      Canvas c,
      Core.QuoteEntry entry,
      float x,
      float topY,
      float width,
      float maxBottom,
      float s,
      int ink,
      int muted) {
    if (entry.body.isEmpty() || maxBottom <= topY + 20 * s) return;
    float avail = maxBottom - topY - (entry.author.isEmpty() ? 0 : 22 * s);
    boolean poem = entry.isPoem() || entry.body.indexOf('\n') >= 0;
    float fontSize = (poem ? 18f : 20f) * s;
    int maxLines = Math.max(2, Math.min(poem ? 7 : 4, (int) (avail / (fontSize * 1.32f))));
    java.util.List<String> pages = Core.paginatePoem(entry.body, maxLines);
    int pageIdx =
        pages.size() <= 1
            ? 0
            : (int) ((Math.max(0L, SystemClock.elapsedRealtime() - began) / 6000L) % pages.size());
    String bodyText = pages.get(pageIdx);
    StaticLayout l = layout(bodyText, width, fontSize, ink, maxLines);
    c.save();
    c.translate(x, topY);
    l.draw(c);
    c.restore();
    String authorLine = entry.author;
    if (pages.size() > 1) {
      authorLine =
          (authorLine.isEmpty() ? "" : authorLine + "  ")
              + "("
              + (pageIdx + 1)
              + "/"
              + pages.size()
              + ")";
    }
    if (!authorLine.isEmpty()) {
      float ay = Math.min(maxBottom - 4 * s, topY + l.getHeight() + 17 * s);
      text(c, "— " + authorLine, x, ay, 13f * s, muted, false);
    }
  }

  private float drawLiteraryOverlay(
      Canvas c,
      Core.QuoteEntry entry,
      float left,
      float right,
      float bottomY,
      float s,
      int bodyColor,
      int authorColor) {
    float width = Math.max(1, right - left);
    boolean poem = entry.isPoem() || entry.body.indexOf('\n') >= 0;
    float fontSize = (poem ? 18f : 20f) * s;
    int maxLines = poem ? 6 : 3;
    java.util.List<String> pages = Core.paginatePoem(entry.body, maxLines);
    int pageIdx =
        pages.size() <= 1
            ? 0
            : (int) ((Math.max(0L, SystemClock.elapsedRealtime() - began) / 6000L) % pages.size());
    String pageText = pages.get(pageIdx);
    float curBottom = bottomY;
    String authorLine = entry.author;
    if (pages.size() > 1) {
      authorLine =
          (authorLine.isEmpty() ? "" : authorLine + " · ")
              + (pageIdx + 1)
              + "/"
              + pages.size();
    }
    if (!authorLine.isEmpty()) {
      text(c, "— " + authorLine, left, curBottom, 13.5f * s, authorColor, false);
      curBottom -= 20 * s;
    }
    StaticLayout l = layout(pageText, width, fontSize, bodyColor, maxLines);
    float topY = curBottom - l.getHeight();
    p.setColor(0xcc7ad2ff);
    c.drawRect(left - 12 * s, topY + 3 * s, left - 9 * s, curBottom - 2 * s, p);
    c.save();
    c.translate(left, topY);
    l.draw(c);
    c.restore();
    return topY;
  }

  private float clock(
      Canvas c, float x, float y, float w, int ink, int muted, float s, float fs) {
    text(c, "N U N I   D A M", x, y + 15 * s, 12.5f * s, muted, false);
    y += 46 * s;
    float size = Math.min(w / 3f, 78 * s * fs);
    text(c, time(), x, y + size, size, ink, false);
    y += size + 25 * s;
    text(c, dayText, x, y, 16.5f * s * fs, muted, false);
    return y + 26 * s;
  }

  private float calendar(Canvas c, float x, float y, float w, int ink, int muted, float s) {
    int start = monthStart, last = monthLast, today = monthToday;
    float cell = w / 7, step = 25 * s;
    for (int i = 0; i < 7; i++)
      text(c, WEEK[i], x + i * cell + cell * .25f, y, 11.5f * s, muted, false);
    y += step;
    for (int d = 1; d <= last; d++) {
      int pos = start + d - 1;
      float dx = x + pos % 7 * cell + cell * .25f, dy = y + pos / 7 * step;
      if (d == today) {
        p.setColor(Ui.PRIMARY);
        c.drawCircle(dx + 7 * s, dy - 4.5f * s, 11.5f * s, p);
      }
      text(c, "" + d, dx, dy, 11.5f * s, d == today ? Color.WHITE : muted, false);
    }
    return y + ((start + last - 1) / 7 + 1) * step;
  }

  private void updateClock() {
    long stamp = System.currentTimeMillis() / 60000;
    if (stamp == minuteStamp) return;
    minuteStamp = stamp;
    Date now = new Date();
    clockFormat.setTimeZone(TimeZone.getDefault());
    dayFormat.setTimeZone(TimeZone.getDefault());
    clockText = clockFormat.format(now);
    dayText = dayFormat.format(now);
    Calendar month = Calendar.getInstance();
    monthToday = month.get(Calendar.DAY_OF_MONTH);
    monthLast = month.getActualMaximum(Calendar.DAY_OF_MONTH);
    month.set(Calendar.DAY_OF_MONTH, 1);
    monthStart = month.get(Calendar.DAY_OF_WEEK) - 1;
  }

  private String time() {
    return clockText;
  }

  private float shift(float s) {
    return ((System.currentTimeMillis() / 900000L) % 5 - 2) * 2 * s;
  }

  private void text(Canvas c, String t, float x, float y, float size, int color, boolean serif) {
    p.setColor(color);
    p.setTextSize(size);
    p.setTypeface(serif ? SERIF : SANS);
    c.drawText(t, x, y, p);
  }

  private StaticLayout layout(String t, float w, float size, int color, int lines) {
    String key = t + "\u0000" + (int) w + ":" + size + ":" + color + ":" + lines;
    StaticLayout l = layouts.get(key);
    if (l == null) {
      TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
      paint.setColor(color);
      paint.setTextSize(size);
      paint.setTypeface(SERIF);
      l =
          StaticLayout.Builder.obtain(t, 0, t.length(), paint, Math.max(1, (int) w))
              .setIncludePad(false)
              .setLineSpacing(size * .24f, 1)
              .setMaxLines(lines)
              .setEllipsize(TextUtils.TruncateAt.END)
              .build();
      layouts.put(key, l);
    }
    return l;
  }

  private void wrap(
      Canvas c, String t, float x, float y, float w, float size, int color, int lines) {
    StaticLayout l = layout(t, w, size, color, lines);
    c.save();
    c.translate(x, y);
    l.draw(c);
    c.restore();
  }
}
