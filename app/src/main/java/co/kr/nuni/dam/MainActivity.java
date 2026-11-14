package co.kr.nuni.dam;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import java.util.*;

/**
 * Adaptive Studio & Frame management shell:
 * 1) 액자 (FRAME)
 * 2) 사진 (PHOTOS)
 * 3) 앨범 (ALBUMS)
 * 4) 글귀 라이브러리 (TEXTS)
 * 5) 재생 장면 (SCENES)
 * 6) 주간 편성표 (SCHEDULES)
 * 7) 설정 (SETTINGS)
 * (+ 간편 꾸미기 CUSTOMIZE 호환 지원)
 */
public final class MainActivity extends Activity implements PhotoStore.Listener {
  private NuniApp a;
  private String currentTab = "FRAME";
  private boolean homeOpened;
  private AlertDialog progressDialog;
  private LinearLayout contentPane;
  private final Map<String, Button> navButtons = new HashMap<>();
  private LibraryActivity.Controller libraryController;
  private AlbumsActivity.Controller albumsController;
  private QuotesActivity.Controller quotesController;
  private ScenesActivity.Controller scenesController;
  private SchedulesActivity.Controller schedulesController;
  private SettingsActivity.Controller settingsController;
  private FrameView.Config draftConfig;
  private String pendingFocusAlbumId;
  private String pendingImportAlbumId = "";

