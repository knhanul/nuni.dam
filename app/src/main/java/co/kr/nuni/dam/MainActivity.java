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
 * Adaptive 4-destination management shell (Section 4 & Section 6):
 * 1) 액자 (FRAME)
 * 2) 사진 (PHOTOS)
 * 3) 꾸미기 (CUSTOMIZE)
 * 4) 설정 (SETTINGS)
 *
 * Window width breakpoints:
 * - < 600dp: Bottom navigation (4 destinations) + single content pane
 * - 600~839dp: Left navigation rail + constrained content pane
 * - >= 840dp: Left navigation rail + 2-pane content (preview/list + detail/options)
 */
public final class MainActivity extends Activity implements PhotoStore.Listener {
  private NuniApp a;
  private String currentTab = "FRAME";
  private boolean homeOpened;
  private AlertDialog progressDialog;
  private LinearLayout contentPane;
  private final Map<String, Button> navButtons = new HashMap<>();
  private LibraryActivity.Controller libraryController;
  private SettingsActivity.Controller settingsController;
  private FrameView.Config draftConfig;

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
    renderCurrentTab();
  }

  protected void onDestroy() {
    a.store.unlisten(this);
    if (libraryController != null) libraryController.release();
    if (progressDialog != null) progressDialog.dismiss();
    super.onDestroy();
  }

  public void changed() {
    runOnUiThread(this::renderCurrentTab);
  }

  private void buildShell() {
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
      rail.setPadding(rPad, Ui.dp(this, 20), rPad, Ui.dp(this, 20));
      rail.setGravity(Gravity.CENTER_HORIZONTAL);
      ImageView logo = new ImageView(this);
      logo.setImageResource(R.drawable.ic_nuni);
      logo.setBackground(Ui.bg(Ui.SURFACE_SUBTLE, Ui.dp(this, 10)));
      logo.setClipToOutline(true);
      rail.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
      Ui.gap(rail, 20);
      addNavButton(rail, "FRAME", "액자", R.drawable.ic_screen, true);
      addNavButton(rail, "PHOTOS", "사진", R.drawable.ic_gallery, true);
      addNavButton(rail, "CUSTOMIZE", "꾸미기", R.drawable.ic_sun, true);
      addNavButton(rail, "SETTINGS", "설정", R.drawable.ic_tune, true);
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

      LinearLayout bottomBar = Ui.row(this);
      bottomBar.setBackgroundColor(Ui.SURFACE);
      bottomBar.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
      addNavButton(bottomBar, "FRAME", "액자", R.drawable.ic_screen, false);
      addNavButton(bottomBar, "PHOTOS", "사진", R.drawable.ic_gallery, false);
      addNavButton(bottomBar, "CUSTOMIZE", "꾸미기", R.drawable.ic_sun, false);
      addNavButton(bottomBar, "SETTINGS", "설정", R.drawable.ic_tune, false);
      shell.addView(bottomBar, new LinearLayout.LayoutParams(-1, -2));
    }

    updateNavSelection();
    renderCurrentTab();
  }

  private void addNavButton(
      LinearLayout parent, String key, String label, int iconRes, boolean rail) {
    Button b = Ui.tile(this, label, iconRes, Ui.INK, () -> switchTab(key));
    b.setContentDescription(label + " 탭");
    LinearLayout.LayoutParams lp =
        rail
            ? new LinearLayout.LayoutParams(-1, Ui.dp(this, 64))
            : new LinearLayout.LayoutParams(0, Ui.dp(this, 58), 1);
    int m = Ui.dp(this, 3);
    lp.setMargins(m, m, m, m);
    navButtons.put(key, b);
    parent.addView(b, lp);
  }

  public void switchTab(String key) {
    if (key == null) return;
    currentTab = key;
    if (!"CUSTOMIZE".equals(key)) draftConfig = null;
    updateNavSelection();
    renderCurrentTab();
  }

  private void updateNavSelection() {
    for (Map.Entry<String, Button> e : navButtons.entrySet()) {
      boolean sel = e.getKey().equals(currentTab);
      Button b = e.getValue();
      int iconRes =
          "FRAME".equals(e.getKey())
              ? R.drawable.ic_screen
              : "PHOTOS".equals(e.getKey())
                  ? R.drawable.ic_gallery
                  : "CUSTOMIZE".equals(e.getKey()) ? R.drawable.ic_sun : R.drawable.ic_tune;
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
    if (!"PHOTOS".equals(currentTab) && libraryController != null) {
      libraryController.release();
      libraryController = null;
    }
    switch (currentTab) {
      case "PHOTOS":
        libraryController =
            LibraryActivity.buildView(contentPane, this, this::pickPhotosFromDevice);
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
    actionCol.removeAllViews();
    actionCol.addView(actionCard);

    a.io.execute(
        () -> {
          List<Photo> active = a.db.photos().active();
          int n = active.size();
          long bytes = a.db.photos().used();
          List<Quote> qs = a.db.photos().quotes();
          String samplePhrase =
              !a.prefs.phrase().isEmpty()
                  ? a.prefs.phrase()
                  : qs.isEmpty() ? "눈이 머무는 곳에 마음도 잠시 쉬어갑니다.|누니담" : qs.get(0).text;
          Photo heroPhoto = null;
          String lastId = a.prefs.raw.getString("lastPhoto", "");
          for (Photo p : active) {
            if (p.id.equals(lastId)) {
              heroPhoto = p;
              break;
            }
          }
          if (heroPhoto == null && !active.isEmpty()) heroPhoto = active.get(0);
          Bitmap heroBmp = heroPhoto != null ? a.store.load(heroPhoto, 960) : null;
          Photo finalHero = heroPhoto;

          a.main.post(
              () -> {
                if (!Ui.alive(this) || !"FRAME".equals(currentTab)) return;
                if (finalHero != null && heroBmp != null) {
                  preview.show(finalHero, heroBmp, samplePhrase);
                } else {
                  preview.showSample(samplePhrase);
                }
                String modeName =
                    "PHOTO".equals(a.prefs.mode())
                        ? "사진 액자"
                        : "BOARD".equals(a.prefs.mode()) ? "생활 보드" : "스마트 매거진";
                String themeName =
                    "INK".equals(a.prefs.theme())
                        ? "잉크"
                        : "SAGE".equals(a.prefs.theme()) ? "세이지" : "밀크";
                String nightSchedule =
                    a.prefs.raw.getBoolean("night", true)
                        ? ("야간 감광 " + Core.timeText(a.prefs.start()) + "~" + Core.timeText(a.prefs.end()))
                        : "야간 감광 꺼짐";
                summaryText.setText(
                    String.format(
                        Locale.KOREA,
                        "%d장의 사진 (%.1f MB) · %s · %s · %s",
                        n,
                        bytes / 1048576d,
                        modeName,
                        themeName,
                        nightSchedule));

                actionCard.removeAllViews();
                if (n == 0) {
                  // First-time user onboarding (Section 4.2 & 6.1)
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
                  // Returning user: one primary action to start frame
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
                  Ui.add(
                      actionCard,
                      "꾸미기 · 화면 구성과 문구 변경",
                      false,
                      R.drawable.ic_sun,
                      () -> switchTab("CUSTOMIZE"));
                }

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
              Ui.toast(this, "액자 꾸미기 설정을 적용했습니다.");
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
          List<Photo> active = a.db.photos().active();
          List<Quote> qs = a.db.photos().quotes();
          String samplePhrase =
              !a.prefs.phrase().isEmpty()
                  ? a.prefs.phrase()
                  : qs.isEmpty()
                      ? "POEM|산에는 꽃 피네\\n꽃이 피네\\n갈 봄 여름 없이\\n꽃이 피네|김소월 · 산유화"
                      : qs.get(0).text;
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
        "명언 · 시 모음 관리 (직접 추가·편집)",
        false,
        R.drawable.ic_quote,
        () -> Ui.open(this, QuotesActivity.class));

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
                  Ui.toast(this, finalOk + "장 사진을 담았습니다.");
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
