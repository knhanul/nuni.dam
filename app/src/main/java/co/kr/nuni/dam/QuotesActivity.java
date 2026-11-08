package co.kr.nuni.dam;

import android.app.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.security.SecureRandom;
import java.util.*;

public final class QuotesActivity extends Activity implements PhotoStore.Listener {
  private NuniApp a;
  private boolean alive;
  private String filter = "ALL";
  private TextView title;
  private List<TextItem> rows = new ArrayList<>();
  private Adapter adapter;
  private LinearLayout tabsRow;
  private final Map<String, Button> tabs = new HashMap<>();

  public void onCreate(Bundle s) {
    super.onCreate(s);
    alive = true;
    a = NuniApp.of(this);
    LinearLayout root = Ui.column(this);
    root.setBackgroundColor(Ui.BG);
    int pad = Ui.dp(this, 20);
    root.setPadding(pad, pad, pad, Ui.dp(this, 12));
    setContentView(root);
    getWindow().setStatusBarColor(Ui.BG);
    getWindow().setNavigationBarColor(Ui.BG);
    root.setOnApplyWindowInsetsListener(
        (v, i) -> {
          v.setPadding(
              pad + i.getSystemWindowInsetLeft(),
              pad + i.getSystemWindowInsetTop(),
              pad + i.getSystemWindowInsetRight(),
              Ui.dp(this, 12) + i.getSystemWindowInsetBottom());
          return i;
        });
    root.requestApplyInsets();
    title = Ui.header(this, root, "글귀 라이브러리", "사진 곁에 놓아 둘 문장과 시를 등록·수정·삭제할 수 있습니다.");
    Ui.gap(root, 14);
    LinearLayout topActions = Ui.row(this);
    root.addView(topActions);
    LinearLayout.LayoutParams topBtnParams =
        new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1);
    topBtnParams.setMargins(0, 0, Ui.dp(this, 8), Ui.dp(this, 10));
    topActions.addView(
        Ui.button(this, "새 글귀 등록", true, R.drawable.ic_plus, () -> editor(null)),
        topBtnParams);
    LinearLayout.LayoutParams resetBtnParams =
        new LinearLayout.LayoutParams(-2, Ui.dp(this, 50));
    resetBtnParams.setMargins(0, 0, 0, Ui.dp(this, 10));
    topActions.addView(
        Ui.button(this, "기본 샘플 복원", false, R.drawable.ic_refresh, this::restoreDefaults),
        resetBtnParams);
    HorizontalScrollView tabScroll = new HorizontalScrollView(this);
    tabScroll.setHorizontalScrollBarEnabled(false);
    tabsRow = Ui.row(this);
    tabScroll.addView(tabsRow);
    root.addView(tabScroll, new LinearLayout.LayoutParams(-1, -2));
    rebuildTabs();
    ListView list = new ListView(this);
    adapter = new Adapter();
    list.setAdapter(adapter);
    list.setDivider(null);
    list.setDividerHeight(Ui.dp(this, 10));
    list.setSelector(android.R.color.transparent);
    list.setOnItemClickListener((p, v, pos, id) -> editor(rows.get(pos)));
    root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    TextView empty = Ui.text(this, "해당하는 글귀가 없습니다.", 18, Ui.MUTED);
    root.addView(empty);
    list.setEmptyView(empty);
    a.store.listen(this);
    refresh();
  }

  private void rebuildTabs() {
    if (tabsRow == null) return;
    tabsRow.removeAllViews();
    tabs.clear();
    addTab(tabsRow, "전체", "ALL");
    for (String cat : a.prefs.textCategories()) {
      addTab(tabsRow, cat, cat);
    }
    addTab(tabsRow, "기타", "기타");
  }

  private void addTab(LinearLayout row, String label, String key) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, Ui.dp(this, 40));
    p.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), Ui.dp(this, 12));
    Button b =
        Ui.button(
            this,
            label,
            false,
            () -> {
              filter = key;
              for (Map.Entry<String, Button> t : tabs.entrySet())
                selectTab(t.getValue(), t.getKey().equals(key));
              refresh();
            });
    b.setTextSize(13.5f);
    b.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
    tabs.put(key, b);
    selectTab(b, key.equals(filter));
    row.addView(b, p);
  }

  private void selectTab(Button b, boolean on) {
    b.setBackground(
        on
            ? Ui.bg(Ui.PRIMARY, Ui.dp(this, 12))
            : Ui.strokeBg(Ui.SURFACE_SUBTLE, Ui.OUTLINE, Ui.dp(this, 1), Ui.dp(this, 12)));
    b.setTextColor(on ? Ui.ON_PRIMARY : Ui.INK);
  }

  protected void onDestroy() {
    alive = false;
    a.store.unlisten(this);
    super.onDestroy();
  }

  public void changed() {
    runOnUiThread(this::refresh);
  }

  private void refresh() {
    if (!alive) return;
    String f = filter;
    a.io.execute(
        () -> {
          a.studio.ensureSeeded();
          List<TextItem> all = a.studio.dao().textItems();
          List<TextItem> filtered = new ArrayList<>();
          for (TextItem item : all) {
            if (item.body == null || item.body.trim().isEmpty()) continue;
            String cat = (item.category == null || item.category.trim().isEmpty()) ? "기타" : item.category.trim();
            if ("ALL".equals(f) || f.equals(cat)) {
              filtered.add(item);
            }
          }
          a.main.post(
              () -> {
                if (!alive || !f.equals(filter)) return;
                rows = filtered;
                title.setText("글귀 라이브러리 · " + rows.size() + "편");
                adapter.notifyDataSetChanged();
              });
        });
  }

  private void restoreDefaults() {
    new AlertDialog.Builder(this)
        .setTitle("기본 샘플 글귀 복원")
        .setMessage("초기 기본 글귀(nunidam_sample.csv, 250편)를 복원할까요?\n직접 추가한 글귀는 그대로 유지됩니다.")
        .setPositiveButton(
            "복원",
            (d, w) ->
                a.io.execute(
                    () -> {
                      int count = a.studio.seedSampleTextsFromCsv(false);
                      a.store.changed();
                      a.main.post(
                          () -> {
                            if (alive) {
                              rebuildTabs();
                              refresh();
                              Ui.toast(this, "기본 샘플 글귀 " + count + "편을 복원했습니다.");
                            }
                          });
                    }))
        .setNegativeButton("취소", null)
        .show();
  }

  private void editor(TextItem existing) {
    LinearLayout box = Ui.column(this);
    box.setPadding(Ui.dp(this, 22), Ui.dp(this, 10), Ui.dp(this, 22), Ui.dp(this, 10));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(box);

    List<String> cats = new ArrayList<>(a.prefs.textCategories());
    if (!cats.contains("기타")) cats.add("기타");
    if (existing != null && existing.category != null && !existing.category.trim().isEmpty() && !cats.contains(existing.category.trim())) {
      cats.add(existing.category.trim());
    }

    box.addView(Ui.text(this, "카테고리", 14, Ui.MUTED));
    Spinner catSpinner = new Spinner(this);
    catSpinner.setAdapter(
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, cats));
    String initCat =
        existing != null && existing.category != null && !existing.category.trim().isEmpty()
            ? existing.category.trim()
            : (!"ALL".equals(filter) ? filter : cats.get(0));
    int catIdx = Math.max(0, cats.indexOf(initCat));
    catSpinner.setSelection(catIdx);
    box.addView(catSpinner, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));

    Ui.gap(box, 8);
    box.addView(Ui.text(this, "글귀 본문 (시는 줄바꿈하여 입력해 주세요)", 14, Ui.MUTED));
    EditText bodyInput =
        Ui.input(
            this,
            "예: 별을 노래하는 마음으로\n모든 죽어가는것을 사랑해야지...",
            existing != null ? existing.body : "");
    bodyInput.setMinLines(4);
    box.addView(bodyInput);

    Ui.gap(box, 8);
    box.addView(Ui.text(this, "작가 (선택)", 14, Ui.MUTED));
    EditText authorInput = Ui.input(this, "예: 윤동주", existing != null ? existing.author : "");
    authorInput.setSingleLine();
    box.addView(authorInput);

    Ui.gap(box, 8);
    box.addView(Ui.text(this, "제목 (선택)", 14, Ui.MUTED));
    EditText titleInput = Ui.input(this, "예: 서시", existing != null ? existing.title : "");
    titleInput.setSingleLine();
    box.addView(titleInput);

    Ui.gap(box, 8);
    box.addView(Ui.text(this, "출처 · 메모 (선택)", 14, Ui.MUTED));
    EditText sourceInput = Ui.input(this, "예: 하늘과 바람과 별과 시", existing != null ? existing.source : "");
    sourceInput.setSingleLine();
    box.addView(sourceInput);

    AlertDialog.Builder builder =
        new AlertDialog.Builder(this)
            .setTitle(existing == null ? "새 글귀 등록" : "글귀 수정 · 삭제")
            .setView(scroll)
            .setPositiveButton("저장", null)
            .setNegativeButton("취소", null);
    if (existing != null)
      builder.setNeutralButton(
          "삭제",
          (d, w) ->
              a.io.execute(
                  () -> {
                    a.studio.dao().deleteTextItem(existing.id);
                    a.store.changed();
                  }));
    AlertDialog dialog = builder.create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      String b = bodyInput.getText().toString().trim();
                      String au = authorInput.getText().toString().trim();
                      String ti = titleInput.getText().toString().trim();
                      String sr = sourceInput.getText().toString().trim();
                      String cat = String.valueOf(catSpinner.getSelectedItem()).trim();
                      if (cat.isEmpty()) cat = "기타";
                      if (b.isEmpty()) {
                        bodyInput.setError("본문을 입력해 주세요.");
                        return;
                      }
                      if (b.length() > 2000) {
                        bodyInput.setError("본문은 2,000자 이내로 입력해 주세요.");
                        return;
                      }
                      final String finalCat = cat;
                      a.io.execute(
                          () -> {
                            long now = System.currentTimeMillis();
                            TextItem item = existing != null ? existing : new TextItem();
                            if (existing == null || item.id == null || item.id.isEmpty()) {
                              item.id = "text_" + now + "_" + new SecureRandom().nextInt(10000);
                              item.createdAt = now;
                              item.sortOrder = a.studio.dao().textItems().size() + 1;
                              item.displaySeconds = 20;
                              item.enabled = true;
                            }
                            item.category = finalCat;
                            item.body = b;
                            item.author = au;
                            item.title = ti;
                            item.source = sr;
                            item.kind =
                                ("시 한 구절".equals(finalCat)
                                        || finalCat.contains("시")
                                        || b.indexOf('\n') >= 0)
                                    ? "POEM"
                                    : "QUOTE";
                            item.updatedAt = now;
                            a.prefs.ensureTextCategory(finalCat);
                            a.studio.dao().saveTextItem(item);
                            a.store.changed();
                          });
                      dialog.dismiss();
                    }));
    dialog.show();
  }

  private final class Adapter extends BaseAdapter {
    public int getCount() {
      return rows.size();
    }

    public Object getItem(int i) {
      return rows.get(i);
    }

    public long getItemId(int i) {
      return i;
    }

    public View getView(int pos, View old, ViewGroup parent) {
      Activity c = QuotesActivity.this;
      LinearLayout row = old instanceof LinearLayout ? (LinearLayout) old : Ui.column(c);
      row.removeAllViews();
      row.setPadding(Ui.dp(c, 18), Ui.dp(c, 16), Ui.dp(c, 18), Ui.dp(c, 16));
      android.graphics.drawable.GradientDrawable card = Ui.bg(Ui.SURFACE, Ui.dp(c, 20));
      card.setStroke(Ui.dp(c, 1), Ui.LINE);
      row.setBackground(card);
      TextItem q = rows.get(pos);
      boolean isPoem = "POEM".equals(q.kind) || (q.body != null && q.body.indexOf('\n') >= 0);
      String cat = (q.category == null || q.category.trim().isEmpty()) ? "기타" : q.category.trim();
      TextView badge =
          Ui.text(
              c,
              cat + (q.title != null && !q.title.isEmpty() ? "  ·  " + q.title : ""),
              12,
              Ui.PRIMARY);
      badge.setCompoundDrawablesRelative(
          Ui.icon(c, isPoem ? R.drawable.ic_heart : R.drawable.ic_quote, Ui.PRIMARY, 16),
          null,
          null,
          null);
      badge.setCompoundDrawablePadding(Ui.dp(c, 6));
      badge.setGravity(Gravity.CENTER_VERTICAL);
      row.addView(badge);
      Ui.gap(row, 8);
      TextView body = Ui.text(c, q.body, 16, Ui.INK);
      body.setTypeface(android.graphics.Typeface.SERIF);
      body.setMaxLines(4);
      body.setEllipsize(android.text.TextUtils.TruncateAt.END);
      row.addView(body);
      if (q.author != null && !q.author.isEmpty()) {
        Ui.gap(row, 6);
        TextView author = Ui.text(c, "— " + q.author, 13, Ui.MUTED);
        author.setTypeface(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC);
        row.addView(author);
      }
      return row;
    }
  }
}
