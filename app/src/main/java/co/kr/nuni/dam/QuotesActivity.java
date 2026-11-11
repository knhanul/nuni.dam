package co.kr.nuni.dam;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class QuotesActivity extends Activity implements PhotoStore.Listener {
  public static final int REQ_IMPORT_CSV = 21;
  public static final int REQ_EXPORT_CSV = 22;
  public static final int REQ_EXPORT_TEMPLATE_CSV = 23;

  private NuniApp a;
  private boolean alive;
  private Controller controller;

  @Override
  public void onCreate(Bundle s) {
    super.onCreate(s);
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

  @Override
  protected void onActivityResult(int req, int result, Intent data) {
    super.onActivityResult(req, result, data);
    if (controller != null) controller.onActivityResult(req, result, data);
  }

  public static Controller buildView(LinearLayout container, Activity host) {
    Controller c = new Controller(host);
    c.attach(container);
    return c;
  }

  public static final class Controller {
    private final Activity host;
    private final NuniApp a;
    private LinearLayout rootContainer;
    private LinearLayout tabsRow, editorPane;
    private TextView headerTitle;
    private String filter = "ALL";
    private String searchQuery = "";
    private String selectedTextId = "";
    private List<TextItem> allItems = new ArrayList<>();
    private List<TextItem> filteredRows = new ArrayList<>();
    private final Map<String, Integer> catCounts = new LinkedHashMap<>();
    private ListAdapter adapter;

    public Controller(Activity h) {
      host = h;
      a = NuniApp.of(h);
    }

    public void attach(LinearLayout parent) {
      rootContainer = parent;
      buildLayout();
      refresh();
    }

    public void onActivityResult(int req, int result, Intent data) {
      if (result != RESULT_OK || data == null || data.getData() == null) return;
      Uri uri = data.getData();
      if (req == REQ_IMPORT_CSV) {
        a.io.execute(
            () -> {
              try (InputStream in = host.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException("파일을 열 수 없습니다.");
                byte[] bytes = Core.read(in, 4 * 1024 * 1024);
                String decoded = Core.decodeCsvBytes(bytes);
                List<Core.ImportedTextRow> parsed = Core.parseSpreadsheetTextRows(decoded);
                int[] counts = a.studio.importTextRows(parsed, false);
                a.main.post(
                    () -> {
                      Ui.toast(
                          host,
                          String.format(
                              Locale.KOREA,
                              "CSV 불러오기 완료 · 추가 %d편 (중복 건너뜀 %d편)",
                              counts[0],
                              counts[1]));
                      refresh();
                    });
              } catch (Exception e) {
                a.main.post(() -> Ui.error(host, e));
              }
            });
      } else if (req == REQ_EXPORT_CSV || req == REQ_EXPORT_TEMPLATE_CSV) {
        boolean templateOnly = req == REQ_EXPORT_TEMPLATE_CSV;
        a.io.execute(
            () -> {
              try (OutputStream out = host.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("저장할 파일을 열 수 없습니다.");
                List<String[]> rows = new ArrayList<>();
                rows.add(new String[] {"카테고리", "본문", "작가", "제목", "출처", "표시시간"});
                if (templateOnly) {
                  rows.add(
                      new String[] {
                        "인생문장",
                        "서두르지 않아도 괜찮습니다. 천천히 걷는 사람만이 길가의 들꽃을 기억합니다.",
                        "누니담",
                        "천천히 가는 길",
                        "",
                        "15"
                      });
                  rows.add(
                      new String[] {
                        "시 한 구절",
                        "봄에는 여린 초록이 창을 두드리고\n여름에는 푸른 그늘이 마루에 내려앉습니다.",
                        "누니담 문학 노트",
                        "사계절의 창",
                        "",
                        "20"
                      });
                  rows.add(
                      new String[] {
                        "", "카테고리를 비워두면 자동으로 기타로 분류됩니다.", "누니담", "", "", "15"
                      });
                } else {
                  for (TextItem t : a.studio.dao().textItems()) {
                    rows.add(
                        new String[] {
                          t.category,
                          t.body,
                          t.author,
                          t.title,
                          t.source,
                          String.valueOf(t.displaySeconds)
                        });
                  }
                }
                byte[] csvBytes = Core.formatCsvRecords(rows).getBytes(StandardCharsets.UTF_8);
                out.write(csvBytes);
                out.flush();
                a.main.post(
                    () ->
                        Ui.toast(
                            host,
                            templateOnly
                                ? "CSV 양식 파일을 저장했습니다."
                                : "글귀 목록을 CSV 파일로 내보냈습니다."));
              } catch (Exception e) {
                a.main.post(() -> Ui.error(host, e));
              }
            });
      }
    }

    private void buildLayout() {
      if (rootContainer == null || !Ui.alive(host)) return;
      rootContainer.removeAllViews();
      int widthDp = host.getResources().getConfiguration().screenWidthDp;
      boolean twoPane = widthDp >= 840;

      headerTitle =
          Ui.header(
              host,
              rootContainer,
              "글귀 라이브러리",
              "카테고리별 명언·시를 편집하고 CSV 불러오기·내보내기·붙여넣기로 일괄 관리합니다.");
      Ui.gap(rootContainer, 12);

      if (twoPane) {
        LinearLayout split = Ui.row(host);
        split.setGravity(Gravity.TOP);
        rootContainer.addView(split, new LinearLayout.LayoutParams(-1, 0, 1));

        ScrollView leftScroll = new ScrollView(host);
        editorPane = Ui.column(host);
        leftScroll.addView(editorPane);
        LinearLayout.LayoutParams lpLeft = new LinearLayout.LayoutParams(0, -1, 4);
        lpLeft.setMarginEnd(Ui.dp(host, 16));
        split.addView(leftScroll, lpLeft);

        LinearLayout rightCol = Ui.column(host);
        split.addView(rightCol, new LinearLayout.LayoutParams(0, -1, 6));
        buildListPane(rightCol, true);
      } else {
        editorPane = null;
        LinearLayout singleCol = Ui.column(host);
        rootContainer.addView(singleCol, new LinearLayout.LayoutParams(-1, 0, 1));
        buildListPane(singleCol, false);
      }
    }

    private void buildListPane(LinearLayout col, boolean twoPane) {
      LinearLayout topActions = Ui.row(host);
      Button newBtn =
          Ui.button(
              host,
              "새 글귀 작성",
              true,
              R.drawable.ic_plus,
              () -> {
                selectedTextId = "";
                if (twoPane && editorPane != null) {
                  renderEditorPane(editorPane, null, null);
                  if (adapter != null) adapter.notifyDataSetChanged();
                } else {
                  showEditorDialog(null);
                }
              });
      newBtn.setTextSize(13.5f);
      LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, Ui.dp(host, 46), 1);
      np.setMarginEnd(Ui.dp(host, 6));
      topActions.addView(newBtn, np);

      Button catBtn =
          Ui.button(
              host,
              "카테고리 관리",
              false,
              R.drawable.ic_tune,
              this::showCategoryManagerDialog);
      catBtn.setTextSize(13.5f);
      LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, Ui.dp(host, 46));
      cp.setMarginEnd(Ui.dp(host, 6));
      topActions.addView(catBtn, cp);

      Button bulkBtn =
          Ui.button(
              host,
              "일괄 · CSV",
              false,
              R.drawable.ic_upload,
              this::showBulkActionsDialog);
      bulkBtn.setTextSize(13.5f);
      topActions.addView(bulkBtn, new LinearLayout.LayoutParams(-2, Ui.dp(host, 46)));
      col.addView(topActions);
      Ui.gap(col, 8);

      HorizontalScrollView tabScroll = new HorizontalScrollView(host);
      tabScroll.setHorizontalScrollBarEnabled(false);
      tabsRow = Ui.row(host);
      tabScroll.addView(tabsRow);
      col.addView(tabScroll, new LinearLayout.LayoutParams(-1, -2));
      Ui.gap(col, 6);

      EditText searchIn = Ui.input(host, "본문, 제목, 작가, 카테고리 검색…", searchQuery);
      searchIn.setSingleLine();
      searchIn.addTextChangedListener(
          new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
              searchQuery = s == null ? "" : s.toString().trim();
              applyFilterAndRender(false);
            }
          });
      col.addView(searchIn);
      Ui.gap(col, 8);

      ListView list = new ListView(host);
      adapter = new ListAdapter();
      list.setAdapter(adapter);
      list.setDivider(null);
      list.setDividerHeight(Ui.dp(host, 8));
      list.setSelector(android.R.color.transparent);
      list.setOnItemClickListener(
          (p, v, pos, id) -> {
            if (pos < 0 || pos >= filteredRows.size()) return;
            TextItem item = filteredRows.get(pos);
            selectedTextId = item.id;
            if (twoPane && editorPane != null) {
              renderEditorPane(editorPane, item, null);
              adapter.notifyDataSetChanged();
            } else {
              showEditorDialog(item);
            }
          });
      col.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
      TextView empty =
          Ui.text(
              host,
              "표시할 글귀가 없습니다. 새 글귀를 작성하거나 ‘일괄 · CSV’ 메뉴에서 샘플 복원 또는 CSV 불러오기를 이용해 보세요.",
              14.5f,
              Ui.MUTED);
      empty.setPadding(Ui.dp(host, 8), Ui.dp(host, 20), Ui.dp(host, 8), Ui.dp(host, 20));
      col.addView(empty);
      list.setEmptyView(empty);
    }

    public void refresh() {
      if (!Ui.alive(host)) return;
      a.io.execute(
          () -> {
            a.studio.ensureSeeded();
            List<TextItem> loaded = a.studio.dao().textItems();
            List<String> managedCats = a.prefs.textCategories();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String c : managedCats) {
              if (!"기타".equals(c)) counts.put(c, 0);
            }
            int etcCount = 0;
            List<TextItem> valid = new ArrayList<>();
            for (TextItem item : loaded) {
              if (item.body == null || item.body.trim().isEmpty()) continue;
              valid.add(item);
              String cat =
                  (item.category == null || item.category.trim().isEmpty())
                      ? "기타"
                      : item.category.trim();
              if ("기타".equals(cat)) {
                etcCount++;
              } else {
                counts.put(cat, (counts.containsKey(cat) ? counts.get(cat) : 0) + 1);
              }
            }
            counts.put("기타", etcCount);
            a.main.post(
                () -> {
                  if (!Ui.alive(host)) return;
                  allItems = valid;
                  catCounts.clear();
                  catCounts.putAll(counts);
                  if (!"ALL".equals(filter) && !catCounts.containsKey(filter)) {
                    filter = "ALL";
                  }
                  rebuildCategoryTabs();
                  applyFilterAndRender(true);
                });
          });
    }

    private void rebuildCategoryTabs() {
      if (tabsRow == null) return;
      tabsRow.removeAllViews();
      addFilterChip("전체 (" + allItems.size() + ")", "ALL");
      for (Map.Entry<String, Integer> e : catCounts.entrySet()) {
        addFilterChip(e.getKey() + " (" + e.getValue() + ")", e.getKey());
      }
    }

    private void addFilterChip(String label, String key) {
      boolean sel = key.equals(filter);
      Button b =
          Ui.button(
              host,
              label,
              sel,
              () -> {
                filter = key;
                rebuildCategoryTabs();
                applyFilterAndRender(false);
              });
      b.setTextSize(13f);
      b.setPadding(Ui.dp(host, 12), 0, Ui.dp(host, 12), 0);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, Ui.dp(host, 38));
      p.setMargins(0, 0, Ui.dp(host, 6), 0);
      tabsRow.addView(b, p);
    }

    private void applyFilterAndRender(boolean updateEditorIfOpen) {
      String q = searchQuery == null ? "" : searchQuery.toLowerCase(Locale.ROOT);
      List<TextItem> out = new ArrayList<>();
      for (TextItem t : allItems) {
        String cat =
            (t.category == null || t.category.trim().isEmpty()) ? "기타" : t.category.trim();
        if (!"ALL".equals(filter) && !filter.equals(cat)) continue;
        if (!q.isEmpty()) {
          boolean match =
              t.body.toLowerCase(Locale.ROOT).contains(q)
                  || t.title.toLowerCase(Locale.ROOT).contains(q)
                  || t.author.toLowerCase(Locale.ROOT).contains(q)
                  || cat.toLowerCase(Locale.ROOT).contains(q);
          if (!match) continue;
        }
        out.add(t);
      }
      filteredRows = out;
      if (headerTitle != null) {
        headerTitle.setText(
            String.format(
                Locale.KOREA, "글귀 라이브러리 · 전체 %d편 중 %d편 표시", allItems.size(), filteredRows.size()));
      }
      if (editorPane != null && updateEditorIfOpen) {
        TextItem active = null;
        for (TextItem t : allItems) {
          if (t.id.equals(selectedTextId)) {
            active = t;
            break;
          }
        }
        if (active == null && !filteredRows.isEmpty()) {
          active = filteredRows.get(0);
          selectedTextId = active.id;
        }
        renderEditorPane(editorPane, active, null);
      }
      if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void showEditorDialog(TextItem existing) {
      ScrollView sv = new ScrollView(host);
      LinearLayout box = Ui.column(host);
      box.setPadding(Ui.dp(host, 16), Ui.dp(host, 12), Ui.dp(host, 16), Ui.dp(host, 8));
      sv.addView(box);
      AlertDialog dlg =
          new AlertDialog.Builder(host)
              .setView(sv)
              .setNegativeButton("닫기", null)
              .create();
      renderEditorPane(box, existing, dlg::dismiss);
      dlg.show();
    }

    private void renderEditorPane(LinearLayout parent, TextItem existing, Runnable onDone) {
      parent.removeAllViews();
      LinearLayout card = Ui.card(parent);
      Ui.section(
          card, R.drawable.ic_quote, existing == null ? "새 글귀 작성" : "선택한 글귀 상세 · 편집");

      List<String> cats = new ArrayList<>(catCounts.keySet());
      if (cats.isEmpty()) {
        cats.addAll(a.prefs.textCategories());
        if (!cats.contains("기타")) cats.add("기타");
      }
      if (existing != null
          && !existing.category.trim().isEmpty()
          && !cats.contains(existing.category.trim())) {
        cats.add(existing.category.trim());
      }

      LinearLayout catLabelRow = Ui.row(host);
      catLabelRow.addView(
          Ui.text(host, "카테고리 (분류)", 13.5f, Ui.MUTED), new LinearLayout.LayoutParams(0, -2, 1));
      Button manageCatLink =
          Ui.button(
              host,
              "카테고리 관리",
              false,
              () -> {
                if (onDone != null) onDone.run();
                showCategoryManagerDialog();
              });
      manageCatLink.setTextSize(12f);
      catLabelRow.addView(manageCatLink, new LinearLayout.LayoutParams(-2, Ui.dp(host, 34)));
      card.addView(catLabelRow);

      Spinner catSpinner = new Spinner(host);
      catSpinner.setAdapter(
          new ArrayAdapter<>(host, android.R.layout.simple_spinner_dropdown_item, cats));
      String initCat =
          existing != null && !existing.category.trim().isEmpty()
              ? existing.category.trim()
              : (!"ALL".equals(filter) ? filter : cats.get(0));
      catSpinner.setSelection(Math.max(0, cats.indexOf(initCat)));
      card.addView(catSpinner, new LinearLayout.LayoutParams(-1, Ui.dp(host, 48)));

      Ui.gap(card, 8);
      card.addView(Ui.text(host, "제목 (선택)", 13.5f, Ui.MUTED));
      EditText titleIn = Ui.input(host, "예: 서시, 오늘의 햇살", existing != null ? existing.title : "");
      titleIn.setSingleLine();
      card.addView(titleIn);

      Ui.gap(card, 8);
      card.addView(Ui.text(host, "작가 · 쓴 사람 (선택)", 13.5f, Ui.MUTED));
      EditText authorIn = Ui.input(host, "예: 윤동주, 누니담", existing != null ? existing.author : "");
      authorIn.setSingleLine();
      card.addView(authorIn);

      Ui.gap(card, 8);
      card.addView(Ui.text(host, "출처 · 메모 (선택)", 13.5f, Ui.MUTED));
      EditText sourceIn =
          Ui.input(host, "예: 하늘과 바람과 별과 시", existing != null ? existing.source : "");
      sourceIn.setSingleLine();
      card.addView(sourceIn);

      Ui.gap(card, 8);
      card.addView(Ui.text(host, "글귀 본문 (시는 줄바꿈 유지 · 긴 시 자동 페이지 분할)", 13.5f, Ui.MUTED));
      EditText bodyIn =
          Ui.input(
              host,
              "여기에 명언이나 시 본문을 입력해 주세요.",
              existing != null ? existing.body : "");
      bodyIn.setMinLines(4);
      card.addView(bodyIn);

      Ui.gap(card, 8);
      card.addView(Ui.text(host, "표시 시간(초, 5~600)", 13.5f, Ui.MUTED));
      EditText secIn =
          Ui.input(
              host, "15", String.valueOf(existing != null ? existing.displaySeconds : 15));
      secIn.setInputType(InputType.TYPE_CLASS_NUMBER);
      secIn.setSingleLine();
      card.addView(secIn);

      Ui.gap(card, 8);
      Switch enabledSw = new Switch(host);
      enabledSw.setText("액자 재생에 사용");
      enabledSw.setTextColor(Ui.INK);
      enabledSw.setChecked(existing == null || existing.enabled);
      card.addView(enabledSw);

      Ui.gap(card, 12);
      Button saveBtn =
          Ui.button(
              host,
              existing == null ? "새 글귀 저장" : "글귀 저장",
              true,
              R.drawable.ic_check,
              () -> {
                String body = bodyIn.getText().toString().trim();
                if (body.isEmpty()) {
                  bodyIn.setError("글귀 본문을 입력해 주세요.");
                  return;
                }
                String cat = String.valueOf(catSpinner.getSelectedItem()).trim();
                String title = titleIn.getText().toString().trim();
                String author = authorIn.getText().toString().trim();
                String source = sourceIn.getText().toString().trim();
                int sec = 15;
                try {
                  sec = Integer.parseInt(secIn.getText().toString().trim());
                } catch (NumberFormatException ignored) {
                }
                int finalSec = sec;
                boolean en = enabledSw.isChecked();
                String existingId = existing != null ? existing.id : "";
                a.io.execute(
                    () -> {
                      try {
                        TextItem saved =
                            a.studio.saveTextEntry(
                                existingId, cat, title, body, author, source, finalSec, en);
                        selectedTextId = saved.id;
                        a.main.post(
                            () -> {
                              Ui.toast(host, "글귀를 저장했습니다.");
                              if (onDone != null) onDone.run();
                              refresh();
                            });
                      } catch (Exception e) {
                        a.main.post(() -> Ui.error(host, e));
                      }
                    });
              });
      card.addView(saveBtn, new LinearLayout.LayoutParams(-1, Ui.dp(host, 50)));

      if (existing != null) {
        Ui.gap(card, 8);
        LinearLayout subRow = Ui.row(host);
        Button cloneBtn =
            Ui.button(
                host,
                "복제",
                false,
                () ->
                    a.io.execute(
                        () -> {
                          try {
                            TextItem copy = a.studio.cloneTextEntry(existing.id);
                            selectedTextId = copy.id;
                            a.main.post(
                                () -> {
                                  Ui.toast(host, "글귀를 복제했습니다.");
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
                "삭제",
                R.drawable.ic_trash,
                () ->
                    new AlertDialog.Builder(host)
                        .setTitle("이 글귀를 삭제할까요?")
                        .setPositiveButton(
                            "삭제",
                            (d, w) ->
                                a.io.execute(
                                    () -> {
                                      try {
                                        a.studio.dao().deleteTextItem(existing.id);
                                        a.studio.publishToTablet("앱에서 글귀 삭제");
                                        if (existing.id.equals(selectedTextId)) selectedTextId = "";
                                        a.main.post(
                                            () -> {
                                              Ui.toast(host, "글귀를 삭제했습니다.");
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

    private void showCategoryManagerDialog() {
      ScrollView sv = new ScrollView(host);
      LinearLayout box = Ui.column(host);
      box.setPadding(Ui.dp(host, 20), Ui.dp(host, 12), Ui.dp(host, 20), Ui.dp(host, 12));
      sv.addView(box);

      box.addView(
          Ui.text(
              host,
              "카테고리를 추가·이름 변경·삭제할 수 있습니다. 삭제된 카테고리의 글귀는 자동으로 ‘기타’로 이동됩니다.",
              13.5f,
              Ui.MUTED));
      Ui.gap(box, 10);

      LinearLayout addRow = Ui.row(host);
      EditText newCatIn = Ui.input(host, "새 카테고리 이름 입력", "");
      newCatIn.setSingleLine();
      LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1);
      ip.setMarginEnd(Ui.dp(host, 8));
      addRow.addView(newCatIn, ip);

      LinearLayout listBox = Ui.column(host);
      Runnable[] renderListRef = new Runnable[1];
      renderListRef[0] =
          () -> {
            listBox.removeAllViews();
            for (Map.Entry<String, Integer> e : catCounts.entrySet()) {
              String catName = e.getKey();
              int count = e.getValue();
              boolean isEtc = "기타".equals(catName);
              LinearLayout row = Ui.row(host);
              row.setPadding(Ui.dp(host, 10), Ui.dp(host, 8), Ui.dp(host, 10), Ui.dp(host, 8));
              row.setBackground(
                  Ui.strokeBg(Ui.SURFACE, Ui.OUTLINE, Ui.dp(host, 1), Ui.dp(host, 10)));
              TextView tv =
                  Ui.text(
                      host,
                      catName + " (" + count + "편)" + (isEtc ? " · 기본 미분류" : ""),
                      14.5f,
                      Ui.INK);
              tv.setTypeface(Typeface.DEFAULT_BOLD);
              row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1));
              if (!isEtc) {
                Button renBtn =
                    Ui.button(
                        host,
                        "이름 변경",
                        false,
                        () -> promptRenameCategory(catName, renderListRef[0]));
                renBtn.setTextSize(12f);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, Ui.dp(host, 36));
                rp.setMarginEnd(Ui.dp(host, 6));
                row.addView(renBtn, rp);

                Button delBtn =
                    Ui.dangerButton(
                        host,
                        "삭제",
                        0,
                        () ->
                            new AlertDialog.Builder(host)
                                .setTitle("‘" + catName + "’ 카테고리를 삭제할까요?")
                                .setMessage(
                                    count > 0
                                        ? ("이 카테고리에 속한 글귀 " + count + "편은 ‘기타’로 이동됩니다.")
                                        : "비어 있는 카테고리입니다.")
                                .setPositiveButton(
                                    "삭제",
                                    (d, w) ->
                                        a.io.execute(
                                            () -> {
                                              try {
                                                a.studio.deleteTextCategory(catName);
                                                if (catName.equals(filter)) filter = "ALL";
                                                a.main.post(
                                                    () -> {
                                                      refresh();
                                                      Ui.toast(
                                                          host, "‘" + catName + "’ 카테고리를 삭제했습니다.");
                                                      a.main.postDelayed(renderListRef[0], 120);
                                                    });
                                              } catch (Exception ex) {
                                                a.main.post(() -> Ui.error(host, ex));
                                              }
                                            }))
                                .setNegativeButton("취소", null)
                                .show());
                delBtn.setTextSize(12f);
                row.addView(delBtn, new LinearLayout.LayoutParams(-2, Ui.dp(host, 36)));
              }
              LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
              lp.bottomMargin = Ui.dp(host, 6);
              listBox.addView(row, lp);
            }
          };

      Button addBtn =
          Ui.button(
              host,
              "추가",
              true,
              R.drawable.ic_plus,
              () -> {
                String name = newCatIn.getText().toString().trim();
                if (name.isEmpty()) {
                  Ui.toast(host, "추가할 카테고리 이름을 입력해 주세요.");
                  return;
                }
                a.io.execute(
                    () -> {
                      try {
                        a.studio.addTextCategory(name);
                        a.main.post(
                            () -> {
                              newCatIn.setText("");
                              refresh();
                              Ui.toast(host, "‘" + name + "’ 카테고리를 추가했습니다.");
                              a.main.postDelayed(renderListRef[0], 120);
                            });
                      } catch (Exception ex) {
                        a.main.post(() -> Ui.error(host, ex));
                      }
                    });
              });
      addRow.addView(addBtn, new LinearLayout.LayoutParams(-2, Ui.dp(host, 46)));
      box.addView(addRow);
      Ui.gap(box, 12);
      box.addView(listBox);
      renderListRef[0].run();

      new AlertDialog.Builder(host)
          .setTitle("글귀 카테고리 관리")
          .setView(sv)
          .setPositiveButton("완료", null)
          .show();
    }

    private void promptRenameCategory(String oldName, Runnable afterDone) {
      LinearLayout box = Ui.column(host);
      box.setPadding(Ui.dp(host, 20), Ui.dp(host, 12), Ui.dp(host, 20), Ui.dp(host, 4));
      EditText in = Ui.input(host, "새 카테고리 이름", oldName);
      in.setSingleLine();
      box.addView(in);
      new AlertDialog.Builder(host)
          .setTitle("‘" + oldName + "’ 이름 변경")
          .setView(box)
          .setPositiveButton(
              "변경",
              (d, w) -> {
                String next = in.getText().toString().trim();
                if (next.isEmpty() || next.equals(oldName)) return;
                a.io.execute(
                    () -> {
                      try {
                        a.studio.renameTextCategory(oldName, next);
                        if (oldName.equals(filter)) filter = next;
                        a.main.post(
                            () -> {
                              refresh();
                              Ui.toast(host, "카테고리 이름을 ‘" + next + "’(으)로 변경했습니다.");
                              if (afterDone != null) a.main.postDelayed(afterDone, 120);
                            });
                      } catch (Exception ex) {
                        a.main.post(() -> Ui.error(host, ex));
                      }
                    });
              })
          .setNegativeButton("취소", null)
          .show();
    }

    private void showBulkActionsDialog() {
      String[] items = {
        "엑셀 · 텍스트 붙여넣기 일괄 등록",
        "CSV 파일 불러오기 (UTF-8 / EUC-KR 자동 인식)",
        "현재 글귀 목록 CSV 파일로 내보내기",
        "CSV 작성 양식 파일 저장",
        "기본 샘플 글귀 250편 복원 (추가한 글귀 유지)",
        "글귀 전체 비우기"
      };
      new AlertDialog.Builder(host)
          .setTitle("글귀 일괄 등록 · CSV 관리")
          .setItems(
              items,
              (d, which) -> {
                switch (which) {
                  case 0:
                    showPasteBulkDialog();
                    break;
                  case 1:
                    host.startActivityForResult(
                        new Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .setType("*/*")
                            .addCategory(Intent.CATEGORY_OPENABLE),
                        REQ_IMPORT_CSV);
                    break;
                  case 2:
                    host.startActivityForResult(
                        new Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .setType("text/csv")
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .putExtra(Intent.EXTRA_TITLE, "nunidam_texts_export.csv"),
                        REQ_EXPORT_CSV);
                    break;
                  case 3:
                    host.startActivityForResult(
                        new Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .setType("text/csv")
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .putExtra(Intent.EXTRA_TITLE, "nunidam_texts_template.csv"),
                        REQ_EXPORT_TEMPLATE_CSV);
                    break;
                  case 4:
                    confirmRestoreDefaults();
                    break;
                  case 5:
                    confirmClearAll();
                    break;
                }
              })
          .setNegativeButton("닫기", null)
          .show();
    }

    private void showPasteBulkDialog() {
      LinearLayout box = Ui.column(host);
      box.setPadding(Ui.dp(host, 20), Ui.dp(host, 12), Ui.dp(host, 20), Ui.dp(host, 8));
      box.addView(
          Ui.text(
              host,
              "엑셀·스프레드시트에서 복사한 표나 CSV 텍스트를 붙여넣으세요.\n열 순서: 카테고리 | 본문 | 작가 | 제목 | 출처 | 표시시간(초)\n(한 줄당 본문만 입력하면 자동으로 ‘기타’ 분류로 등록됩니다.)",
              13f,
              Ui.MUTED));
      Ui.gap(box, 8);
      EditText pasteIn =
          Ui.input(
              host,
              "예: 인생문장,천천히 걸어도 괜찮습니다.,누니담,산책,,15",
              "");
      pasteIn.setMinLines(6);
      box.addView(pasteIn);
      new AlertDialog.Builder(host)
          .setTitle("엑셀 · 텍스트 붙여넣기 일괄 등록")
          .setView(box)
          .setPositiveButton(
              "일괄 등록",
              (d, w) -> {
                String raw = pasteIn.getText().toString();
                a.io.execute(
                    () -> {
                      try {
                        List<Core.ImportedTextRow> rows = Core.parseSpreadsheetTextRows(raw);
                        int[] counts = a.studio.importTextRows(rows, false);
                        a.main.post(
                            () -> {
                              Ui.toast(
                                  host,
                                  String.format(
                                      Locale.KOREA,
                                      "일괄 등록 완료 · 추가 %d편 (중복 건너뜀 %d편)",
                                      counts[0],
                                      counts[1]));
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

    private void confirmRestoreDefaults() {
      new AlertDialog.Builder(host)
          .setTitle("기본 샘플 글귀 복원")
          .setMessage("초기 기본 글귀(nunidam_sample.csv, 250편)를 복원할까요?\n직접 추가한 글귀는 그대로 유지됩니다.")
          .setPositiveButton(
              "복원",
              (d, w) ->
                  a.io.execute(
                      () -> {
                        try {
                          int count = a.studio.seedSampleTextsFromCsv(false);
                          a.studio.publishToTablet("앱에서 기본 샘플 글귀 복원");
                          a.main.post(
                              () -> {
                                Ui.toast(host, "기본 샘플 글귀 " + count + "편을 복원했습니다.");
                                refresh();
                              });
                        } catch (Exception e) {
                          a.main.post(() -> Ui.error(host, e));
                        }
                      }))
          .setNegativeButton("취소", null)
          .show();
    }

    private void confirmClearAll() {
      if (allItems.isEmpty()) {
        Ui.toast(host, "삭제할 글귀가 없습니다.");
        return;
      }
      new AlertDialog.Builder(host)
          .setTitle("모든 글귀를 비울까요?")
          .setMessage("등록된 글귀 " + allItems.size() + "편이 모두 삭제됩니다. (필요 시 기본 샘플 복원으로 기본 250편은 다시 불러올 수 있습니다.)")
          .setPositiveButton(
              "전체 비우기",
              (d, w) ->
                  a.io.execute(
                      () -> {
                        try {
                          a.studio.clearAllTextItems();
                          selectedTextId = "";
                          a.main.post(
                              () -> {
                                Ui.toast(host, "모든 글귀를 비웠습니다.");
                                refresh();
                              });
                        } catch (Exception e) {
                          a.main.post(() -> Ui.error(host, e));
                        }
                      }))
          .setNegativeButton("취소", null)
          .show();
    }

    private final class ListAdapter extends BaseAdapter {
      @Override
      public int getCount() {
        return filteredRows.size();
      }

      @Override
      public Object getItem(int i) {
        return filteredRows.get(i);
      }

      @Override
      public long getItemId(int i) {
        return i;
      }

      @Override
      public View getView(int pos, View old, ViewGroup parent) {
        LinearLayout row = old instanceof LinearLayout ? (LinearLayout) old : Ui.column(host);
        row.removeAllViews();
        row.setPadding(Ui.dp(host, 16), Ui.dp(host, 14), Ui.dp(host, 16), Ui.dp(host, 14));
        TextItem q = filteredRows.get(pos);
        boolean sel = editorPane != null && q.id.equals(selectedTextId);
        row.setBackground(
            Ui.strokeBg(
                sel ? Ui.SURFACE_SUBTLE : Ui.SURFACE,
                sel ? Ui.PRIMARY : Ui.OUTLINE,
                Ui.dp(host, sel ? 2 : 1),
                Ui.dp(host, 16)));
        boolean isPoem = "POEM".equals(q.kind) || (q.body != null && q.body.indexOf('\n') >= 0);
        String cat =
            (q.category == null || q.category.trim().isEmpty()) ? "기타" : q.category.trim();

        LinearLayout topRow = Ui.row(host);
        TextView badge =
            Ui.text(
                host,
                "#"
                    + (pos + 1)
                    + "  "
                    + cat
                    + (!q.title.isEmpty() ? " · " + q.title : "")
                    + " · "
                    + q.displaySeconds
                    + "초",
                12.5f,
                q.enabled ? Ui.PRIMARY : Ui.MUTED);
        badge.setCompoundDrawablesRelative(
            Ui.icon(
                host,
                isPoem ? R.drawable.ic_heart : R.drawable.ic_quote,
                q.enabled ? Ui.PRIMARY : Ui.MUTED,
                15),
            null,
            null,
            null);
        badge.setCompoundDrawablePadding(Ui.dp(host, 6));
        badge.setGravity(Gravity.CENTER_VERTICAL);
        topRow.addView(badge, new LinearLayout.LayoutParams(0, -2, 1));

        CheckBox enableChk = new CheckBox(host);
        enableChk.setText("사용");
        enableChk.setTextSize(12.5f);
        enableChk.setTextColor(Ui.MUTED);
        enableChk.setFocusable(false);
        enableChk.setChecked(q.enabled);
        enableChk.setOnCheckedChangeListener(
            (btn, on) -> {
              q.enabled = on;
              a.io.execute(
                  () -> {
                    try {
                      a.studio.saveTextEntry(
                          q.id,
                          q.category,
                          q.title,
                          q.body,
                          q.author,
                          q.source,
                          q.displaySeconds,
                          on);
                    } catch (Exception ignored) {
                    }
                  });
            });
        topRow.addView(enableChk, new LinearLayout.LayoutParams(-2, -2));
        row.addView(topRow);

        Ui.gap(row, 6);
        TextView body = Ui.text(host, q.body, 15.5f, q.enabled ? Ui.INK : Ui.MUTED);
        body.setTypeface(Typeface.SERIF);
        body.setMaxLines(3);
        body.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(body);

        if (!q.author.isEmpty() || !q.source.isEmpty()) {
          Ui.gap(row, 4);
          String credit =
              !q.author.isEmpty() && !q.source.isEmpty()
                  ? ("— " + q.author + " (" + q.source + ")")
                  : (!q.author.isEmpty() ? ("— " + q.author) : q.source);
          TextView author = Ui.text(host, credit, 12.5f, Ui.MUTED);
          author.setTypeface(Typeface.SERIF, Typeface.ITALIC);
          row.addView(author);
        }
        return row;
      }
    }
  }
}
