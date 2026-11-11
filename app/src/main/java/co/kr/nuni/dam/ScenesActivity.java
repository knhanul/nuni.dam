package co.kr.nuni.dam;

import android.app.*;
import android.graphics.*;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class ScenesActivity extends Activity implements PhotoStore.Listener {
  private NuniApp a;
  private boolean alive;
  private Controller controller;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    alive = true;
    a = NuniApp.of(this);
    getWindow().setStatusBarColor(Ui.BG);
    getWindow().setNavigationBarColor(Ui.BG);
    LinearLayout root = Ui.column(this);
    root.setBackgroundColor(Ui.BG);
    int pad = Ui.dp(this, Ui.outerPaddingDp(getResources().getConfiguration().screenWidthDp));
    root.setPadding(pad, Ui.dp(this, 16), pad, Ui.dp(this, 16));
    setContentView(root);
    root.setOnApplyWindowInsetsListener(
        (v, i) -> {
          v.setPadding(
              pad + i.getSystemWindowInsetLeft(),
              Ui.dp(this, 16) + i.getSystemWindowInsetTop(),
              pad + i.getSystemWindowInsetRight(),
              Ui.dp(this, 16) + i.getSystemWindowInsetBottom());
          return i;
        });
    root.requestApplyInsets();
    controller = buildView(root, this);
    a.store.listen(this);
  }

  @Override
  protected void onDestroy() {
    alive = false;
    a.store.unlisten(this);
    super.onDestroy();
  }

  @Override
  public void changed() {
    runOnUiThread(
        () -> {
          if (alive && controller != null) controller.refresh();
        });
  }

  public static Controller buildView(LinearLayout container, Activity host) {
    Controller c = new Controller(host);
    c.attach(container);
    return c;
  }

  public static final class Controller {
    private static final String[] LAYOUT_VALUES = {
      "PHOTO", "MAGAZINE", "BOARD", "SPLIT", "TEXT_ONLY"
    };
    private static final String[] LAYOUT_LABELS = {
      "전체 화면 사진 (사진 중심)",
      "감성형 스마트 매거진 (사진+글귀)",
      "생활 보드 (시계·이번 달 달력·글귀)",
      "좌우/상하 분할 카드형",
      "글귀 전용 화면 (엽서형)"
    };
    private static final String[] TEXT_MODE_VALUES = {"OVERLAY", "SPLIT", "DEDICATED", "NONE"};
    private static final String[] TEXT_MODE_LABELS = {
      "사진 위 글귀 표시", "사진·글귀 분할 화면 배치", "글귀 전용 중심 화면", "글귀 표시하지 않음"
    };
    private static final String[] EFFECT_VALUES = {"ZOOM", "FADE", "NONE"};
    private static final String[] EFFECT_LABELS = {
      "부드러운 확대 (Ken Burns)", "차분한 전환", "효과 없음 (저전력)"
    };
    private static final String[] CYCLE_VALUES = {"PHOTO", "MINUTES_15", "HOUR", "DAY"};
    private static final String[] CYCLE_LABELS = {
      "사진이 바뀔 때마다", "15분마다", "1시간마다", "하루에 한 번"
    };

    private final Activity host;
    private final NuniApp a;
    private LinearLayout rootContainer;
    private LinearLayout leftCol, editorContainer;
    private FrameView livePreview;
    private FrameLayout previewStage;
    private boolean portraitPreview;
    private String selectedSceneId = "";
    private Scene draft;
    private final Set<String> draftAlbumIds = new LinkedHashSet<>();
    private final Set<String> draftCategories = new LinkedHashSet<>();
    private List<Scene> scenes = new ArrayList<>();
    private List<Album> albums = new ArrayList<>();
    private final Map<String, Integer> catCounts = new LinkedHashMap<>();
    private final Map<String, List<String>> sceneAlbumLinks = new HashMap<>();
    private final Map<String, Set<String>> sceneCatLinks = new HashMap<>();

    public Controller(Activity h) {
      host = h;
      a = NuniApp.of(h);
    }

    public void attach(LinearLayout parent) {
      rootContainer = parent;
      refresh();
    }

    public void refresh() {
      if (!Ui.alive(host)) return;
      final String reqSceneId = selectedSceneId;
      a.io.execute(
          () -> {
            a.studio.ensureSeeded();
            StudioDao sdao = a.studio.dao();
            List<Scene> allScenes = sdao.scenes();
            List<Album> allAlbums = sdao.albums();
            List<TextItem> allTexts = sdao.textItems();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String c : a.prefs.textCategories()) {
              if (!"기타".equals(c)) counts.put(c, 0);
            }
            int etcCount = 0;
            for (TextItem t : allTexts) {
              if (t.body == null || t.body.trim().isEmpty()) continue;
              String c =
                  (t.category == null || t.category.trim().isEmpty())
                      ? "기타"
                      : t.category.trim();
              if ("기타".equals(c)) etcCount++;
              else counts.put(c, (counts.containsKey(c) ? counts.get(c) : 0) + 1);
            }
            counts.put("기타", etcCount);

            Map<String, List<String>> albumMap = new HashMap<>();
            Map<String, Set<String>> catMap = new HashMap<>();
            for (Scene sc : allScenes) {
              List<String> aids = new ArrayList<>();
              for (SceneAlbum sa : sdao.sceneAlbums(sc.id)) aids.add(sa.albumId);
              albumMap.put(sc.id, aids);
              catMap.put(sc.id, a.studio.extractSceneCategories(sc.id));
            }

            Scene target = null;
            for (Scene sc : allScenes) {
              if (sc.id.equals(reqSceneId)) {
                target = sc;
                break;
              }
            }
            if (target == null && !allScenes.isEmpty()) target = allScenes.get(0);
            Scene finalTarget = target;
            a.main.post(
                () -> {
                  if (!Ui.alive(host)) return;
                  scenes = allScenes;
                  albums = allAlbums;
                  catCounts.clear();
                  catCounts.putAll(counts);
                  sceneAlbumLinks.clear();
                  sceneAlbumLinks.putAll(albumMap);
                  sceneCatLinks.clear();
                  sceneCatLinks.putAll(catMap);
                  if (finalTarget != null
                      && (draft == null || !finalTarget.id.equals(selectedSceneId))) {
                    loadSceneIntoDraft(finalTarget);
                  } else if (draft == null) {
                    initNewSceneDraft();
                  }
                  renderFullUi();
                });
          });
    }

    private void loadSceneIntoDraft(Scene src) {
      selectedSceneId = src.id;
      draft = copyScene(src);
      draftAlbumIds.clear();
      List<String> linked = sceneAlbumLinks.get(src.id);
      if (linked != null) draftAlbumIds.addAll(linked);
      if (draftAlbumIds.isEmpty()) draftAlbumIds.add(Album.ALL_PHOTOS_ID);
      draftCategories.clear();
      Set<String> cats = sceneCatLinks.get(src.id);
      if (cats != null) draftCategories.addAll(cats);
    }

    private void initNewSceneDraft() {
      selectedSceneId = "";
      draft = new Scene();
      draft.id = "";
      draft.name = "새 장면";
      draft.description = "";
      draft.layout = "MAGAZINE";
      draft.textDisplayMode = "OVERLAY";
      draft.theme = "MILK";
      draft.transitionEffect = "ZOOM";
      draft.quoteCycle = "PHOTO";
      draft.seconds = 20;
      draft.textEveryN = 1;
      draft.brightness = 75;
      draft.fontScale = 100;
      draft.showClock = true;
      draft.showCaptureDate = true;
      draft.shufflePhotos = true;
      draft.nightDim = false;
      draft.isDefault = false;
      draftAlbumIds.clear();
      draftAlbumIds.add(Album.ALL_PHOTOS_ID);
      draftCategories.clear();
    }

    private static Scene copyScene(Scene s) {
      Scene c = new Scene();
      c.id = s.id;
      c.name = s.name;
      c.description = s.description;
      c.layout = s.layout;
      c.transitionEffect = s.transitionEffect;
      c.textDisplayMode = s.textDisplayMode;
      c.quoteCycle = s.quoteCycle;
      c.theme = s.theme;
      c.textColor = s.textColor;
      c.textAlign = s.textAlign;
      c.seconds = s.seconds;
      c.textEveryN = s.textEveryN;
      c.brightness = s.brightness;
      c.fontScale = s.fontScale;
      c.textPadding = s.textPadding;
      c.scrimOpacity = s.scrimOpacity;
      c.showClock = s.showClock;
      c.showWeather = s.showWeather;
      c.showCaptureDate = s.showCaptureDate;
      c.shufflePhotos = s.shufflePhotos;
      c.isDefault = s.isDefault;
      c.nightDim = s.nightDim;
      c.createdAt = s.createdAt;
      c.updatedAt = s.updatedAt;
      return c;
    }

    private void renderFullUi() {
      if (rootContainer == null || !Ui.alive(host)) return;
      rootContainer.removeAllViews();
      int widthDp = host.getResources().getConfiguration().screenWidthDp;
      boolean twoPane = widthDp >= 840;

      Ui.header(
          host,
          rootContainer,
          "재생 장면(Scene) 관리",
          "앨범 + 글귀 카테고리 + 레이아웃·색감을 조합하여 시간대별 재생 장면을 구성합니다.");
      Ui.gap(rootContainer, 12);

      if (twoPane) {
        LinearLayout split = Ui.row(host);
        split.setGravity(Gravity.TOP);
        rootContainer.addView(split, new LinearLayout.LayoutParams(-1, 0, 1));

        ScrollView leftScroll = new ScrollView(host);
        leftCol = Ui.column(host);
        leftScroll.addView(leftCol);
        LinearLayout.LayoutParams lpLeft = new LinearLayout.LayoutParams(0, -1, 5);
        lpLeft.setMarginEnd(Ui.dp(host, 16));
        split.addView(leftScroll, lpLeft);

        buildPreviewCard(leftCol, true);
        buildSceneListCard(leftCol, false);

        ScrollView rightScroll = new ScrollView(host);
        editorContainer = Ui.column(host);
        rightScroll.addView(editorContainer);
        split.addView(rightScroll, new LinearLayout.LayoutParams(0, -1, 5));
        buildEditorCard(editorContainer);
      } else {
        ScrollView scroll = new ScrollView(host);
        LinearLayout single = Ui.column(host);
        scroll.addView(single);
        rootContainer.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        buildPreviewCard(single, false);
        buildSceneListCard(single, true);
        editorContainer = Ui.column(host);
        single.addView(editorContainer);
        buildEditorCard(editorContainer);
      }
      refreshPreviewContent();
    }

    private void buildPreviewCard(LinearLayout parent, boolean twoPane) {
      LinearLayout card = Ui.card(parent);
      LinearLayout top = Ui.row(host);
      top.addView(
          Ui.title(host, "실시간 장면 미리보기", 17), new LinearLayout.LayoutParams(0, -2, 1));

      Button landBtn =
          Ui.button(
              host,
              "가로형",
              !portraitPreview,
              () -> {
                portraitPreview = false;
                updatePreviewDimensions(twoPane);
                renderFullUi();
              });
      landBtn.setTextSize(12.5f);
      LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(-2, Ui.dp(host, 36));
      lp1.setMarginEnd(Ui.dp(host, 6));
      top.addView(landBtn, lp1);

      Button portBtn =
          Ui.button(
              host,
              "세로형",
              portraitPreview,
              () -> {
                portraitPreview = true;
                updatePreviewDimensions(twoPane);
                renderFullUi();
              });
      portBtn.setTextSize(12.5f);
      top.addView(portBtn, new LinearLayout.LayoutParams(-2, Ui.dp(host, 36)));
      card.addView(top);
      Ui.gap(card, 10);

      previewStage = new FrameLayout(host);
      livePreview = new FrameView(host);
      livePreview.setPreviewMode(true);
      livePreview.setOverrideConfig(FrameView.Config.fromScene(draft, a.prefs));
      livePreview.setBackground(
          Ui.strokeBg(Ui.DARK_BG, Ui.OUTLINE, Ui.dp(host, 2), Ui.dp(host, 14)));
      livePreview.setClipToOutline(true);
      updatePreviewDimensions(twoPane);
      card.addView(previewStage, new LinearLayout.LayoutParams(-1, -2));

      if (!selectedSceneId.isEmpty()) {
        Ui.gap(card, 10);
        Ui.add(
            card,
            "이 장면을 지금 임시 즉시 재생",
            false,
            R.drawable.ic_play,
            this::showTempPlayDialog);
      }
    }

    private void updatePreviewDimensions(boolean twoPane) {
      if (previewStage == null || livePreview == null) return;
      previewStage.removeAllViews();
      FrameLayout.LayoutParams flp;
      if (portraitPreview) {
        flp =
            new FrameLayout.LayoutParams(
                Ui.dp(host, 190), Ui.dp(host, twoPane ? 290 : 250), Gravity.CENTER_HORIZONTAL);
      } else {
        flp = new FrameLayout.LayoutParams(-1, Ui.dp(host, twoPane ? 240 : 200));
      }
      previewStage.addView(livePreview, flp);
    }

    private void refreshPreviewContent() {
      if (livePreview == null || draft == null) return;
      livePreview.setOverrideConfig(FrameView.Config.fromScene(draft, a.prefs));
      final Set<String> reqAlbums = new LinkedHashSet<>(draftAlbumIds);
      final Set<String> reqCats = new LinkedHashSet<>(draftCategories);
      final String textMode = draft.textDisplayMode;
      a.io.execute(
          () -> {
            StudioDao sdao = a.studio.dao();
            List<String> effectiveAlbums =
                Core.exclusiveAlbumSelection(reqAlbums, Album.ALL_PHOTOS_ID);
            Photo samplePhoto = null;
            if (effectiveAlbums.isEmpty() || effectiveAlbums.contains(Album.ALL_PHOTOS_ID)) {
              List<Photo> active = a.db.photos().active();
              if (!active.isEmpty()) samplePhoto = active.get(0);
            } else {
              for (String aid : effectiveAlbums) {
                List<Photo> ap = sdao.albumPhotos(aid);
                if (!ap.isEmpty()) {
                  samplePhoto = ap.get(0);
                  break;
                }
              }
            }
            Bitmap bmp = samplePhoto != null ? a.store.load(samplePhoto, 800) : null;
            String samplePhrase = "";
            if (!"NONE".equals(textMode)) {
              List<TextItem> enabledTexts = sdao.enabledTextItems();
              TextItem chosen = null;
              for (TextItem ti : enabledTexts) {
                if (reqCats.isEmpty() || reqCats.contains(ti.category)) {
                  chosen = ti;
                  break;
                }
              }
              if (chosen == null && !enabledTexts.isEmpty()) chosen = enabledTexts.get(0);
              if (chosen != null) {
                String authorStr = chosen.author;
                if (!chosen.title.isEmpty() && !chosen.author.contains(chosen.title)) {
                  authorStr =
                      chosen.author.isEmpty()
                          ? chosen.title
                          : (chosen.author + " · " + chosen.title);
                }
                String k =
                    "POEM".equals(chosen.kind) || chosen.body.indexOf('\n') >= 0
                        ? "POEM"
                        : "QUOTE";
                samplePhrase = new Core.QuoteEntry(k, chosen.body, authorStr).encode();
              } else {
                samplePhrase = "눈이 머무는 곳에 마음도 잠시 쉬어갑니다.|누니담";
              }
            }
            Photo finalPhoto = samplePhoto;
            String finalPhrase = samplePhrase;
            a.main.post(
                () -> {
                  if (!Ui.alive(host) || livePreview == null) return;
                  if (finalPhoto != null && bmp != null) {
                    livePreview.show(finalPhoto, bmp, finalPhrase);
                  } else {
                    livePreview.showSample(finalPhrase);
                  }
                });
          });
    }

    private void buildSceneListCard(LinearLayout parent, boolean compact) {
      LinearLayout card = Ui.card(parent);
      LinearLayout top = Ui.row(host);
      top.addView(
          Ui.title(host, "재생 장면 목록 (" + scenes.size() + "개)", 17),
          new LinearLayout.LayoutParams(0, -2, 1));
      Button addBtn =
          Ui.button(
              host,
              "새 장면",
              true,
              R.drawable.ic_plus,
              () -> {
                initNewSceneDraft();
                renderFullUi();
              });
      addBtn.setTextSize(13f);
      top.addView(addBtn, new LinearLayout.LayoutParams(-2, Ui.dp(host, 40)));
      card.addView(top);
      Ui.gap(card, 8);

      Map<String, String> albumNames = new HashMap<>();
      for (Album al : albums) albumNames.put(al.id, al.name);

      if (compact) {
        HorizontalScrollView hsv = new HorizontalScrollView(host);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.row(host);
        hsv.addView(row);
        for (Scene sc : scenes) {
          boolean sel = sc.id.equals(selectedSceneId);
          boolean isDef = "scene_default".equals(sc.id) || sc.isDefault;
          Button chip =
              Ui.button(
                  host,
                  (isDef ? "★ " : "") + sc.name,
                  sel,
                  () -> {
                    loadSceneIntoDraft(sc);
                    renderFullUi();
                  });
          chip.setTextSize(13f);
          LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(host, 40));
          lp.setMarginEnd(Ui.dp(host, 6));
          row.addView(chip, lp);
        }
        card.addView(hsv);
      } else {
        for (int i = 0; i < scenes.size(); i++) {
          Scene sc = scenes.get(i);
          boolean sel = sc.id.equals(selectedSceneId);
          boolean isDef = "scene_default".equals(sc.id) || sc.isDefault;
          LinearLayout row = Ui.column(host);
          row.setPadding(Ui.dp(host, 12), Ui.dp(host, 10), Ui.dp(host, 12), Ui.dp(host, 10));
          row.setBackground(
              Ui.strokeBg(
                  sel ? Ui.SURFACE_SUBTLE : Ui.SURFACE,
                  sel ? Ui.PRIMARY : Ui.OUTLINE,
                  Ui.dp(host, sel ? 2 : 1),
                  Ui.dp(host, 12)));
          row.setClickable(true);
          row.setFocusable(true);
          row.setOnClickListener(
              v -> {
                loadSceneIntoDraft(sc);
                renderFullUi();
              });
          TextView tvTitle =
              Ui.text(
                  host,
                  (isDef ? "★ " : "") + sc.name + (isDef ? " (기본 장면)" : ""),
                  15,
                  sel ? Ui.PRIMARY : Ui.INK);
          tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
          row.addView(tvTitle);

          List<String> aids = sceneAlbumLinks.get(sc.id);
          List<String> aNames = new ArrayList<>();
          if (aids != null) {
            for (String id : aids) aNames.add(albumNames.containsKey(id) ? albumNames.get(id) : id);
          }
          String albSum = aNames.isEmpty() ? "모든 사진" : TextUtils.join(", ", aNames);
          Set<String> cNames = sceneCatLinks.get(sc.id);
          String catSum =
              (cNames == null || cNames.isEmpty()) ? "전체 글귀" : TextUtils.join(", ", cNames);
          String sub =
              String.format(
                  Locale.KOREA,
                  "%s · %s · 밝기 %d%% · %d초\n앨범: %s | 글귀: %s",
                  shortLayoutLabel(sc.layout),
                  sc.theme,
                  sc.brightness,
                  sc.seconds,
                  albSum,
                  catSum);
          row.addView(Ui.text(host, sub, 12.5f, Ui.MUTED));
          LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
          if (i < scenes.size() - 1) rp.bottomMargin = Ui.dp(host, 6);
          card.addView(row, rp);
        }
      }
    }

    private void buildEditorCard(LinearLayout parent) {
      parent.removeAllViews();
      if (draft == null) return;
      boolean isNew = selectedSceneId.isEmpty();
      boolean isFixedDefault = "scene_default".equals(draft.id);
      boolean isDef = isFixedDefault || draft.isDefault;

      LinearLayout card = Ui.card(parent);
      Ui.section(
          card,
          R.drawable.ic_scene,
          isNew ? "새 장면 만들기" : ("장면 상세 편집 · " + draft.name));

      if (isDef && !isNew) {
        TextView notice =
            Ui.text(
                host,
                "★ 기본 장면 — 삭제할 수 없으며, 주간 편성표가 지정되지 않은 모든 시간대에 상시 재생됩니다.",
                13f,
                Ui.PRIMARY);
        notice.setPadding(Ui.dp(host, 12), Ui.dp(host, 8), Ui.dp(host, 12), Ui.dp(host, 8));
        notice.setBackground(Ui.bg(Ui.SURFACE_SUBTLE, Ui.dp(host, 10)));
        card.addView(notice);
        Ui.gap(card, 10);
      }

      card.addView(Ui.text(host, "장면 이름", 13.5f, Ui.MUTED));
      EditText nameIn = Ui.input(host, "예: 좋은 아침, 저녁의 쉼", draft.name);
      nameIn.setSingleLine();
      watchText(nameIn, s -> draft.name = s);
      card.addView(nameIn);

      Ui.gap(card, 8);
      card.addView(Ui.text(host, "장면 설명", 13.5f, Ui.MUTED));
      EditText descIn = Ui.input(host, "장면에 대한 간단한 설명", draft.description);
      descIn.setSingleLine();
      watchText(descIn, s -> draft.description = s);
      card.addView(descIn);

      Ui.gap(card, 10);
      card.addView(Ui.text(host, "레이아웃 템플릿", 13.5f, Ui.MUTED));
      Spinner layoutSpin = new Spinner(host);
      layoutSpin.setAdapter(
          new ArrayAdapter<>(host, android.R.layout.simple_spinner_dropdown_item, LAYOUT_LABELS));
      layoutSpin.setSelection(indexOf(LAYOUT_VALUES, draft.layout));
      layoutSpin.setOnItemSelectedListener(
          new AdapterView.OnItemSelectedListener() {
            @Override
            public void onNothingSelected(AdapterView<?> p) {}

            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
              draft.layout = LAYOUT_VALUES[pos];
              if (livePreview != null) {
                livePreview.setOverrideConfig(FrameView.Config.fromScene(draft, a.prefs));
              }
            }
          });
      card.addView(layoutSpin, new LinearLayout.LayoutParams(-1, Ui.dp(host, 48)));

      Ui.gap(card, 8);
      card.addView(Ui.text(host, "글귀 표시 방식", 13.5f, Ui.MUTED));
      Spinner textModeSpin = new Spinner(host);
      textModeSpin.setAdapter(
          new ArrayAdapter<>(
              host, android.R.layout.simple_spinner_dropdown_item, TEXT_MODE_LABELS));
      textModeSpin.setSelection(indexOf(TEXT_MODE_VALUES, draft.textDisplayMode));
      textModeSpin.setOnItemSelectedListener(
          new AdapterView.OnItemSelectedListener() {
            @Override
            public void onNothingSelected(AdapterView<?> p) {}

            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
              draft.textDisplayMode = TEXT_MODE_VALUES[pos];
              refreshPreviewContent();
            }
          });
      card.addView(textModeSpin, new LinearLayout.LayoutParams(-1, Ui.dp(host, 48)));

      Ui.gap(card, 10);
      card.addView(Ui.text(host, "배경 색감 테마", 13.5f, Ui.MUTED));
      LinearLayout themeRow = Ui.row(host);
      addThemeSwatch(themeRow, "MILK", "밀크", 0xfff4f2ec, 0xff24332b, nameIn, descIn);
      addThemeSwatch(themeRow, "SAGE", "세이지", 0xffdce4d5, 0xff24332b, nameIn, descIn);
      addThemeSwatch(themeRow, "INK", "잉크", 0xff18221d, 0xffedefe8, nameIn, descIn);
      card.addView(themeRow);

      Ui.gap(card, 10);
      card.addView(Ui.text(host, "사진 전환 효과", 13.5f, Ui.MUTED));
      Spinner effectSpin = new Spinner(host);
      effectSpin.setAdapter(
          new ArrayAdapter<>(host, android.R.layout.simple_spinner_dropdown_item, EFFECT_LABELS));
      effectSpin.setSelection(indexOf(EFFECT_VALUES, draft.transitionEffect));
      effectSpin.setOnItemSelectedListener(
          new AdapterView.OnItemSelectedListener() {
            @Override
            public void onNothingSelected(AdapterView<?> p) {}

            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
              draft.transitionEffect = EFFECT_VALUES[pos];
            }
          });
      card.addView(effectSpin, new LinearLayout.LayoutParams(-1, Ui.dp(host, 48)));

      Ui.gap(card, 8);
      card.addView(Ui.text(host, "글귀 전환 주기", 13.5f, Ui.MUTED));
      Spinner cycleSpin = new Spinner(host);
      cycleSpin.setAdapter(
          new ArrayAdapter<>(host, android.R.layout.simple_spinner_dropdown_item, CYCLE_LABELS));
      cycleSpin.setSelection(indexOf(CYCLE_VALUES, draft.quoteCycle));
      cycleSpin.setOnItemSelectedListener(
          new AdapterView.OnItemSelectedListener() {
            @Override
            public void onNothingSelected(AdapterView<?> p) {}

            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
              draft.quoteCycle = CYCLE_VALUES[pos];
            }
          });
      card.addView(cycleSpin, new LinearLayout.LayoutParams(-1, Ui.dp(host, 48)));

      Ui.gap(card, 8);
      LinearLayout numRow = Ui.row(host);
      LinearLayout secCol = Ui.column(host);
      secCol.addView(Ui.text(host, "사진 표시 시간(초)", 13.5f, Ui.MUTED));
      EditText secIn = Ui.input(host, "20", String.valueOf(draft.seconds));
      secIn.setInputType(InputType.TYPE_CLASS_NUMBER);
      secIn.setSingleLine();
      watchText(
          secIn,
          s -> {
            try {
              draft.seconds = Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
          });
      secCol.addView(secIn);
      LinearLayout.LayoutParams sp1 = new LinearLayout.LayoutParams(0, -2, 1);
      sp1.setMarginEnd(Ui.dp(host, 8));
      numRow.addView(secCol, sp1);

      LinearLayout everyCol = Ui.column(host);
      everyCol.addView(Ui.text(host, "글귀 빈도(사진 N장마다)", 13.5f, Ui.MUTED));
      EditText everyIn = Ui.input(host, "1", String.valueOf(draft.textEveryN));
      everyIn.setInputType(InputType.TYPE_CLASS_NUMBER);
      everyIn.setSingleLine();
      watchText(
          everyIn,
          s -> {
            try {
              draft.textEveryN = Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
          });
      everyCol.addView(everyIn);
      numRow.addView(everyCol, new LinearLayout.LayoutParams(0, -2, 1));
      card.addView(numRow);

      Ui.gap(card, 10);
      TextView brightLabel =
          Ui.text(host, "화면 밝기: " + draft.brightness + "%", 13.5f, Ui.MUTED);
      card.addView(brightLabel);
      SeekBar brightSeek = new SeekBar(host);
      brightSeek.setMax(95);
      brightSeek.setProgress(Math.max(0, Math.min(95, draft.brightness - 5)));
      brightSeek.setOnSeekBarChangeListener(
          new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
              draft.brightness = progress + 5;
              brightLabel.setText("화면 밝기: " + draft.brightness + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {}
          });
      card.addView(brightSeek);

      Ui.gap(card, 10);
      card.addView(Ui.text(host, "글자 크기 배율", 13.5f, Ui.MUTED));
      LinearLayout scaleRow = Ui.row(host);
      int[] scales = {100, 120, 140};
      String[] scaleLabels = {"기본 (100%)", "크게 (120%)", "아주 크게 (140%)"};
      for (int i = 0; i < scales.length; i++) {
        int val = scales[i];
        boolean sel = draft.fontScale == val;
        Button sb =
            Ui.button(
                host,
                scaleLabels[i],
                sel,
                () -> {
                  draft.name = nameIn.getText().toString();
                  draft.description = descIn.getText().toString();
                  draft.fontScale = val;
                  buildEditorCard(parent);
                  if (livePreview != null) {
                    livePreview.setOverrideConfig(FrameView.Config.fromScene(draft, a.prefs));
                  }
                });
        sb.setTextSize(12.5f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(host, 42), 1);
        if (i < scales.length - 1) lp.setMarginEnd(Ui.dp(host, 6));
        scaleRow.addView(sb, lp);
      }
      card.addView(scaleRow);

      Ui.gap(card, 12);
      card.addView(Ui.text(host, "연결할 사진 앨범 (복수 선택)", 14, Ui.INK));
      card.addView(
          Ui.text(
              host,
              "‘모든 사진’을 선택하면 다른 앨범과 중복 선택되지 않습니다. 특정 앨범만 재생하려면 ‘모든 사진’을 해제한 뒤 앨범을 선택하세요.",
              12.5f,
              Ui.MUTED));
      LinearLayout albumCheckBoxWrap = Ui.column(host);
      albumCheckBoxWrap.setPadding(Ui.dp(host, 10), Ui.dp(host, 6), Ui.dp(host, 10), Ui.dp(host, 6));
      albumCheckBoxWrap.setBackground(
          Ui.strokeBg(Ui.BG, Ui.OUTLINE, Ui.dp(host, 1), Ui.dp(host, 12)));
      List<CheckBox> albumBoxes = new ArrayList<>();
      boolean hasNamedSelected = false;
      for (String aid : draftAlbumIds) {
        if (!Album.isAllPhotos(aid)) hasNamedSelected = true;
      }
      boolean allPhotosChecked =
          !hasNamedSelected
              && (draftAlbumIds.isEmpty() || draftAlbumIds.contains(Album.ALL_PHOTOS_ID));
      for (Album al : albums) {
        CheckBox cb = new CheckBox(host);
        cb.setTag(al.id);
        cb.setTextColor(Ui.INK);
        cb.setText(
            Album.isAllPhotos(al.id) ? "모든 사진 · 보관함 전체" : al.name);
        cb.setChecked(
            Album.isAllPhotos(al.id)
                ? allPhotosChecked
                : (!allPhotosChecked && draftAlbumIds.contains(al.id)));
        albumBoxes.add(cb);
        albumCheckBoxWrap.addView(cb);
      }
      Runnable syncAlbumCheckState =
          () -> {
            CheckBox allBox = null;
            boolean anyOtherChecked = false;
            for (CheckBox cb : albumBoxes) {
              if (Album.ALL_PHOTOS_ID.equals(cb.getTag())) allBox = cb;
              else if (cb.isChecked()) anyOtherChecked = true;
            }
            if (allBox != null) {
              boolean allSel = allBox.isChecked() && !anyOtherChecked;
              allBox.setEnabled(!anyOtherChecked);
              for (CheckBox cb : albumBoxes) {
                if (cb != allBox) cb.setEnabled(!allSel);
              }
            }
            draftAlbumIds.clear();
            for (CheckBox cb : albumBoxes) {
              if (cb.isChecked()) draftAlbumIds.add(String.valueOf(cb.getTag()));
            }
          };
      for (CheckBox cb : albumBoxes) {
        cb.setOnClickListener(
            v -> {
              if (Album.ALL_PHOTOS_ID.equals(cb.getTag()) && cb.isChecked()) {
                for (CheckBox other : albumBoxes) {
                  if (other != cb) other.setChecked(false);
                }
              } else if (!Album.ALL_PHOTOS_ID.equals(cb.getTag()) && cb.isChecked()) {
                for (CheckBox other : albumBoxes) {
                  if (Album.ALL_PHOTOS_ID.equals(other.getTag())) other.setChecked(false);
                }
              }
              syncAlbumCheckState.run();
              refreshPreviewContent();
            });
      }
      syncAlbumCheckState.run();
      card.addView(albumCheckBoxWrap);

      Ui.gap(card, 12);
      card.addView(Ui.text(host, "연결할 글귀 카테고리 (미선택 시 전체 글귀 재생)", 14, Ui.INK));
      LinearLayout catCheckWrap = Ui.column(host);
      catCheckWrap.setPadding(Ui.dp(host, 10), Ui.dp(host, 6), Ui.dp(host, 10), Ui.dp(host, 6));
      catCheckWrap.setBackground(Ui.strokeBg(Ui.BG, Ui.OUTLINE, Ui.dp(host, 1), Ui.dp(host, 12)));
      for (Map.Entry<String, Integer> e : catCounts.entrySet()) {
        String catName = e.getKey();
        CheckBox cb = new CheckBox(host);
        cb.setTextColor(Ui.INK);
        cb.setText(catName + " (" + e.getValue() + "편)");
        cb.setChecked(draftCategories.contains(catName));
        cb.setOnCheckedChangeListener(
            (btn, on) -> {
              if (on) draftCategories.add(catName);
              else draftCategories.remove(catName);
              refreshPreviewContent();
            });
        catCheckWrap.addView(cb);
      }
      card.addView(catCheckWrap);

      Ui.gap(card, 10);
      Switch clockSw = new Switch(host);
      clockSw.setText("시계 표시");
      clockSw.setTextColor(Ui.INK);
      clockSw.setChecked(draft.showClock);
      clockSw.setOnCheckedChangeListener(
          (v, on) -> {
            draft.showClock = on;
            if (livePreview != null) {
              livePreview.setOverrideConfig(FrameView.Config.fromScene(draft, a.prefs));
            }
          });
      card.addView(clockSw);

      Switch dateSw = new Switch(host);
      dateSw.setText("촬영일 표시");
      dateSw.setTextColor(Ui.INK);
      dateSw.setChecked(draft.showCaptureDate);
      dateSw.setOnCheckedChangeListener(
          (v, on) -> {
            draft.showCaptureDate = on;
            if (livePreview != null) {
              livePreview.setOverrideConfig(FrameView.Config.fromScene(draft, a.prefs));
            }
          });
      card.addView(dateSw);

      Switch shufSw = new Switch(host);
      shufSw.setText("사진 무작위 순서(셔플)");
      shufSw.setTextColor(Ui.INK);
      shufSw.setChecked(draft.shufflePhotos);
      shufSw.setOnCheckedChangeListener((v, on) -> draft.shufflePhotos = on);
      card.addView(shufSw);

      Switch dimSw = new Switch(host);
      dimSw.setText("야간 절전 감광");
      dimSw.setTextColor(Ui.INK);
      dimSw.setChecked(draft.nightDim);
      dimSw.setOnCheckedChangeListener((v, on) -> draft.nightDim = on);
      card.addView(dimSw);

      Switch defSw = new Switch(host);
      defSw.setText("기본 장면으로 지정 (미편성 시간대 상시 재생)");
      defSw.setTextColor(Ui.INK);
      defSw.setChecked(isDef);
      defSw.setEnabled(!isFixedDefault);
      defSw.setOnCheckedChangeListener((v, on) -> draft.isDefault = on);
      card.addView(defSw);

      Ui.gap(card, 14);
      Button saveBtn =
          Ui.button(
              host,
              isNew ? "새 장면 저장 및 적용" : "장면 저장 및 액자 적용",
              true,
              R.drawable.ic_check,
              () -> {
                draft.name = nameIn.getText().toString().trim();
                draft.description = descIn.getText().toString().trim();
                try {
                  draft.seconds = Integer.parseInt(secIn.getText().toString().trim());
                } catch (NumberFormatException ignored) {
                }
                try {
                  draft.textEveryN = Integer.parseInt(everyIn.getText().toString().trim());
                } catch (NumberFormatException ignored) {
                }
                boolean makeDef = isFixedDefault || defSw.isChecked();
                Set<String> aids = new LinkedHashSet<>(draftAlbumIds);
                Set<String> cats = new LinkedHashSet<>(draftCategories);
                Scene toSave = copyScene(draft);
                a.io.execute(
                    () -> {
                      try {
                        Scene saved = a.studio.saveSceneWithLinks(toSave, aids, cats, makeDef);
                        selectedSceneId = saved.id;
                        draft = null;
                        a.main.post(
                            () -> {
                              Ui.toast(host, "‘" + saved.name + "’ 장면을 저장하고 액자에 반영했습니다.");
                              refresh();
                            });
                      } catch (Exception e) {
                        a.main.post(() -> Ui.error(host, e));
                      }
                    });
              });
      card.addView(saveBtn, new LinearLayout.LayoutParams(-1, Ui.dp(host, 52)));

      if (!isNew) {
        Ui.gap(card, 8);
        LinearLayout subActions = Ui.row(host);
        Button cloneBtn =
            Ui.button(
                host,
                "장면 복제",
                false,
                () ->
                    a.io.execute(
                        () -> {
                          try {
                            Scene cloned = a.studio.cloneScene(selectedSceneId);
                            selectedSceneId = cloned.id;
                            draft = null;
                            a.main.post(
                                () -> {
                                  Ui.toast(host, "‘" + cloned.name + "’ 장면을 복제했습니다.");
                                  refresh();
                                });
                          } catch (Exception e) {
                            a.main.post(() -> Ui.error(host, e));
                          }
                        }));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, Ui.dp(host, 46), 1);
        if (!isDef) cp.setMarginEnd(Ui.dp(host, 8));
        subActions.addView(cloneBtn, cp);

        if (!isDef) {
          Button delBtn =
              Ui.dangerButton(
                  host,
                  "장면 삭제",
                  R.drawable.ic_trash,
                  () ->
                      new AlertDialog.Builder(host)
                          .setTitle("‘" + draft.name + "’ 장면을 삭제할까요?")
                          .setPositiveButton(
                              "삭제",
                              (d, w) ->
                                  a.io.execute(
                                      () -> {
                                        try {
                                          a.studio.deleteScene(selectedSceneId);
                                          selectedSceneId = "";
                                          draft = null;
                                          a.main.post(
                                              () -> {
                                                Ui.toast(host, "장면을 삭제했습니다.");
                                                refresh();
                                              });
                                        } catch (Exception e) {
                                          a.main.post(() -> Ui.error(host, e));
                                        }
                                      }))
                          .setNegativeButton("취소", null)
                          .show());
          subActions.addView(delBtn, new LinearLayout.LayoutParams(0, Ui.dp(host, 46), 1));
        }
        card.addView(subActions);
      }
    }

    private void addThemeSwatch(
        LinearLayout row,
        String key,
        String title,
        int bgCol,
        int fgCol,
        EditText nameIn,
        EditText descIn) {
      boolean sel = key.equals(draft.theme);
      LinearLayout box = Ui.column(host);
      box.setPadding(Ui.dp(host, 10), Ui.dp(host, 8), Ui.dp(host, 10), Ui.dp(host, 8));
      box.setBackground(
          Ui.strokeBg(
              sel ? Ui.SURFACE_SUBTLE : Ui.SURFACE,
              sel ? Ui.PRIMARY : Ui.OUTLINE,
              Ui.dp(host, sel ? 2 : 1),
              Ui.dp(host, 12)));
      box.setClickable(true);
      box.setFocusable(true);
      box.setOnClickListener(
          v -> {
            draft.name = nameIn.getText().toString();
            draft.description = descIn.getText().toString();
            draft.theme = key;
            if (editorContainer != null) buildEditorCard(editorContainer);
            if (livePreview != null) {
              livePreview.setOverrideConfig(FrameView.Config.fromScene(draft, a.prefs));
            }
          });
      View swatch = new View(host);
      swatch.setBackground(Ui.strokeBg(bgCol, fgCol, Ui.dp(host, 1), Ui.dp(host, 8)));
      box.addView(swatch, new LinearLayout.LayoutParams(-1, Ui.dp(host, 22)));
      Ui.gap(box, 4);
      TextView tv = Ui.text(host, title, 13.5f, sel ? Ui.PRIMARY : Ui.INK);
      tv.setTypeface(Typeface.DEFAULT_BOLD);
      box.addView(tv);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
      if (!"INK".equals(key)) lp.setMarginEnd(Ui.dp(host, 6));
      row.addView(box, lp);
    }

    private void showTempPlayDialog() {
      if (selectedSceneId.isEmpty()) return;
      String[] labels = {"30분 동안 즉시 재생", "1시간 동안 즉시 재생", "3시간 동안 즉시 재생", "임시 재생 해제 (원래 시간표 복귀)"};
      int[] mins = {30, 60, 180, 0};
      new AlertDialog.Builder(host)
          .setTitle("‘" + draft.name + "’ 임시 즉시 재생")
          .setItems(
              labels,
              (d, idx) -> {
                int dur = mins[idx];
                String sid = dur > 0 ? selectedSceneId : "";
                a.io.execute(
                    () -> {
                      try {
                        a.studio.applyTempScheduleOverride(sid, dur);
                        a.main.post(
                            () ->
                                Ui.toast(
                                    host,
                                    dur > 0
                                        ? ("‘" + draft.name + "’ 장면을 " + dur + "분 동안 즉시 재생합니다.")
                                        : "임시 재생을 해제하고 주간 시간표로 복귀했습니다."));
                      } catch (Exception e) {
                        a.main.post(() -> Ui.error(host, e));
                      }
                    });
              })
          .setNegativeButton("취소", null)
          .show();
    }

    private interface TextConsumer {
      void accept(String text);
    }

    private static void watchText(EditText input, TextConsumer consumer) {
      input.addTextChangedListener(
          new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
              consumer.accept(s == null ? "" : s.toString());
            }
          });
    }

    private static int indexOf(String[] arr, String val) {
      for (int i = 0; i < arr.length; i++) {
        if (arr[i].equals(val)) return i;
      }
      return 0;
    }

    private static String shortLayoutLabel(String layout) {
      if ("PHOTO".equals(layout)) return "전면 사진형";
      if ("BOARD".equals(layout)) return "생활 보드";
      if ("SPLIT".equals(layout)) return "분할 카드형";
      if ("TEXT_ONLY".equals(layout)) return "글귀 전용";
      return "스마트 매거진";
    }
  }
}
