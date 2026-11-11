package co.kr.nuni.dam;

import android.app.*;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class SchedulesActivity extends Activity implements PhotoStore.Listener {
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
    private static final String[] DAYS_KOR = {"일", "월", "화", "수", "목", "금", "토"};
    private static final String[] RULE_TYPE_VALUES = {"WEEKLY", "DATE", "ANNIVERSARY"};
    private static final String[] RULE_TYPE_LABELS = {
      "요일별 반복 편성 (우선순위 3)",
      "특정 날짜 지정 편성 (우선순위 2)",
      "매년 생일·기념일 편성 (우선순위 2)"
    };
    private static final int[][] BANDS = {
      {0, 360},
      {360, 720},
      {720, 1080},
      {1080, 1440}
    };
    private static final String[] BAND_LABELS = {"00~06시", "06~12시", "12~18시", "18~24시"};

    private final Activity host;
    private final NuniApp a;
    private LinearLayout rootContainer;
    private LinearLayout editorContainer;
    private String selectedRuleId = "";
    private ScheduleRule draft;
    private List<Scene> scenes = new ArrayList<>();
    private List<ScheduleRule> rules = new ArrayList<>();
    private List<String> conflicts = new ArrayList<>();
    private Core.ScheduleEvaluation currentEval;

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
      final String reqRuleId = selectedRuleId;
      a.io.execute(
          () -> {
            a.studio.ensureSeeded();
            StudioDao sdao = a.studio.dao();
            List<Scene> allScenes = sdao.scenes();
            List<ScheduleRule> allRules = sdao.scheduleRules();
            List<Core.ScheduleSlot> slots = new ArrayList<>();
            for (ScheduleRule r : sdao.enabledScheduleRules()) {
              slots.add(Core.ScheduleSlot.from(r));
            }
            List<String> detectedConflicts = Core.detectScheduleConflicts(slots);
            Core.ScheduleEvaluation eval =
                a.studio.evaluateCurrentSchedule(System.currentTimeMillis());

            ScheduleRule matched = null;
            for (ScheduleRule r : allRules) {
              if (r.id.equals(reqRuleId)) {
                matched = r;
                break;
              }
            }
            if (matched == null) {
              for (ScheduleRule r : allRules) {
                if (!"TEMP".equals(r.ruleType)) {
                  matched = r;
                  break;
                }
              }
            }
            ScheduleRule finalMatched = matched;
            a.main.post(
                () -> {
                  if (!Ui.alive(host)) return;
                  scenes = allScenes;
                  rules = allRules;
                  conflicts = detectedConflicts;
                  currentEval = eval;
                  if (finalMatched != null
                      && (draft == null || !finalMatched.id.equals(selectedRuleId))) {
                    loadRuleIntoDraft(finalMatched);
                  } else if (draft == null) {
                    initNewRuleDraft();
                  }
                  renderFullUi();
                });
          });
    }

    private void loadRuleIntoDraft(ScheduleRule src) {
      selectedRuleId = src.id;
      draft = copyRule(src);
    }

    private void initNewRuleDraft() {
      selectedRuleId = "";
      draft = new ScheduleRule();
      draft.id = "";
      draft.name = "새 시간표 규칙";
      draft.ruleType = "WEEKLY";
      draft.specificDate = "";
      draft.daysMask = 62; // 평일(월~금)
      draft.startMin = 480; // 08:00
      draft.endMin = 720; // 12:00
      draft.priority = 3;
      draft.enabled = true;
      Scene def = findDefaultScene();
      draft.sceneId =
          !scenes.isEmpty() ? scenes.get(0).id : (def != null ? def.id : "scene_default");
    }

    private static ScheduleRule copyRule(ScheduleRule r) {
      ScheduleRule c = new ScheduleRule();
      c.id = r.id;
      c.name = r.name;
      c.ruleType = r.ruleType;
      c.specificDate = r.specificDate;
      c.sceneId = r.sceneId;
      c.daysMask = r.daysMask;
      c.startMin = r.startMin;
      c.endMin = r.endMin;
      c.priority = r.priority;
      c.enabled = r.enabled;
      c.expiresAt = r.expiresAt;
      c.createdAt = r.createdAt;
      c.updatedAt = r.updatedAt;
      return c;
    }

    private Scene findDefaultScene() {
      for (Scene s : scenes) {
        if ("scene_default".equals(s.id) || s.isDefault) return s;
      }
      return scenes.isEmpty() ? null : scenes.get(0);
    }

    private String sceneNameOf(String sceneId) {
      for (Scene s : scenes) {
        if (s.id.equals(sceneId)) return s.name;
      }
      Scene def = findDefaultScene();
      return def != null ? def.name : "기본 장면";
    }

    private void renderFullUi() {
      if (rootContainer == null || !Ui.alive(host)) return;
      rootContainer.removeAllViews();
      int widthDp = host.getResources().getConfiguration().screenWidthDp;
      boolean twoPane = widthDp >= 840;

      Ui.header(
          host,
          rootContainer,
          "요일별 · 시간대별 주간 편성표",
          "미지정 시간대에는 기본 장면이 상시 재생되며, 지정한 요일·시간대에만 해당 장면으로 전환됩니다.");
      Ui.gap(rootContainer, 12);

      if (twoPane) {
        LinearLayout split = Ui.row(host);
        split.setGravity(Gravity.TOP);
        rootContainer.addView(split, new LinearLayout.LayoutParams(-1, 0, 1));

        ScrollView leftScroll = new ScrollView(host);
        editorContainer = Ui.column(host);
        leftScroll.addView(editorContainer);
        LinearLayout.LayoutParams lpLeft = new LinearLayout.LayoutParams(0, -1, 4);
        lpLeft.setMarginEnd(Ui.dp(host, 16));
        split.addView(leftScroll, lpLeft);

        buildStatusAndTempCard(editorContainer);
        buildRuleEditorCard(editorContainer, null);

        ScrollView rightScroll = new ScrollView(host);
        LinearLayout rightCol = Ui.column(host);
        rightScroll.addView(rightCol);
        split.addView(rightScroll, new LinearLayout.LayoutParams(0, -1, 6));

        buildConflictBanner(rightCol);
        buildRulesListCard(rightCol, true);
        buildWeeklyTimetableCard(rightCol, true);
      } else {
        editorContainer = null;
        ScrollView scroll = new ScrollView(host);
        LinearLayout col = Ui.column(host);
        scroll.addView(col);
        rootContainer.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        buildConflictBanner(col);
        buildStatusAndTempCard(col);
        buildRulesListCard(col, false);
        buildWeeklyTimetableCard(col, false);
      }
    }

    private void buildConflictBanner(LinearLayout parent) {
      if (conflicts == null || conflicts.isEmpty()) return;
      LinearLayout alert = Ui.column(host);
      alert.setPadding(Ui.dp(host, 16), Ui.dp(host, 12), Ui.dp(host, 16), Ui.dp(host, 12));
      alert.setBackground(
          Ui.strokeBg(Ui.DANGER_BG, Ui.DANGER, Ui.dp(host, 1), Ui.dp(host, 14)));
      TextView tv =
          Ui.text(
              host,
              "⚠ 시간표 겹침 알림\n" + android.text.TextUtils.join("\n", conflicts),
              13.5f,
              Ui.DANGER);
      tv.setTypeface(Typeface.DEFAULT_BOLD);
      alert.addView(tv);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
      lp.bottomMargin = Ui.dp(host, 14);
      parent.addView(alert, lp);
    }

    private void buildStatusAndTempCard(LinearLayout parent) {
      LinearLayout card = Ui.card(parent);
      Ui.section(card, R.drawable.ic_play, "현재 재생 편성 상태 · 임시 재생");

      if (currentEval != null) {
        String activeSceneName = sceneNameOf(currentEval.activeSceneId);
        String nextSceneName = sceneNameOf(currentEval.nextSceneId);
        String nextDesc =
            currentEval.minutesUntilNext > 0
                ? String.format(
                    Locale.KOREA,
                    "다음 전환: %s (%d시간 %d분 후 · %s)",
                    nextSceneName,
                    currentEval.minutesUntilNext / 60,
                    currentEval.minutesUntilNext % 60,
                    currentEval.nextRuleName)
                : "다음 전환: 동일 장면 계속 유지";
        TextView curTv =
            Ui.text(
                host,
                "현재 재생 장면: " + activeSceneName + " (" + currentEval.activeRuleName + ")",
                14.5f,
                Ui.PRIMARY);
        curTv.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(curTv);
        card.addView(Ui.text(host, nextDesc, 13f, Ui.MUTED));
        Ui.gap(card, 8);
      }

      Switch schedEnableSw = new Switch(host);
      schedEnableSw.setText("주간 편성표 자동 장면 전환 사용");
      schedEnableSw.setTextColor(Ui.INK);
      schedEnableSw.setChecked(a.prefs.studioScheduleEnabled());
      schedEnableSw.setOnCheckedChangeListener(
          (v, on) -> {
            a.prefs.flag("studioScheduleEnabled", on);
            a.store.changed();
            Ui.toast(
                host,
                on
                    ? "주간 편성표에 따른 자동 장면 전환을 켰습니다."
                    : "자동 장면 전환을 껐습니다.");
          });
      card.addView(schedEnableSw);

      Ui.gap(card, 8);
      Ui.add(
          card,
          "임시 즉시 재생 (우선순위 1순위)",
          false,
          R.drawable.ic_clock,
          this::showTempOverrideDialog);
    }

    private void showTempOverrideDialog() {
      if (scenes.isEmpty()) return;
      LinearLayout box = Ui.column(host);
      box.setPadding(Ui.dp(host, 20), Ui.dp(host, 12), Ui.dp(host, 20), Ui.dp(host, 8));
      box.addView(
          Ui.text(
              host,
              "주간 시간표를 바꾸지 않고 지금 바로 특정 장면을 지정 시간 동안 최우선으로 재생합니다.",
              13.5f,
              Ui.MUTED));
      Ui.gap(box, 10);

      box.addView(Ui.text(host, "즉시 재생할 장면", 13.5f, Ui.MUTED));
      String[] sceneLabels = new String[scenes.size()];
      for (int i = 0; i < scenes.size(); i++) sceneLabels[i] = scenes.get(i).name;
      Spinner sceneSpin = new Spinner(host);
      sceneSpin.setAdapter(
          new ArrayAdapter<>(host, android.R.layout.simple_spinner_dropdown_item, sceneLabels));
      box.addView(sceneSpin, new LinearLayout.LayoutParams(-1, Ui.dp(host, 48)));

      Ui.gap(box, 8);
      box.addView(Ui.text(host, "유지 시간", 13.5f, Ui.MUTED));
      String[] durLabels = {
        "30분 동안", "1시간 동안", "3시간 동안", "임시 재생 해제 (원래 시간표로 복귀)"
      };
      int[] durMins = {30, 60, 180, 0};
      Spinner durSpin = new Spinner(host);
      durSpin.setAdapter(
          new ArrayAdapter<>(host, android.R.layout.simple_spinner_dropdown_item, durLabels));
      durSpin.setSelection(1);
      box.addView(durSpin, new LinearLayout.LayoutParams(-1, Ui.dp(host, 48)));

      new AlertDialog.Builder(host)
          .setTitle("임시 즉시 재생 (우선순위 1순위)")
          .setView(box)
          .setPositiveButton(
              "적용",
              (d, w) -> {
                int dur = durMins[Math.max(0, durSpin.getSelectedItemPosition())];
                String sid =
                    dur > 0
                        ? scenes.get(Math.max(0, sceneSpin.getSelectedItemPosition())).id
                        : "";
                a.io.execute(
                    () -> {
                      try {
                        a.studio.applyTempScheduleOverride(sid, dur);
                        a.main.post(
                            () -> {
                              Ui.toast(
                                  host,
                                  dur > 0
                                      ? "임시 즉시 재생을 시작했습니다."
                                      : "임시 재생을 해제하고 주간 시간표로 복귀했습니다.");
                              refresh();
                            });
                      } catch (Exception e) {
                        a.main.post(() -> Ui.error(host, e));
                      }
                    });
              })
          .setNegativeButton("취소", null)
          .show();
    }

    private void buildRulesListCard(LinearLayout parent, boolean twoPane) {
      LinearLayout card = Ui.card(parent);
      LinearLayout top = Ui.row(host);
      top.addView(
          Ui.title(host, "편성 규칙 목록 (" + rules.size() + "건)", 17),
          new LinearLayout.LayoutParams(0, -2, 1));
      Button addBtn =
          Ui.button(
              host,
              "새 편성 규칙",
              true,
              R.drawable.ic_plus,
              () -> {
                initNewRuleDraft();
                if (twoPane && editorContainer != null) {
                  renderFullUi();
                } else {
                  showRuleEditorDialog();
                }
              });
      addBtn.setTextSize(13f);
      top.addView(addBtn, new LinearLayout.LayoutParams(-2, Ui.dp(host, 40)));
      card.addView(top);
      Ui.gap(card, 8);

      Scene defScene = findDefaultScene();
      String defName = defScene != null ? defScene.name : "기본 장면";
      if (rules.isEmpty()) {
        TextView empty =
            Ui.text(
                host,
                "등록된 특정 시간대 일정이 없습니다. 모든 요일 · 전 시간대에 ‘★ " + defName + "’이 상시 재생됩니다.",
                14f,
                Ui.PRIMARY);
        empty.setPadding(Ui.dp(host, 8), Ui.dp(host, 12), Ui.dp(host, 8), Ui.dp(host, 12));
        card.addView(empty);
        return;
      }

      for (int i = 0; i < rules.size(); i++) {
        ScheduleRule r = rules.get(i);
        boolean sel = twoPane && r.id.equals(selectedRuleId);
        LinearLayout row = Ui.row(host);
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
              if ("TEMP".equals(r.ruleType)) {
                showTempOverrideDialog();
                return;
              }
              loadRuleIntoDraft(r);
              if (twoPane && editorContainer != null) {
                renderFullUi();
              } else {
                showRuleEditorDialog();
              }
            });

        LinearLayout info = Ui.column(host);
        TextView tvName =
            Ui.text(
                host,
                "#" + (i + 1) + "  " + r.name + "  →  " + sceneNameOf(r.sceneId),
                14.5f,
                r.enabled ? (sel ? Ui.PRIMARY : Ui.INK) : Ui.MUTED);
        tvName.setTypeface(Typeface.DEFAULT_BOLD);
        info.addView(tvName);

        String typeTag =
            "TEMP".equals(r.ruleType)
                ? "임시 재생(순위 1)"
                : "DATE".equals(r.ruleType)
                    ? ("특정일 " + r.specificDate + "(순위 2)")
                    : "ANNIVERSARY".equals(r.ruleType)
                        ? ("매년 " + r.specificDate + "(순위 2)")
                        : (Core.formatDaysMask(r.daysMask) + "(순위 3)");
        String timeTag =
            "TEMP".equals(r.ruleType)
                ? "만료 시 자동 복귀"
                : (Core.timeText(r.startMin)
                    + "~"
                    + Core.timeText(r.endMin)
                    + (r.startMin > r.endMin ? " (익일)" : ""));
        info.addView(Ui.text(host, typeTag + " · " + timeTag, 12.5f, Ui.MUTED));
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        Switch enSw = new Switch(host);
        enSw.setChecked(r.enabled);
        enSw.setOnCheckedChangeListener(
            (btn, on) -> {
              r.enabled = on;
              a.io.execute(
                  () -> {
                    try {
                      a.studio.saveScheduleRuleAndCheckConflicts(r);
                      a.main.post(this::refresh);
                    } catch (Exception e) {
                      a.main.post(() -> Ui.error(host, e));
                    }
                  });
            });
        row.addView(enSw, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        if (i < rules.size() - 1) rp.bottomMargin = Ui.dp(host, 6);
        card.addView(row, rp);
      }
    }

    private void buildWeeklyTimetableCard(LinearLayout parent, boolean twoPane) {
      LinearLayout card = Ui.card(parent);
      Scene defScene = findDefaultScene();
      String defName = defScene != null ? defScene.name : "기본 장면";

      Ui.section(card, R.drawable.ic_calendar, "주간 시간표 한눈에 보기");
      card.addView(
          Ui.text(
              host,
              "★ 미지정 시간대: ‘" + defName + "’ 상시 재생 · 아래 시간표 칸을 누르면 해당 규칙을 바로 편집합니다.",
              12.5f,
              Ui.MUTED));
      Ui.gap(card, 10);

      HorizontalScrollView hsv = new HorizontalScrollView(host);
      hsv.setFillViewport(true);
      TableLayout table = new TableLayout(host);
      table.setBackground(Ui.strokeBg(Ui.SURFACE, Ui.OUTLINE, Ui.dp(host, 1), Ui.dp(host, 12)));
      hsv.addView(table);

      // Header Row
      TableRow headRow = new TableRow(host);
      headRow.setBackgroundColor(Ui.SURFACE_SUBTLE);
      headRow.addView(makeGridHeaderCell("시간", 70));
      for (String d : DAYS_KOR) {
        headRow.addView(makeGridHeaderCell(d + "요일", 96));
      }
      table.addView(headRow);

      for (int bIdx = 0; bIdx < BANDS.length; bIdx++) {
        int bandStart = BANDS[bIdx][0];
        int bandEnd = BANDS[bIdx][1];
        TableRow tr = new TableRow(host);
        tr.addView(makeGridHeaderCell(BAND_LABELS[bIdx], 70));
        for (int d = 0; d < 7; d++) {
          LinearLayout cell = Ui.column(host);
          cell.setPadding(Ui.dp(host, 6), Ui.dp(host, 6), Ui.dp(host, 6), Ui.dp(host, 6));
          cell.setBackground(Ui.strokeBg(Ui.SURFACE, Ui.OUTLINE, 1, 0));
          cell.setMinimumWidth(Ui.dp(host, 96));
          cell.setMinimumHeight(Ui.dp(host, 64));

          int coveredMin = 0;
          for (ScheduleRule r : rules) {
            if (!r.enabled || !"WEEKLY".equals(r.ruleType)) continue;
            Core.ScheduleSlot slot = Core.ScheduleSlot.from(r);
            int cMin = Core.weeklyBandCoveredMinutes(slot, d, bandStart, bandEnd);
            if (cMin > 0) {
              coveredMin += cMin;
              LinearLayout pill = Ui.column(host);
              pill.setPadding(Ui.dp(host, 6), Ui.dp(host, 4), Ui.dp(host, 6), Ui.dp(host, 4));
              pill.setBackground(
                  Ui.strokeBg(Ui.SURFACE_SUBTLE, Ui.PRIMARY, Ui.dp(host, 1), Ui.dp(host, 6)));
              pill.setClickable(true);
              pill.setOnClickListener(
                  v -> {
                    loadRuleIntoDraft(r);
                    if (twoPane && editorContainer != null) {
                      renderFullUi();
                    } else {
                      showRuleEditorDialog();
                    }
                  });
              TextView pTitle = Ui.text(host, sceneNameOf(r.sceneId), 11.5f, Ui.PRIMARY);
              pTitle.setTypeface(Typeface.DEFAULT_BOLD);
              pill.addView(pTitle);
              pill.addView(
                  Ui.text(
                      host,
                      Core.timeText(r.startMin) + "~" + Core.timeText(r.endMin),
                      10.5f,
                      Ui.INK));
              LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
              plp.bottomMargin = Ui.dp(host, 4);
              cell.addView(pill, plp);
            }
          }
          if (coveredMin < (bandEnd - bandStart)) {
            TextView defTv =
                Ui.text(
                    host,
                    "★ " + defName + (coveredMin == 0 ? "\n(전 시간)" : "\n(나머지)"),
                    10.5f,
                    Ui.MUTED);
            defTv.setPadding(Ui.dp(host, 4), Ui.dp(host, 2), Ui.dp(host, 4), Ui.dp(host, 2));
            cell.addView(defTv);
          }
          tr.addView(cell);
        }
        table.addView(tr);
      }
      card.addView(hsv);
    }

    private TextView makeGridHeaderCell(String label, int minWidthDp) {
      TextView tv = Ui.text(host, label, 12f, Ui.INK);
      tv.setTypeface(Typeface.DEFAULT_BOLD);
      tv.setGravity(Gravity.CENTER);
      tv.setMinimumWidth(Ui.dp(host, minWidthDp));
      tv.setPadding(Ui.dp(host, 8), Ui.dp(host, 8), Ui.dp(host, 8), Ui.dp(host, 8));
      return tv;
    }

    private void showRuleEditorDialog() {
      ScrollView sv = new ScrollView(host);
      LinearLayout box = Ui.column(host);
      box.setPadding(Ui.dp(host, 16), Ui.dp(host, 12), Ui.dp(host, 16), Ui.dp(host, 8));
      sv.addView(box);
      AlertDialog dlg =
          new AlertDialog.Builder(host)
              .setView(sv)
              .setNegativeButton("닫기", null)
              .create();
      buildRuleEditorCard(box, dlg::dismiss);
      dlg.show();
    }

    private void buildRuleEditorCard(LinearLayout parent, Runnable onDone) {
      if (draft == null) initNewRuleDraft();
      boolean isNew = selectedRuleId.isEmpty();
      LinearLayout card = Ui.card(parent);
      Ui.section(
          card,
          R.drawable.ic_clock,
          isNew ? "새 편성 규칙 추가" : ("편성 규칙 상세 · " + draft.name));

      card.addView(Ui.text(host, "일정 이름", 13.5f, Ui.MUTED));
      EditText nameIn = Ui.input(host, "예: 평일 좋은 아침, 저녁 휴식", draft.name);
      nameIn.setSingleLine();
      card.addView(nameIn);

      Ui.gap(card, 8);
      card.addView(Ui.text(host, "편성 종류 (우선순위 자동 부여)", 13.5f, Ui.MUTED));
      Spinner typeSpin = new Spinner(host);
      typeSpin.setAdapter(
          new ArrayAdapter<>(host, android.R.layout.simple_spinner_dropdown_item, RULE_TYPE_LABELS));
      int typeIdx = 0;
      for (int i = 0; i < RULE_TYPE_VALUES.length; i++) {
        if (RULE_TYPE_VALUES[i].equals(draft.ruleType)) typeIdx = i;
      }
      typeSpin.setSelection(typeIdx);
      card.addView(typeSpin, new LinearLayout.LayoutParams(-1, Ui.dp(host, 48)));

      Ui.gap(card, 8);
      card.addView(Ui.text(host, "재생할 장면(Scene)", 13.5f, Ui.MUTED));
      List<String> sceneLabels = new ArrayList<>();
      int sceneIdx = 0;
      for (int i = 0; i < scenes.size(); i++) {
        Scene sc = scenes.get(i);
        boolean isDef = "scene_default".equals(sc.id) || sc.isDefault;
        sceneLabels.add(sc.name + (isDef ? " (★ 기본 장면)" : ""));
        if (sc.id.equals(draft.sceneId)) sceneIdx = i;
      }
      Spinner sceneSpin = new Spinner(host);
      sceneSpin.setAdapter(
          new ArrayAdapter<>(host, android.R.layout.simple_spinner_dropdown_item, sceneLabels));
      if (!sceneLabels.isEmpty()) sceneSpin.setSelection(sceneIdx);
      card.addView(sceneSpin, new LinearLayout.LayoutParams(-1, Ui.dp(host, 48)));

      LinearLayout dateWrap = Ui.column(host);
      Ui.gap(dateWrap, 8);
      TextView dateLabel = Ui.text(host, "날짜 (YYYY-MM-DD 또는 MM-DD)", 13.5f, Ui.MUTED);
      dateWrap.addView(dateLabel);
      LinearLayout dateRow = Ui.row(host);
      EditText dateIn = Ui.input(host, "예: 2026-12-25 또는 05-08", draft.specificDate);
      dateIn.setSingleLine();
      LinearLayout.LayoutParams dip = new LinearLayout.LayoutParams(0, -2, 1);
      dip.setMarginEnd(Ui.dp(host, 6));
      dateRow.addView(dateIn, dip);
      Button pickDateBtn =
          Ui.button(
              host,
              "달력 선택",
              false,
              () -> {
                Calendar cal = Calendar.getInstance();
                new DatePickerDialog(
                        host,
                        (view, year, month, dayOfMonth) -> {
                          String selType =
                              RULE_TYPE_VALUES[Math.max(0, typeSpin.getSelectedItemPosition())];
                          if ("ANNIVERSARY".equals(selType)) {
                            dateIn.setText(
                                String.format(Locale.ROOT, "%02d-%02d", month + 1, dayOfMonth));
                          } else {
                            dateIn.setText(
                                String.format(
                                    Locale.ROOT, "%04d-%02d-%02d", year, month + 1, dayOfMonth));
                          }
                        },
                        cal.get(Calendar.YEAR),
                        cal.get(Calendar.MONTH),
                        cal.get(Calendar.DAY_OF_MONTH))
                    .show();
              });
      pickDateBtn.setTextSize(12.5f);
      dateRow.addView(pickDateBtn, new LinearLayout.LayoutParams(-2, Ui.dp(host, 46)));
      dateWrap.addView(dateRow);
      card.addView(dateWrap);

      LinearLayout daysWrap = Ui.column(host);
      Ui.gap(daysWrap, 10);
      daysWrap.addView(Ui.text(host, "적용 요일 선택", 13.5f, Ui.MUTED));
      LinearLayout presetRow = Ui.row(host);
      CheckBox[] dayBoxes = new CheckBox[7];
      Runnable[] updateDayChecks = new Runnable[1];
      addDayPresetBtn(
          presetRow,
          "매일",
          () -> {
            draft.daysMask = 127;
            updateDayChecks[0].run();
          });
      addDayPresetBtn(
          presetRow,
          "평일(월~금)",
          () -> {
            draft.daysMask = 62;
            updateDayChecks[0].run();
          });
      addDayPresetBtn(
          presetRow,
          "주말(토·일)",
          () -> {
            draft.daysMask = 65;
            updateDayChecks[0].run();
          });
      daysWrap.addView(presetRow);
      Ui.gap(daysWrap, 6);

      HorizontalScrollView dayHsv = new HorizontalScrollView(host);
      dayHsv.setHorizontalScrollBarEnabled(false);
      LinearLayout dayRow = Ui.row(host);
      dayHsv.addView(dayRow);
      for (int i = 0; i < 7; i++) {
        int bit = 1 << i;
        CheckBox cb = new CheckBox(host);
        cb.setText(DAYS_KOR[i]);
        cb.setTextColor(Ui.INK);
        cb.setChecked((draft.daysMask & bit) != 0);
        cb.setOnCheckedChangeListener(
            (b, on) -> {
              if (on) draft.daysMask |= bit;
              else draft.daysMask &= ~bit;
            });
        dayBoxes[i] = cb;
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.setMarginEnd(Ui.dp(host, 8));
        dayRow.addView(cb, clp);
      }
      updateDayChecks[0] =
          () -> {
            for (int i = 0; i < 7; i++) {
              dayBoxes[i].setChecked((draft.daysMask & (1 << i)) != 0);
            }
          };
      daysWrap.addView(dayHsv);
      card.addView(daysWrap);

      typeSpin.setOnItemSelectedListener(
          new AdapterView.OnItemSelectedListener() {
            @Override
            public void onNothingSelected(AdapterView<?> p) {}

            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
              draft.ruleType = RULE_TYPE_VALUES[pos];
              boolean weekly = "WEEKLY".equals(draft.ruleType);
              daysWrap.setVisibility(weekly ? View.VISIBLE : View.GONE);
              dateWrap.setVisibility(weekly ? View.GONE : View.VISIBLE);
              dateLabel.setText(
                  "ANNIVERSARY".equals(draft.ruleType)
                      ? "매년 반복 날짜 (MM-DD, 예: 05-08)"
                      : "특정 날짜 지정 (YYYY-MM-DD, 예: 2026-12-25)");
            }
          });
      boolean initWeekly = "WEEKLY".equals(draft.ruleType);
      daysWrap.setVisibility(initWeekly ? View.VISIBLE : View.GONE);
      dateWrap.setVisibility(initWeekly ? View.GONE : View.VISIBLE);

      Ui.gap(card, 10);
      card.addView(Ui.text(host, "시작 · 종료 시간 (종료가 시작보다 이르면 자정 통과 야간 일정으로 동작)", 13.5f, Ui.MUTED));
      LinearLayout timeRow = Ui.row(host);
      Button[] startBtnRef = new Button[1];
      Button[] endBtnRef = new Button[1];
      startBtnRef[0] =
          Ui.button(
              host,
              "시작 " + Core.timeText(draft.startMin),
              false,
              R.drawable.ic_clock,
              () ->
                  new TimePickerDialog(
                          host,
                          (tp, h, m) -> {
                            draft.startMin = h * 60 + m;
                            startBtnRef[0].setText("시작 " + Core.timeText(draft.startMin));
                          },
                          draft.startMin / 60,
                          draft.startMin % 60,
                          true)
                      .show());
      LinearLayout.LayoutParams tp1 = new LinearLayout.LayoutParams(0, Ui.dp(host, 46), 1);
      tp1.setMarginEnd(Ui.dp(host, 8));
      timeRow.addView(startBtnRef[0], tp1);

      endBtnRef[0] =
          Ui.button(
              host,
              "종료 " + Core.timeText(draft.endMin),
              false,
              R.drawable.ic_clock,
              () ->
                  new TimePickerDialog(
                          host,
                          (tp, h, m) -> {
                            draft.endMin = h * 60 + m;
                            endBtnRef[0].setText("종료 " + Core.timeText(draft.endMin));
                          },
                          draft.endMin / 60,
                          draft.endMin % 60,
                          true)
                      .show());
      timeRow.addView(endBtnRef[0], new LinearLayout.LayoutParams(0, Ui.dp(host, 46), 1));
      card.addView(timeRow);

      Ui.gap(card, 10);
      Switch enabledSw = new Switch(host);
      enabledSw.setText("이 편성 규칙 활성화");
      enabledSw.setTextColor(Ui.INK);
      enabledSw.setChecked(draft.enabled);
      card.addView(enabledSw);

      Ui.gap(card, 12);
      Button saveBtn =
          Ui.button(
              host,
              isNew ? "새 편성 규칙 저장 및 적용" : "편성 규칙 저장 및 적용",
              true,
              R.drawable.ic_check,
              () -> {
                draft.name = nameIn.getText().toString().trim();
                draft.ruleType =
                    RULE_TYPE_VALUES[Math.max(0, typeSpin.getSelectedItemPosition())];
                if (!scenes.isEmpty()) {
                  draft.sceneId =
                      scenes.get(Math.max(0, sceneSpin.getSelectedItemPosition())).id;
                }
                draft.specificDate = dateIn.getText().toString().trim();
                draft.enabled = enabledSw.isChecked();
                ScheduleRule toSave = copyRule(draft);
                a.io.execute(
                    () -> {
                      try {
                        List<String> foundConflicts =
                            a.studio.saveScheduleRuleAndCheckConflicts(toSave);
                        selectedRuleId = toSave.id;
                        draft = null;
                        a.main.post(
                            () -> {
                              if (!foundConflicts.isEmpty()) {
                                Ui.toast(
                                    host,
                                    "저장되었으나 시간표 겹침이 감지되었습니다: " + foundConflicts.get(0));
                              } else {
                                Ui.toast(host, "편성 규칙을 저장하고 액자에 반영했습니다.");
                              }
                              if (onDone != null) onDone.run();
                              refresh();
                            });
                      } catch (Exception e) {
                        a.main.post(() -> Ui.error(host, e));
                      }
                    });
              });
      card.addView(saveBtn, new LinearLayout.LayoutParams(-1, Ui.dp(host, 50)));

      if (!isNew) {
        Ui.gap(card, 8);
        LinearLayout subRow = Ui.row(host);
        Button cloneBtn =
            Ui.button(
                host,
                "규칙 복제",
                false,
                () ->
                    a.io.execute(
                        () -> {
                          try {
                            ScheduleRule cloned = a.studio.cloneScheduleRule(selectedRuleId);
                            if (cloned != null) selectedRuleId = cloned.id;
                            draft = null;
                            a.main.post(
                                () -> {
                                  Ui.toast(host, "편성 규칙을 복제했습니다.");
                                  if (onDone != null) onDone.run();
                                  refresh();
                                });
                          } catch (Exception e) {
                            a.main.post(() -> Ui.error(host, e));
                          }
                        }));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, Ui.dp(host, 46), 1);
        cp.setMarginEnd(Ui.dp(host, 8));
        subRow.addView(cloneBtn, cp);

        Button delBtn =
            Ui.dangerButton(
                host,
                "규칙 삭제",
                R.drawable.ic_trash,
                () ->
                    new AlertDialog.Builder(host)
                        .setTitle("‘" + draft.name + "’ 규칙을 삭제할까요?")
                        .setPositiveButton(
                            "삭제",
                            (d, w) ->
                                a.io.execute(
                                    () -> {
                                      try {
                                        a.studio.deleteScheduleRuleAndPublish(selectedRuleId);
                                        selectedRuleId = "";
                                        draft = null;
                                        a.main.post(
                                            () -> {
                                              Ui.toast(host, "편성 규칙을 삭제했습니다.");
                                              if (onDone != null) onDone.run();
                                              refresh();
                                            });
                                      } catch (Exception e) {
                                        a.main.post(() -> Ui.error(host, e));
                                      }
                                    }))
                        .setNegativeButton("취소", null)
                        .show());
        subRow.addView(delBtn, new LinearLayout.LayoutParams(0, Ui.dp(host, 46), 1));
        card.addView(subRow);
      }
    }

    private void addDayPresetBtn(LinearLayout row, String label, Runnable action) {
      Button b = Ui.button(host, label, false, action);
      b.setTextSize(12f);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(host, 36));
      lp.setMarginEnd(Ui.dp(host, 6));
      row.addView(b, lp);
    }
  }
}