  public void onCreate(Bundle state) {
    super.onCreate(state);
    a = NuniApp.of(this);
    if (state != null) {
      currentTab = state.getString("tab", "FRAME");
    } else if (getIntent() != null && getIntent().hasExtra("tab")) {
      currentTab = getIntent().getStringExtra("tab");
    }
    a.store.listen(this);
    buildShell();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (intent != null && intent.hasExtra("tab")) {
      switchTab(intent.getStringExtra("tab"));
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putString("tab", currentTab);
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    buildShell();
  }

  protected void onResume() {
    super.onResume();
    refreshOrRenderCurrentTab();
    maybeShowExitNotice();
  }

  /** 액자 재생을 가리지 않는다. 홈으로 바로 액자를 여는 경우에는 나중에 메인으로 돌아왔을 때 보여 준다. */
  private void maybeShowExitNotice() {
    if (!Ui.alive(this) || !ExitLog.hasPendingNotice(this)) return;
    if (!homeOpened && getIntent() != null && getIntent().hasCategory(Intent.CATEGORY_HOME)) {
      return;
    }
    ExitLog.consumePendingNotice(this);
    new AlertDialog.Builder(this)
        .setTitle("앱이 비정상 종료됐어요")
        .setMessage("지난번에 앱이 비정상 종료됐어요. 설정 > 앱 종료 기록에서 확인할 수 있어요.")
        .setPositiveButton("확인", null)
        .setNeutralButton(
            "기록 보기",
            (d, w) -> switchTab("SETTINGS"))
        .show();
  }

  protected void onDestroy() {
    a.store.unlisten(this);
    releaseActiveControllers();
    if (progressDialog != null) progressDialog.dismiss();
    super.onDestroy();
  }

  public void changed() {
    runOnUiThread(this::refreshOrRenderCurrentTab);
  }

  private void refreshOrRenderCurrentTab() {
    if (!Ui.alive(this) || contentPane == null) return;
    if ("PHOTOS".equals(currentTab) && libraryController != null) {
      libraryController.refresh();
    } else if ("ALBUMS".equals(currentTab) && albumsController != null) {
      albumsController.refresh();
    } else if ("TEXTS".equals(currentTab) && quotesController != null) {
      quotesController.refresh();
    } else if ("SCENES".equals(currentTab) && scenesController != null) {
      scenesController.refresh();
    } else if ("SCHEDULES".equals(currentTab) && schedulesController != null) {
      schedulesController.refresh();
    } else {
      renderCurrentTab();
    }
  }

  private void releaseActiveControllers() {
    if (libraryController != null) {
      libraryController.release();
      libraryController = null;
    }
    if (albumsController != null) {
      albumsController.release();
      albumsController = null;
    }
    quotesController = null;
    scenesController = null;
    schedulesController = null;
    settingsController = null;
  }

  private void buildShell() {
    releaseActiveControllers();
    getWindow().setStatusBarColor(Ui.BG);
    getWindow().setNavigationBarColor(Ui.BG);
    int widthDp = getResources().getConfiguration().screenWidthDp;
    boolean useRail = widthDp >= 600;

    LinearLayout shell = useRail ? Ui.row(this) : Ui.column(this);
    shell.setBackgroundColor(Ui.BG);
    shell.setGravity(Gravity.TOP);
    setContentView(shell);

    // Apply WindowInsets once at the shell level (Section 4.1)
    shell.setOnApplyWindowInsetsListener(
        (v, i) -> {
          v.setPadding(
              i.getSystemWindowInsetLeft(),
              i.getSystemWindowInsetTop(),
              i.getSystemWindowInsetRight(),
              i.getSystemWindowInsetBottom());
          return i;
        });
    shell.requestApplyInsets();

    navButtons.clear();
    if (useRail) {
      LinearLayout rail = Ui.column(this);
      rail.setBackgroundColor(Ui.SURFACE);
      int rPad = Ui.dp(this, 10);
      rail.setPadding(rPad, Ui.dp(this, 16), rPad, Ui.dp(this, 16));
      rail.setGravity(Gravity.CENTER_HORIZONTAL);
      ImageView logo = new ImageView(this);
      logo.setImageResource(R.drawable.ic_nuni);
      logo.setBackground(Ui.bg(Ui.SURFACE_SUBTLE, Ui.dp(this, 10)));
      logo.setClipToOutline(true);
      rail.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34)));
      ImageView railKrLogo = new ImageView(this);
      railKrLogo.setImageResource(R.drawable.nunidam_kr_logo);
      railKrLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
      LinearLayout.LayoutParams rklp = new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 20));
      rklp.topMargin = Ui.dp(this, 4);
      rail.addView(railKrLogo, rklp);
      Ui.gap(rail, 10);

      ScrollView navScroll = new ScrollView(this);
      navScroll.setVerticalScrollBarEnabled(false);
      navScroll.setFillViewport(true);
      LinearLayout navCol = Ui.column(this);
      navCol.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
      addNavButton(navCol, "FRAME", "액자", "액자 홈 탭", R.drawable.ic_screen, true);
      addNavButton(navCol, "PHOTOS", "사진", "사진 보관함 탭", R.drawable.ic_gallery, true);
      addNavButton(navCol, "ALBUMS", "앨범", "앨범 관리 탭", R.drawable.ic_folder, true);
      addNavButton(navCol, "TEXTS", "글귀", "글귀 라이브러리 탭", R.drawable.ic_quote, true);
      addNavButton(navCol, "SCENES", "재생장면", "재생 장면 관리 탭", R.drawable.ic_scene, true);
      addNavButton(navCol, "SCHEDULES", "편성표", "주간 편성표 관리 탭", R.drawable.ic_calendar, true);
      addNavButton(navCol, "SETTINGS", "설정", "설정 탭", R.drawable.ic_tune, true);
      navScroll.addView(navCol);
      rail.addView(navScroll, new LinearLayout.LayoutParams(-1, 0, 1));

      Button railExitBtn = Ui.tile(this, "종료", R.drawable.ic_exit, Ui.DANGER, () -> Ui.exitApp(this));
      railExitBtn.setContentDescription("앱 종료");
      LinearLayout.LayoutParams exitLp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 58));
      int em = Ui.dp(this, 2);
      exitLp.setMargins(em, Ui.dp(this, 6), em, em);
      rail.addView(railExitBtn, exitLp);
      shell.addView(rail, new LinearLayout.LayoutParams(Ui.dp(this, 96), -1));
      shell.addView(Ui.divider(this), new LinearLayout.LayoutParams(Ui.dp(this, 1), -1));

      contentPane = Ui.column(this);
      int pad = Ui.dp(this, Ui.outerPaddingDp(widthDp));
      contentPane.setPadding(pad, Ui.dp(this, 18), pad, Ui.dp(this, 18));
      shell.addView(contentPane, new LinearLayout.LayoutParams(0, -1, 1));
    } else {
      contentPane = Ui.column(this);
      int pad = Ui.dp(this, Ui.outerPaddingDp(widthDp));
      contentPane.setPadding(pad, Ui.dp(this, 16), pad, Ui.dp(this, 12));
      shell.addView(contentPane, new LinearLayout.LayoutParams(-1, 0, 1));
      shell.addView(Ui.divider(this), new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));

      HorizontalScrollView bottomScroll = new HorizontalScrollView(this);
      bottomScroll.setHorizontalScrollBarEnabled(false);
      bottomScroll.setFillViewport(true);
      bottomScroll.setBackgroundColor(Ui.SURFACE);
      LinearLayout bottomBar = Ui.row(this);
      bottomBar.setPadding(Ui.dp(this, 6), Ui.dp(this, 5), Ui.dp(this, 6), Ui.dp(this, 5));
      addNavButton(bottomBar, "FRAME", "액자", "액자 홈 탭", R.drawable.ic_screen, false);
      addNavButton(bottomBar, "PHOTOS", "사진", "사진 보관함 탭", R.drawable.ic_gallery, false);
      addNavButton(bottomBar, "ALBUMS", "앨범", "앨범 관리 탭", R.drawable.ic_folder, false);
      addNavButton(bottomBar, "TEXTS", "글귀", "글귀 라이브러리 탭", R.drawable.ic_quote, false);
      addNavButton(bottomBar, "SCENES", "재생장면", "재생 장면 관리 탭", R.drawable.ic_scene, false);
      addNavButton(bottomBar, "SCHEDULES", "편성표", "주간 편성표 관리 탭", R.drawable.ic_calendar, false);
      addNavButton(bottomBar, "SETTINGS", "설정", "설정 탭", R.drawable.ic_tune, false);
      Button botExitBtn = Ui.tile(this, "종료", R.drawable.ic_exit, Ui.DANGER, () -> Ui.exitApp(this));
      botExitBtn.setContentDescription("앱 종료");
      botExitBtn.setMinWidth(Ui.dp(this, 60));
      botExitBtn.setMinimumWidth(Ui.dp(this, 60));
      LinearLayout.LayoutParams botExitLp = new LinearLayout.LayoutParams(0, Ui.dp(this, 58), 1);
      int bm = Ui.dp(this, 2);
      botExitLp.setMargins(bm, bm, bm, bm);
      bottomBar.addView(botExitBtn, botExitLp);
      bottomScroll.addView(bottomBar, new LinearLayout.LayoutParams(-1, -2));
      shell.addView(bottomScroll, new LinearLayout.LayoutParams(-1, -2));
    }

    updateNavSelection();
    renderCurrentTab();
  }

  private void addNavButton(
      LinearLayout parent,
      String key,
      String label,
      String contentDesc,
      int iconRes,
      boolean rail) {
    Button b = Ui.tile(this, label, iconRes, Ui.INK, () -> switchTab(key));
    b.setContentDescription(contentDesc);
    if (!rail) {
      b.setMinWidth(Ui.dp(this, 64));
      b.setMinimumWidth(Ui.dp(this, 64));
    }
    LinearLayout.LayoutParams lp =
        rail
            ? new LinearLayout.LayoutParams(-1, Ui.dp(this, 58))
            : new LinearLayout.LayoutParams(0, Ui.dp(this, 58), 1);
    int m = Ui.dp(this, 2);
    lp.setMargins(m, m, m, m);
    navButtons.put(key, b);
    parent.addView(b, lp);
  }

  public void openAlbumManagement(String albumId) {
    pendingFocusAlbumId = albumId;
    switchTab("ALBUMS");
  }

  public void switchTab(String key) {
    if (key == null) return;
    currentTab = key;
    if (!"CUSTOMIZE".equals(key)) draftConfig = null;
    updateNavSelection();
    renderCurrentTab();
  }

  private static int navIconForKey(String key) {
    switch (key) {
      case "FRAME":
        return R.drawable.ic_screen;
      case "PHOTOS":
        return R.drawable.ic_gallery;
      case "ALBUMS":
        return R.drawable.ic_folder;
      case "TEXTS":
        return R.drawable.ic_quote;
      case "SCENES":
        return R.drawable.ic_scene;
      case "SCHEDULES":
        return R.drawable.ic_calendar;
      case "SETTINGS":
      default:
        return R.drawable.ic_tune;
    }
  }

  private void updateNavSelection() {
    String highlighted = "CUSTOMIZE".equals(currentTab) ? "SCENES" : currentTab;
    for (Map.Entry<String, Button> e : navButtons.entrySet()) {
      boolean sel = e.getKey().equals(highlighted);
      Button b = e.getValue();
      int iconRes = navIconForKey(e.getKey());
      int color = sel ? Ui.PRIMARY : Ui.MUTED;
      b.setTextColor(color);
      b.setTypeface(Typeface.DEFAULT, sel ? Typeface.BOLD : Typeface.NORMAL);
      b.setSelected(sel);
      b.setBackground(
          Ui.bg(sel ? Ui.SURFACE_SUBTLE : Color.TRANSPARENT, Ui.dp(this, 12)));
      Ui.tileIcon(b, iconRes, color);
    }
  }

  private void renderCurrentTab() {
    if (contentPane == null || !Ui.alive(this)) return;
    releaseActiveControllers();
    switch (currentTab) {
      case "PHOTOS":
        libraryController =
            LibraryActivity.buildView(contentPane, this, this::pickPhotosFromDevice);
        break;
      case "ALBUMS":
        String focusAlbum = pendingFocusAlbumId;
        pendingFocusAlbumId = null;
        albumsController =
            AlbumsActivity.buildView(
                contentPane, this, this::pickPhotosForAlbumFromDevice, focusAlbum);
        break;
      case "TEXTS":
        quotesController = QuotesActivity.buildView(contentPane, this);
        break;
      case "SCENES":
        scenesController = ScenesActivity.buildView(contentPane, this);
        break;
      case "SCHEDULES":
        schedulesController = SchedulesActivity.buildView(contentPane, this);
        break;
      case "CUSTOMIZE":
        buildCustomizeTab(contentPane);
        break;
      case "SETTINGS":
        settingsController =
            SettingsActivity.buildView(contentPane, this, () -> switchTab("CUSTOMIZE"));
        break;
      case "FRAME":
      default:
        buildFrameTab(contentPane);
        break;
    }
  }

  // Section 6.1: 액자 탭 (실제 FrameView 렌더러 공유 미리보기 + 액자 시작 주요 버튼 + 빈 상태 온보딩)
  private void buildFrameTab(LinearLayout container) {
    container.removeAllViews();
    ScrollView sv = new ScrollView(this);
    sv.setFillViewport(true);
    LinearLayout root = Ui.column(this);
    sv.addView(root);
    container.addView(sv, new LinearLayout.LayoutParams(-1, -1));

    Ui.header(this, root, "눈부신 순간을 담다", "좋아하는 사진을 편안하게 바라보는 우리만의 작은 갤러리.");
    Ui.gap(root, 16);

    int widthDp = getResources().getConfiguration().screenWidthDp;
    boolean twoPane = widthDp >= 840;
    LinearLayout mainArea = twoPane ? Ui.row(this) : Ui.column(this);
    mainArea.setGravity(Gravity.TOP);
    root.addView(mainArea, new LinearLayout.LayoutParams(-1, -2));

    LinearLayout previewCard = Ui.card(mainArea);
    FrameView preview = new FrameView(this);
    preview.setPreviewMode(true);
    preview.setBackground(Ui.bg(Ui.DARK_BG, Ui.dp(this, 14)));
    preview.setClipToOutline(true);
    int previewHeightDp = twoPane ? 340 : 250;
    previewCard.addView(preview, new LinearLayout.LayoutParams(-1, Ui.dp(this, previewHeightDp)));
    Ui.gap(previewCard, 12);
    TextView summaryText = Ui.text(this, "보관함을 확인하고 있어요…", 14, Ui.MUTED);
    previewCard.addView(summaryText);

    LinearLayout actionCol = Ui.column(this);
    if (twoPane) {
      LinearLayout.LayoutParams lpLeft = new LinearLayout.LayoutParams(0, -2, 6);
      lpLeft.setMarginEnd(Ui.dp(this, 16));
      previewCard.setLayoutParams(lpLeft);
      mainArea.addView(actionCol, new LinearLayout.LayoutParams(0, -2, 4));
    } else {
      mainArea.addView(actionCol, new LinearLayout.LayoutParams(-1, -2));
    }

    LinearLayout actionCard = Ui.card(actionCol);
    LinearLayout studioQuickCard = Ui.card(actionCol);

    a.io.execute(
        () -> {
          if (a.studio != null) a.studio.ensureSeeded();
          List<Photo> active = a.db.photos().active();
          int n = active.size();
          long bytes = a.db.photos().used();
          int albumCount = a.studio != null ? a.studio.dao().albums().size() : 0;
          int textCount = a.studio != null ? a.studio.dao().textItems().size() : 0;
          int sceneCount = a.studio != null ? a.studio.dao().scenes().size() : 0;
          int schedCount = a.studio != null ? a.studio.dao().scheduleRules().size() : 0;

          Core.ScheduleEvaluation eval =
              (a.studio != null && a.prefs.studioScheduleEnabled())
                  ? a.studio.evaluateCurrentSchedule(System.currentTimeMillis())
                  : null;
          Scene activeScene =
              (eval != null && a.studio != null)
                  ? a.studio.dao().scene(eval.activeSceneId)
                  : null;
          List<TextItem> tItems =
              a.studio != null
                  ? (activeScene != null
                      ? a.studio.resolveTextsForScene(activeScene.id)
                      : a.studio.dao().enabledTextItems())
                  : Collections.emptyList();

          String samplePhrase;
          if (!a.prefs.phrase().isEmpty()) {
            samplePhrase = a.prefs.phrase();
          } else if (!tItems.isEmpty()) {
            TextItem ti = tItems.get(0);
            String authorStr = ti.author;
            if (!ti.title.isEmpty() && !ti.author.contains(ti.title)) {
              authorStr = ti.author.isEmpty() ? ti.title : (ti.author + " · " + ti.title);
            }
            String k = "POEM".equals(ti.kind) || ti.body.indexOf('\n') >= 0 ? "POEM" : "QUOTE";
            samplePhrase = new Core.QuoteEntry(k, ti.body, authorStr).encode();
          } else {
            samplePhrase = "눈이 머무는 곳에 마음도 잠시 쉬어갑니다.|누니담";
          }
          List<Photo> previewPool =
              (activeScene != null && a.studio != null)
                  ? a.studio.resolvePhotosForScene(activeScene.id)
                  : active;
          if (previewPool.isEmpty()) previewPool = active;
          Photo heroPhoto = null;
          String lastId = a.prefs.raw.getString("lastPhoto", "");
          for (Photo p : previewPool) {
            if (p.id.equals(lastId)) {
              heroPhoto = p;
              break;
            }
          }
          if (heroPhoto == null && !previewPool.isEmpty()) heroPhoto = previewPool.get(0);
          Bitmap heroBmp = heroPhoto != null ? a.store.load(heroPhoto, 960) : null;
          Photo finalHero = heroPhoto;
          Scene finalScene = activeScene;

          a.main.post(
              () -> {
                if (!Ui.alive(this) || !"FRAME".equals(currentTab)) return;
                if (finalScene != null) {
                  preview.setOverrideConfig(FrameView.Config.fromScene(finalScene, a.prefs));
                } else {
                  preview.setOverrideConfig(null);
                }
                if (finalHero != null && heroBmp != null) {
                  preview.show(finalHero, heroBmp, samplePhrase);
                } else {
                  preview.showSample(samplePhrase);
                }
                String sceneSummary =
                    finalScene != null
                        ? ("현재 장면: " + finalScene.name + " (" + eval.activeRuleName + ")")
                        : ("화면 구성: "
                            + ("PHOTO".equals(a.prefs.mode())
                                ? "사진 액자"
                                : "BOARD".equals(a.prefs.mode()) ? "생활 보드" : "스마트 매거진"));
                summaryText.setText(
                    String.format(
                        Locale.KOREA,
                        "%s\n사진 %d장 (%.1f MB) · 앨범 %d개 · 글귀 %d편 · 장면 %d개 · 편성 %d건",
                        sceneSummary,
                        n,
                        bytes / 1048576d,
                        albumCount,
                        textCount,
                        sceneCount,
                        schedCount));

                actionCard.removeAllViews();
                if (n == 0) {
                  Ui.section(actionCard, R.drawable.ic_plus, "첫 번째 사진을 담아보세요");
                  actionCard.addView(
                      Ui.text(
                          this,
                          "위 화면은 예시 미리보기입니다(보관함에 자동 추가되지 않음). 사진을 담는 방법을 선택해 시작해 보세요.",
                          14,
                          Ui.MUTED));
                  Ui.add(
                      actionCard,
                      "이 기기에서 사진 선택",
                      true,
                      R.drawable.ic_gallery,
                      this::pickPhotosFromDevice);
                  Ui.add(
                      actionCard,
                      "스마트폰에서 보내기 · QR",
                      false,
                      R.drawable.ic_phone,
                      () -> Ui.open(this, TransferActivity.class));
                  Ui.add(
                      actionCard,
                      "자동 동기화 폴더 선택",
                      false,
                      R.drawable.ic_folder,
                      this::pickSyncFolder);
                } else {
                  Ui.section(actionCard, R.drawable.ic_play, "지금 감상하기");
                  Ui.add(
                      actionCard,
                      "액자 시작",
                      true,
                      R.drawable.ic_play,
                      () -> Ui.open(this, FrameActivity.class));
                  Ui.add(
                      actionCard,
                      "이 기기에서 사진 추가",
                      false,
                      R.drawable.ic_plus,
                      this::pickPhotosFromDevice);
                  Ui.add(
                      actionCard,
                      "스마트폰에서 보내기 · QR",
                      false,
                      R.drawable.ic_phone,
                      () -> Ui.open(this, TransferActivity.class));
                }

                studioQuickCard.removeAllViews();
                Ui.section(studioQuickCard, R.drawable.ic_scene, "누니담 스튜디오 관리");
                studioQuickCard.addView(
                    Ui.text(
                        this,
                        "PC 연결 없이도 이 기기에서 바로 앨범, 글귀 라이브러리, 재생 장면, 주간 편성표를 관리할 수 있습니다.",
                        13.5f,
                        Ui.MUTED));
                Ui.add(
                    studioQuickCard,
                    "앨범 관리 (" + albumCount + "개)",
                    false,
                    R.drawable.ic_folder,
                    () -> switchTab("ALBUMS"));
                Ui.add(
                    studioQuickCard,
                    "글귀 라이브러리 (" + textCount + "편)",
                    false,
                    R.drawable.ic_quote,
                    () -> switchTab("TEXTS"));
                Ui.add(
                    studioQuickCard,
                    "재생 장면 관리 (" + sceneCount + "개)",
                    false,
                    R.drawable.ic_scene,
                    () -> switchTab("SCENES"));
                Ui.add(
                    studioQuickCard,
                    "주간 편성표 관리 (" + schedCount + "건)",
                    false,
                    R.drawable.ic_calendar,
                    () -> switchTab("SCHEDULES"));

                if (!homeOpened && getIntent().hasCategory(Intent.CATEGORY_HOME) && n > 0) {
                  homeOpened = true;
                  Ui.open(this, FrameActivity.class);
                }
              });
        });
  }

  // Section 6.4: 꾸미기 탭 (실제 FrameView 미리보기 + 화면 구성 → 색감 → 시계·날짜·문구 → 사진 전환 + 적용/취소)
  private void buildCustomizeTab(LinearLayout container) {
    container.removeAllViews();
    if (draftConfig == null) draftConfig = FrameView.Config.from(a.prefs);

    int widthDp = getResources().getConfiguration().screenWidthDp;
    boolean twoPane = widthDp >= 840;

    Ui.header(this, container, "액자 꾸미기", "실제 미리보기를 보며 구성을 바꾼 뒤 ‘적용’을 눌러 확정하세요.");
    Ui.gap(container, 12);

    LinearLayout body = twoPane ? Ui.row(this) : Ui.column(this);
    body.setGravity(Gravity.TOP);
    container.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

    // Fixed preview pane (top or left)
    LinearLayout previewWrap = Ui.column(this);
    FrameView preview = new FrameView(this);
    preview.setPreviewMode(true);
    preview.setOverrideConfig(draftConfig);
    preview.setBackground(Ui.strokeBg(Ui.DARK_BG, Ui.OUTLINE, Ui.dp(this, 1), Ui.dp(this, 16)));
    preview.setClipToOutline(true);
    int prevHeight = twoPane ? 340 : 210;
    previewWrap.addView(preview, new LinearLayout.LayoutParams(-1, Ui.dp(this, prevHeight)));
    Ui.gap(previewWrap, 10);

    // Apply / Cancel action bar right under preview
    LinearLayout applyRow = Ui.row(this);
    Button cancelBtn =
        Ui.button(
            this,
            "취소 (이전 값 복원)",
            false,
            R.drawable.ic_refresh,
            () -> {
              draftConfig = FrameView.Config.from(a.prefs);
              buildCustomizeTab(container);
              Ui.toast(this, "이전 설정으로 되돌렸습니다.");
            });
    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1);
    cp.setMarginEnd(Ui.dp(this, 8));
    applyRow.addView(cancelBtn, cp);
    Button applyBtn =
        Ui.button(
            this,
            "적용",
            true,
            R.drawable.ic_check,
            () -> {
              draftConfig.applyTo(a.prefs);
              FrameView.Config snapCfg = draftConfig;
              a.io.execute(
                  () -> {
                    try {
                      if (a.studio != null) {
                        a.studio.ensureSeeded();
                        Scene def = a.studio.dao().defaultScene();
                        if (def == null) def = a.studio.dao().scene("scene_default");
                        if (def != null) {
                          def.layout = snapCfg.mode;
                          def.theme = snapCfg.theme;
                          def.showClock = snapCfg.showClock;
                          def.showCaptureDate = snapCfg.showCaptureDate;
                          def.textDisplayMode = snapCfg.quoteOnPhoto ? "OVERLAY" : "NONE";
                          def.quoteCycle = snapCfg.quoteCycle;
                          def.fontScale = snapCfg.fontScale;
                          def.seconds = snapCfg.seconds;
                          def.transitionEffect = snapCfg.animate ? "ZOOM" : "NONE";
                          def.updatedAt = System.currentTimeMillis();
                          a.studio.dao().saveScene(def);
                          a.studio.publishToTablet("간편 꾸미기에서 기본 장면 반영");
                        }
                      }
                    } catch (Exception ignored) {
                    }
                  });
              Ui.toast(this, "액자 꾸미기 설정을 기본 장면에 적용했습니다.");
            });
    applyRow.addView(applyBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1));
    previewWrap.addView(applyRow);

    ScrollView optionsScroll = new ScrollView(this);
    LinearLayout optionsCol = Ui.column(this);
    optionsScroll.addView(optionsCol);

    if (twoPane) {
      LinearLayout.LayoutParams lpLeft = new LinearLayout.LayoutParams(0, -2, 5);
      lpLeft.setMarginEnd(Ui.dp(this, 18));
      body.addView(previewWrap, lpLeft);
      body.addView(optionsScroll, new LinearLayout.LayoutParams(0, -1, 5));
    } else {
      body.addView(previewWrap, new LinearLayout.LayoutParams(-1, -2));
      Ui.gap(body, 10);
      body.addView(optionsScroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    // Load user photo or sample into preview
    a.io.execute(
        () -> {
          if (a.studio != null) a.studio.ensureSeeded();
          List<Photo> active = a.db.photos().active();
          List<TextItem> tItems =
              a.studio != null ? a.studio.dao().enabledTextItems() : Collections.emptyList();
          String samplePhrase;
          if (!a.prefs.phrase().isEmpty()) {
            samplePhrase = a.prefs.phrase();
          } else if (!tItems.isEmpty()) {
            TextItem ti = tItems.get(0);
            String authorStr = ti.author;
            if (!ti.title.isEmpty() && !ti.author.contains(ti.title)) {
              authorStr = ti.author.isEmpty() ? ti.title : (ti.author + " · " + ti.title);
            }
            String k = "POEM".equals(ti.kind) || ti.body.indexOf('\n') >= 0 ? "POEM" : "QUOTE";
            samplePhrase = new Core.QuoteEntry(k, ti.body, authorStr).encode();
          } else {
            samplePhrase = "POEM|산에는 꽃 피네\\n꽃이 피네\\n갈 봄 여름 없이\\n꽃이 피네|김소월 · 산유화";
          }
          Photo p = active.isEmpty() ? null : active.get(0);
          Bitmap b = p != null ? a.store.load(p, 800) : null;
          a.main.post(
              () -> {
                if (!Ui.alive(this)) return;
                if (p != null && b != null) preview.show(p, b, samplePhrase);
                else preview.showSample(samplePhrase);
              });
        });

    // 1. 화면 구성 (with visual thumbnail cards)
    LinearLayout modeCard = Ui.card(optionsCol);
    Ui.section(modeCard, R.drawable.ic_screen, "1. 화면 구성");
    LinearLayout modeRow = Ui.row(this);
    addModeOption(modeRow, preview, "PHOTO", "사진 액자", "전체 사진 중심");
    addModeOption(modeRow, preview, "MAGAZINE", "스마트 매거진", "비율별 자동 분할");
    addModeOption(modeRow, preview, "BOARD", "생활 보드", "시계 · 이번 달 달력");
    modeCard.addView(modeRow);

    // 2. 색감 (with color swatch cards)
    LinearLayout themeCard = Ui.card(optionsCol);
    Ui.section(themeCard, R.drawable.ic_sun, "2. 색감");
    LinearLayout themeRow = Ui.row(this);
    addThemeOption(themeRow, preview, "MILK", "밀크", 0xfff4f2ec, 0xff24332b);
    addThemeOption(themeRow, preview, "INK", "잉크", 0xff18221d, 0xffedefe8);
    addThemeOption(themeRow, preview, "SAGE", "세이지", 0xffdce4d5, 0xff24332b);
    themeCard.addView(themeRow);

    // 3. 시계 · 날짜 · 문구
    LinearLayout infoCard = Ui.card(optionsCol);
    Ui.section(infoCard, R.drawable.ic_quote, "3. 시계 · 날짜 · 문구");
    Switch clockSw = new Switch(this);
    clockSw.setText("시계 표시");
    clockSw.setTextColor(Ui.INK);
    clockSw.setChecked(draftConfig.showClock);
    clockSw.setOnCheckedChangeListener(
        (v, on) -> {
          draftConfig.showClock = on;
          preview.setOverrideConfig(draftConfig);
        });
    infoCard.addView(clockSw);

    Switch dateSw = new Switch(this);
    dateSw.setText("촬영일 표시");
    dateSw.setTextColor(Ui.INK);
    dateSw.setChecked(draftConfig.showCaptureDate);
    dateSw.setOnCheckedChangeListener(
        (v, on) -> {
          draftConfig.showCaptureDate = on;
          preview.setOverrideConfig(draftConfig);
        });
    infoCard.addView(dateSw);

    Switch overlaySw = new Switch(this);
    overlaySw.setText("전체 화면 사진 위에도 명언·시 표시");
    overlaySw.setTextColor(Ui.INK);
    overlaySw.setChecked(draftConfig.quoteOnPhoto);
    overlaySw.setOnCheckedChangeListener(
        (v, on) -> {
          draftConfig.quoteOnPhoto = on;
          preview.setOverrideConfig(draftConfig);
        });
    infoCard.addView(overlaySw);

    Ui.gap(infoCard, 8);
    infoCard.addView(Ui.text(this, "사진 위 시계·문구 글자 크기", 14, Ui.MUTED));
    LinearLayout scaleRow = Ui.row(this);
    int[] scales = new int[] {100, 120, 140};
    String[] scaleNames = new String[] {"기본 (100%)", "크게 (120%)", "아주 크게 (140%)"};
    for (int i = 0; i < scales.length; i++) {
      int val = scales[i];
      boolean sel = draftConfig.fontScale == val;
      Button sb =
          Ui.button(
              this,
              scaleNames[i],
              sel,
              () -> {
                draftConfig.fontScale = val;
                buildCustomizeTab(container);
              });
      sb.setTextSize(13f);
      LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1);
      if (i < scales.length - 1) sp.setMarginEnd(Ui.dp(this, 6));
      scaleRow.addView(sb, sp);
    }
    infoCard.addView(scaleRow);

    Ui.gap(infoCard, 8);
    infoCard.addView(Ui.text(this, "표시할 글 종류", 14, Ui.MUTED));
    Spinner kindSpin = new Spinner(this);
    String[] kindNames = new String[] {"명언과 시 모두", "명언 · 짧은 문구만", "시(詩)만", "표시 안 함"};
    String[] kindVals = new String[] {"ALL", "QUOTE", "POEM", "NONE"};
    kindSpin.setAdapter(
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, kindNames));
    for (int i = 0; i < kindVals.length; i++)
      if (kindVals[i].equals(draftConfig.quoteKind)) kindSpin.setSelection(i);
    kindSpin.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> p) {}

          public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
            draftConfig.quoteKind = kindVals[pos];
            preview.setOverrideConfig(draftConfig);
          }
        });
    infoCard.addView(kindSpin, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));

    infoCard.addView(Ui.text(this, "글이 바뀌는 주기", 14, Ui.MUTED));
    Spinner cycleSpin = new Spinner(this);
    String[] cycleNames = new String[] {"사진이 바뀔 때마다", "15분마다", "1시간마다", "하루에 한 번"};
    String[] cycleVals = new String[] {"PHOTO", "MINUTES_15", "HOUR", "DAY"};
    cycleSpin.setAdapter(
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, cycleNames));
    for (int i = 0; i < cycleVals.length; i++)
      if (cycleVals[i].equals(draftConfig.quoteCycle)) cycleSpin.setSelection(i);
    cycleSpin.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> p) {}

          public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
            draftConfig.quoteCycle = cycleVals[pos];
          }
        });
    infoCard.addView(cycleSpin, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));

    Ui.add(
        infoCard,
        "글귀 라이브러리 관리 (카테고리·CSV·편집)",
        false,
        R.drawable.ic_quote,
        () -> switchTab("TEXTS"));
    Ui.add(
        infoCard,
        "시간대별 재생 장면 상세 관리",
        false,
        R.drawable.ic_scene,
        () -> switchTab("SCENES"));

    // 4. 사진 전환
    LinearLayout transCard = Ui.card(optionsCol);
    Ui.section(transCard, R.drawable.ic_clock, "4. 사진 전환");
    Switch animSw = new Switch(this);
    animSw.setText("사진을 천천히 확대하는 효과");
    animSw.setTextColor(Ui.INK);
    animSw.setChecked(draftConfig.animate);
    animSw.setOnCheckedChangeListener((v, on) -> draftConfig.animate = on);
    transCard.addView(animSw);

    Switch favOnlySw = new Switch(this);
    favOnlySw.setText("즐겨찾기한 사진만 재생");
    favOnlySw.setTextColor(Ui.INK);
    favOnlySw.setChecked(a.prefs.raw.getBoolean("favourites", false));
    favOnlySw.setOnCheckedChangeListener((v, on) -> a.prefs.flag("favourites", on));
    transCard.addView(favOnlySw);

    Switch memSw = new Switch(this);
    memSw.setText("N년 전 오늘의 사진 먼저 보여주기");
    memSw.setTextColor(Ui.INK);
    memSw.setChecked(a.prefs.raw.getBoolean("memories", true));
    memSw.setOnCheckedChangeListener((v, on) -> a.prefs.flag("memories", on));
    transCard.addView(memSw);

    Ui.gap(transCard, 6);
    transCard.addView(Ui.text(this, "사진 전환 간격(초)", 14, Ui.MUTED));
    LinearLayout secRow = Ui.row(this);
    int[] secPresets = new int[] {15, 30, 45, 120, 300};
    String[] secLabels = new String[] {"15초", "30초", "45초", "2분", "5분"};
    for (int i = 0; i < secPresets.length; i++) {
      int sec = secPresets[i];
      boolean sel = draftConfig.seconds == sec;
      Button b =
          Ui.button(
              this,
              secLabels[i],
              sel,
              () -> {
                draftConfig.seconds = sec;
                buildCustomizeTab(container);
              });
      b.setTextSize(13f);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1);
      if (i < secPresets.length - 1) lp.setMarginEnd(Ui.dp(this, 4));
      secRow.addView(b, lp);
    }
    transCard.addView(secRow);
  }

  private void addModeOption(
      LinearLayout row, FrameView preview, String key, String title, String sub) {
    boolean sel = key.equals(draftConfig.mode);
    LinearLayout card = Ui.column(this);
    card.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
    card.setBackground(
        Ui.strokeBg(
            sel ? Ui.SURFACE_SUBTLE : Ui.SURFACE,
            sel ? Ui.PRIMARY : Ui.OUTLINE,
            Ui.dp(this, sel ? 2 : 1),
            Ui.dp(this, 12)));
    card.setClickable(true);
    card.setFocusable(true);
    card.setOnClickListener(
        v -> {
          draftConfig.mode = key;
          preview.setOverrideConfig(draftConfig);
          renderCurrentTab();
        });
    TextView tvTitle = Ui.text(this, title, 14.5f, sel ? Ui.PRIMARY : Ui.INK);
    tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
    card.addView(tvTitle);
    card.addView(Ui.text(this, sub, 12f, Ui.MUTED));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
    lp.setMarginEnd(Ui.dp(this, 6));
    row.addView(card, lp);
  }

  private void addThemeOption(
      LinearLayout row, FrameView preview, String key, String title, int bgCol, int fgCol) {
    boolean sel = key.equals(draftConfig.theme);
    LinearLayout card = Ui.column(this);
    card.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
    card.setBackground(
        Ui.strokeBg(
            sel ? Ui.SURFACE_SUBTLE : Ui.SURFACE,
            sel ? Ui.PRIMARY : Ui.OUTLINE,
            Ui.dp(this, sel ? 2 : 1),
            Ui.dp(this, 12)));
    card.setClickable(true);
    card.setFocusable(true);
    card.setOnClickListener(
        v -> {
          draftConfig.theme = key;
          preview.setOverrideConfig(draftConfig);
          renderCurrentTab();
        });
    View swatch = new View(this);
    swatch.setBackground(Ui.strokeBg(bgCol, fgCol, Ui.dp(this, 1), Ui.dp(this, 8)));
    card.addView(swatch, new LinearLayout.LayoutParams(-1, Ui.dp(this, 24)));
    Ui.gap(card, 6);
    TextView tv = Ui.text(this, title, 14f, sel ? Ui.PRIMARY : Ui.INK);
    tv.setTypeface(Typeface.DEFAULT_BOLD);
    card.addView(tv);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
    lp.setMarginEnd(Ui.dp(this, 6));
    row.addView(card, lp);
  }

  public void pickPhotosFromDevice() {
    Intent i =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("image/*")
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    startActivityForResult(i, 1);
  }

  public void pickPhotosForAlbumFromDevice(String albumId) {
    pendingImportAlbumId = albumId == null ? "" : albumId;
    Intent i =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("image/*")
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    startActivityForResult(i, AlbumsActivity.REQ_PICK_ALBUM_PHOTOS);
  }

  public void pickSyncFolder() {
    startActivityForResult(
        new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
        2);
  }

  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 10 || request == 11 || request == 12 || request == 13) {
      if (settingsController != null) settingsController.onActivityResult(request, result, data);
      return;
    }
    if (request == QuotesActivity.REQ_IMPORT_CSV
        || request == QuotesActivity.REQ_EXPORT_CSV
        || request == QuotesActivity.REQ_EXPORT_TEMPLATE_CSV) {
      if (quotesController != null) quotesController.onActivityResult(request, result, data);
      return;
    }
    if (request == AlbumsActivity.REQ_PICK_ALBUM_PHOTOS) {
      if (result == RESULT_OK && data != null) {
        AlbumsActivity.importPhotosIntoAlbum(
            this,
            a,
            data,
            pendingImportAlbumId,
            () -> {
              if (albumsController != null) albumsController.refresh();
            });
      }
      return;
    }
    if (result != RESULT_OK || data == null) return;
    if (request == 2 && data.getData() != null) {
      try {
        getContentResolver()
            .takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        a.prefs.text("folder", data.getData().toString());
        FolderSync.schedule(this);
        FolderSync.now(this);
        Ui.toast(this, "폴더의 사진을 가져옵니다. 하위 폴더는 제외합니다.");
      } catch (Exception e) {
        Ui.error(this, e);
      }
      return;
    }
    if (request == 1) {
      List<Uri> list = new ArrayList<>();
      if (data.getClipData() != null)
        for (int i = 0; i < data.getClipData().getItemCount(); i++)
          list.add(data.getClipData().getItemAt(i).getUri());
      else if (data.getData() != null) list.add(data.getData());
      importUriList(list);
    }
  }

  private void importUriList(List<Uri> list) {
    if (list.isEmpty()) return;
    TextView progress = Ui.text(this, "사진을 준비하고 있어요", 16, Ui.INK);
    progress.setPadding(40, 30, 40, 30);
    progressDialog =
        new AlertDialog.Builder(this)
            .setTitle("사진 담는 중")
            .setView(progress)
            .setCancelable(false)
            .create();
    progressDialog.show();
    a.imports.execute(
        () -> {
          int ok = 0;
          List<Uri> failedUris = new ArrayList<>();
          String lastError = "";
          for (int idx = 0; idx < list.size(); idx++) {
            Uri uri = list.get(idx);
            try {
              a.store.fromUri(uri, name(uri), true);
              ok++;
            } catch (Exception e) {
              failedUris.add(uri);
              lastError = e.getMessage() == null ? "가져오기 오류" : e.getMessage();
            }
            int done = ok + failedUris.size();
            a.main.post(
                () -> {
                  if (Ui.alive(this))
                    progress.setText(done + " / " + list.size() + "장 처리 중");
                });
          }
          int finalOk = ok;
          String errText = lastError;
          a.main.post(
              () -> {
                if (!Ui.alive(this)) return;
                progressDialog.dismiss();
                renderCurrentTab();
                if (failedUris.isEmpty()) {
                  Ui.toast(
                      this,
                      finalOk
                          + "장 사진을 ‘모든 사진’에 담았습니다. 다른 앨범에 정리하려면 사진 탭에서 사진을 선택한 뒤 ‘앨범에 담기’를 누르거나 새 앨범을 만들어 주세요.");
                } else {
                  new AlertDialog.Builder(this)
                      .setTitle("일부 사진을 담지 못했어요")
                      .setMessage(
                          "성공: "
                              + finalOk
                              + "장 · 실패: "
                              + failedUris.size()
                              + "장\n사유: "
                              + errText)
                      .setPositiveButton(
                          "실패한 " + failedUris.size() + "장 다시 시도",
                          (d, w) -> importUriList(failedUris))
                      .setNegativeButton("닫기", null)
                      .show();
                }
              });
        });
  }

  private String name(Uri u) {
    try (Cursor c =
        getContentResolver()
            .query(u, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (c != null && c.moveToFirst()) return c.getString(0);
    } catch (Exception ignored) {
    }
    return "사진";
  }
}
