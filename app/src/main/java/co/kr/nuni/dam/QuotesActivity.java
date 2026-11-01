package co.kr.nuni.dam;

import android.app.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class QuotesActivity extends Activity implements PhotoStore.Listener {
  private NuniApp a;
  private boolean alive;
  private String filter = "ALL";
  private TextView title;
  private List<Quote> rows = new ArrayList<>();
  private Adapter adapter;
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
    title = Ui.header(this, root, "명언 · 시 모음", "사진 곁에 놓아 둘 문장과 시를 골라 담아 보세요.");
    Ui.gap(root, 14);
    LinearLayout topActions = Ui.row(this);
    root.addView(topActions);
    LinearLayout.LayoutParams topBtnParams =
        new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1);
    topBtnParams.setMargins(0, 0, Ui.dp(this, 8), Ui.dp(this, 10));
    topActions.addView(
        Ui.button(this, "새 명언 · 시 추가", true, R.drawable.ic_plus, () -> editor(null)),
        topBtnParams);
    LinearLayout.LayoutParams resetBtnParams =
        new LinearLayout.LayoutParams(-2, Ui.dp(this, 50));
    resetBtnParams.setMargins(0, 0, 0, Ui.dp(this, 10));
    topActions.addView(
        Ui.button(this, "기본 복원", false, R.drawable.ic_refresh, this::restoreDefaults),
        resetBtnParams);
    LinearLayout tabs = Ui.row(this);
    root.addView(tabs);
    addTab(tabs, "전체", "ALL");
    addTab(tabs, "명언", "QUOTE");
    addTab(tabs, "시(詩)", "POEM");
    addTab(tabs, "내 등록", "CUSTOM");
    ListView list = new ListView(this);
    adapter = new Adapter();
    list.setAdapter(adapter);
    list.setDivider(null);
    list.setDividerHeight(Ui.dp(this, 10));
    list.setSelector(android.R.color.transparent);
    list.setOnItemClickListener((p, v, pos, id) -> editor(rows.get(pos)));
    root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    TextView empty = Ui.text(this, "해당하는 명언·시가 없습니다.", 18, Ui.MUTED);
    root.addView(empty);
    list.setEmptyView(empty);
    a.store.listen(this);
    refresh();
  }

  private void addTab(LinearLayout row, String label, String key) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1);
    p.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), Ui.dp(this, 12));
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
          List<Quote> all = a.db.photos().quotes();
          List<Quote> filtered = new ArrayList<>();
          for (Quote q : all) {
            Core.QuoteEntry e = Core.parseQuote(q.text);
            if (e.body.isEmpty()) continue;
            if ("CUSTOM".equals(f)) {
              if (q.id >= 1000) filtered.add(q);
            } else if (Core.matchesQuoteKind(e, f)) {
              filtered.add(q);
            }
          }
          Collections.sort(
              filtered,
              (x, y) -> {
                boolean xc = x.id >= 1000, yc = y.id >= 1000;
                if (xc != yc) return xc ? -1 : 1;
                return xc ? Integer.compare(y.id, x.id) : Integer.compare(x.id, y.id);
              });
          a.main.post(
              () -> {
                if (!alive || !f.equals(filter)) return;
                rows = filtered;
                title.setText("명언 · 시 모음 · " + rows.size());
                adapter.notifyDataSetChanged();
              });
        });
  }

  private void restoreDefaults() {
    a.io.execute(
        () -> {
          try (InputStream in = getAssets().open("phrases.txt")) {
            String text = new String(Core.read(in, 128 * 1024), StandardCharsets.UTF_8);
            int id = 0;
            for (String line : text.split("\\r?\\n"))
              if (!line.trim().isEmpty()) {
                Quote q = new Quote();
                q.id = id++;
                q.text = line.trim();
                a.db.photos().quote(q);
              }
            a.store.changed();
            a.main.post(
                () -> {
                  if (alive) Ui.toast(this, "기본 명언과 시를 모두 복원했습니다.");
                });
          } catch (Exception e) {
            a.main.post(
                () -> {
                  if (alive) Ui.error(this, e);
                });
          }
        });
  }

  private void editor(Quote existing) {
    Core.QuoteEntry parsed =
        existing == null ? new Core.QuoteEntry("POEM", "", "") : Core.parseQuote(existing.text);
    LinearLayout box = Ui.column(this);
    box.setPadding(Ui.dp(this, 22), Ui.dp(this, 10), Ui.dp(this, 22), Ui.dp(this, 10));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(box);
    box.addView(Ui.text(this, "분류", 14, Ui.MUTED));
    Spinner kindSpinner = new Spinner(this);
    kindSpinner.setAdapter(
        new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"시(詩) · 여러 줄 문학", "명언 · 짧은 문구"}));
    kindSpinner.setSelection(parsed.isPoem() ? 0 : 1);
    box.addView(kindSpinner, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
    Ui.gap(box, 8);
    box.addView(Ui.text(this, "본문 (시는 줄바꿈하여 입력해 주세요)", 14, Ui.MUTED));
    EditText bodyInput =
        Ui.input(this, "예: 죽는 날까지 하늘을 우러러\n한 점 부끄럼이 없기를...", parsed.body);
    bodyInput.setMinLines(4);
    box.addView(bodyInput);
    Ui.gap(box, 8);
    box.addView(Ui.text(this, "작가 · 제목 또는 출처 (선택)", 14, Ui.MUTED));
    EditText authorInput = Ui.input(this, "예: 윤동주 · 서시", parsed.author);
    authorInput.setSingleLine();
    box.addView(authorInput);
    AlertDialog.Builder builder =
        new AlertDialog.Builder(this)
            .setTitle(existing == null ? "새 명언 · 시 추가" : "명언 · 시 편집")
            .setView(scroll)
            .setPositiveButton("저장", null)
            .setNegativeButton("취소", null);
    if (existing != null)
      builder.setNeutralButton(
          "삭제",
          (d, w) ->
              a.io.execute(
                  () -> {
                    a.db.photos().deleteQuote(existing.id);
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
                      if (b.isEmpty()) {
                        bodyInput.setError("본문을 입력해 주세요.");
                        return;
                      }
                      if (b.length() > 600) {
                        bodyInput.setError("본문은 600자 이내로 입력해 주세요.");
                        return;
                      }
                      if (au.length() > 100) {
                        authorInput.setError("출처는 100자 이내로 입력해 주세요.");
                        return;
                      }
                      String k = kindSpinner.getSelectedItemPosition() == 0 ? "POEM" : "QUOTE";
                      String encoded = new Core.QuoteEntry(k, b, au).encode();
                      a.io.execute(
                          () -> {
                            Quote q = new Quote();
                            q.id =
                                existing != null
                                    ? existing.id
                                    : Math.max(1000, a.db.photos().maxCustomQuoteId() + 1);
                            q.text = encoded;
                            a.db.photos().quote(q);
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
      return rows.get(i).id;
    }

    public View getView(int pos, View old, ViewGroup parent) {
      Activity c = QuotesActivity.this;
      LinearLayout row = old instanceof LinearLayout ? (LinearLayout) old : Ui.column(c);
      row.removeAllViews();
      row.setPadding(Ui.dp(c, 18), Ui.dp(c, 16), Ui.dp(c, 18), Ui.dp(c, 16));
      android.graphics.drawable.GradientDrawable card = Ui.bg(Ui.SURFACE, Ui.dp(c, 20));
      card.setStroke(Ui.dp(c, 1), Ui.LINE);
      row.setBackground(card);
      Quote q = rows.get(pos);
      Core.QuoteEntry e = Core.parseQuote(q.text);
      TextView badge =
          Ui.text(
              c,
              (e.isPoem() ? "시 " : "명언 ") + " ·  " + (q.id >= 1000 ? "내 등록" : "기본 수록"),
              12,
              Ui.PRIMARY);
      badge.setCompoundDrawablesRelative(
          Ui.icon(c, e.isPoem() ? R.drawable.ic_heart : R.drawable.ic_quote, Ui.PRIMARY, 16),
          null,
          null,
          null);
      badge.setCompoundDrawablePadding(Ui.dp(c, 6));
      badge.setGravity(Gravity.CENTER_VERTICAL);
      row.addView(badge);
      Ui.gap(row, 8);
      TextView body = Ui.text(c, e.body, 16, Ui.INK);
      body.setTypeface(android.graphics.Typeface.SERIF);
      body.setMaxLines(4);
      body.setEllipsize(android.text.TextUtils.TruncateAt.END);
      row.addView(body);
      if (!e.author.isEmpty()) {
        Ui.gap(row, 6);
        TextView author = Ui.text(c, "— " + e.author, 13, Ui.MUTED);
        author.setTypeface(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC);
        row.addView(author);
      }
      return row;
    }
  }
}
