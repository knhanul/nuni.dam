package co.kr.nuni.dam;

import android.app.*;
import android.graphics.*;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public final class LibraryActivity extends Activity implements PhotoStore.Listener {
  private NuniApp a;
  private boolean alive;
  private LinearLayout root;

  public void onCreate(Bundle s) {
    super.onCreate(s);
    alive = true;
    a = NuniApp.of(this);
    getWindow().setStatusBarColor(Ui.BG);
    getWindow().setNavigationBarColor(Ui.BG);
    root = Ui.column(this);
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
    buildView(root, this, null);
    a.store.listen(this);
  }

  protected void onDestroy() {
    alive = false;
    a.store.unlisten(this);
    super.onDestroy();
  }

  public void changed() {
    runOnUiThread(
        () -> {
          if (alive && activeController != null) activeController.refresh();
        });
  }

  private Controller activeController;

  public static Controller buildView(
      LinearLayout container, Activity host, Runnable onAddFromDevice) {
    Controller c = new Controller(host, onAddFromDevice);
    if (host instanceof LibraryActivity) ((LibraryActivity) host).activeController = c;
    c.attach(container);
    return c;
  }

  public static final class Controller {
    private final Activity host;
    private final NuniApp a;
    private final Runnable onAddDevice;
    private String filter = "ALL"; // ALL, FAV, TRASH
    private boolean selectionMode;
    private final Set<String> selectedIds = new HashSet<>();
    private String detailPhotoId;
    private List<Photo> rows = new ArrayList<>();
    private TextView headerTitle, selectionInfo, emptyView;
    private LinearLayout batchBar, detailContainer;
    private GridView grid;
    private GridAdapter adapter;
    private final Map<String, Button> filterButtons = new HashMap<>();
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> cache =
        new LruCache<String, Bitmap>(4 * 1024 * 1024) {
          protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
          }
        };

    public Controller(Activity h, Runnable addDeviceAction) {
      host = h;
      a = NuniApp.of(h);
      onAddDevice = addDeviceAction;
    }

    public void release() {
      images.shutdownNow();
      cache.evictAll();
    }

    public void attach(LinearLayout parent) {
      parent.removeAllViews();
      int widthDp = host.getResources().getConfiguration().screenWidthDp;
      boolean twoPane = widthDp >= 840;

      LinearLayout topRow = Ui.row(host);
      headerTitle = Ui.title(host, "사진 보관함", 24);
      topRow.addView(headerTitle, new LinearLayout.LayoutParams(0, -2, 1));
      if (onAddDevice != null) {
        Button addBtn = Ui.button(host, "사진 추가", true, R.drawable.ic_plus, onAddDevice);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, Ui.dp(host, 48));
        ap.setMarginEnd(Ui.dp(host, 8));
        topRow.addView(addBtn, ap);
      }
      Button qrBtn =
          Ui.button(
              host,
              "QR 전송",
              false,
              R.drawable.ic_phone,
              () -> Ui.open(host, TransferActivity.class));
      topRow.addView(qrBtn, new LinearLayout.LayoutParams(-2, Ui.dp(host, 48)));
      parent.addView(topRow);
      Ui.gap(parent, 10);

      LinearLayout filterRow = Ui.row(host);
      addFilterTab(filterRow, "전체", "ALL");
      addFilterTab(filterRow, "즐겨찾기", "FAV");
      addFilterTab(filterRow, "휴지통", "TRASH");
      Button selectToggle =
          Ui.button(
              host,
              "선택",
              false,
              R.drawable.ic_check,
              () -> {
                selectionMode = !selectionMode;
                if (!selectionMode) selectedIds.clear();
                updateBatchBar();
                adapter.notifyDataSetChanged();
              });
      LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, Ui.dp(host, 44));
      sp.setMarginStart(Ui.dp(host, 8));
      filterRow.addView(selectToggle, sp);
      parent.addView(filterRow);

      batchBar = Ui.row(host);
      batchBar.setPadding(Ui.dp(host, 12), Ui.dp(host, 8), Ui.dp(host, 12), Ui.dp(host, 8));
      batchBar.setBackground(Ui.strokeBg(Ui.SURFACE, Ui.OUTLINE, Ui.dp(host, 1), Ui.dp(host, 12)));
      batchBar.setVisibility(View.GONE);
      selectionInfo = Ui.text(host, "0장 선택됨", 14, Ui.INK);
      batchBar.addView(selectionInfo, new LinearLayout.LayoutParams(0, -2, 1));
      parent.addView(batchBar);
      Ui.gap(parent, 8);

      LinearLayout body = twoPane ? Ui.row(host) : Ui.column(host);
      body.setGravity(Gravity.TOP);
      parent.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

      LinearLayout leftPane = Ui.column(host);
      grid = new GridView(host);
      int cols = widthDp >= 1100 ? 4 : widthDp >= 600 ? 3 : 2;
      grid.setNumColumns(cols);
      grid.setHorizontalSpacing(Ui.dp(host, 10));
      grid.setVerticalSpacing(Ui.dp(host, 10));
      grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
      grid.setSelector(android.R.color.transparent);
      adapter = new GridAdapter();
      grid.setAdapter(adapter);
      grid.setOnItemClickListener(
          (p, v, pos, id) -> {
            if (pos < 0 || pos >= rows.size()) return;
            Photo item = rows.get(pos);
            if (selectionMode) {
              if (!selectedIds.remove(item.id)) selectedIds.add(item.id);
              updateBatchBar();
              adapter.notifyDataSetChanged();
            } else {
              detailPhotoId = item.id;
              if (twoPane && detailContainer != null) {
                renderDetailPane(detailContainer, item);
              } else {
                showDetailDialog(item);
              }
            }
          });
      leftPane.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
      emptyView = Ui.text(host, "표시할 사진이 없습니다.", 16, Ui.MUTED);
      emptyView.setPadding(Ui.dp(host, 16), Ui.dp(host, 24), Ui.dp(host, 16), Ui.dp(host, 24));
      leftPane.addView(emptyView);
      grid.setEmptyView(emptyView);

      if (twoPane) {
        body.addView(leftPane, new LinearLayout.LayoutParams(0, -1, 6));
        detailContainer = Ui.column(host);
        detailContainer.setPadding(Ui.dp(host, 16), 0, 0, 0);
        body.addView(detailContainer, new LinearLayout.LayoutParams(0, -1, 4));
      } else {
        body.addView(leftPane, new LinearLayout.LayoutParams(-1, 0, 1));
        detailContainer = null;
      }
      refresh();
    }

    private void addFilterTab(LinearLayout row, String label, String key) {
      Button b =
          Ui.button(
              host,
              label,
              false,
              () -> {
                filter = key;
                selectedIds.clear();
                updateFilterTabs();
                updateBatchBar();
                refresh();
              });
      b.setTextSize(14f);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(host, 44), 1);
      lp.setMarginEnd(Ui.dp(host, 6));
      filterButtons.put(key, b);
      row.addView(b, lp);
      updateFilterTabs();
    }

    private void updateFilterTabs() {
      for (Map.Entry<String, Button> e : filterButtons.entrySet()) {
        boolean sel = e.getKey().equals(filter);
        Button b = e.getValue();
        b.setBackground(
            sel
                ? Ui.bg(Ui.PRIMARY, Ui.dp(host, 12))
                : Ui.strokeBg(Ui.SURFACE_SUBTLE, Ui.OUTLINE, Ui.dp(host, 1), Ui.dp(host, 12)));
        b.setTextColor(sel ? Ui.ON_PRIMARY : Ui.INK);
      }
    }

    private void updateBatchBar() {
      batchBar.removeAllViews();
      if (!selectionMode) {
        batchBar.setVisibility(View.GONE);
        return;
      }
      batchBar.setVisibility(View.VISIBLE);
      int n = selectedIds.size();
      selectionInfo = Ui.text(host, n + "장 선택됨", 14, Ui.INK);
      batchBar.addView(selectionInfo, new LinearLayout.LayoutParams(0, -2, 1));
      if ("TRASH".equals(filter)) {
        Button restoreBtn =
            Ui.button(
                host,
                "복원(" + n + ")",
                false,
                R.drawable.ic_refresh,
                () -> {
                  if (selectedIds.isEmpty()) return;
                  Set<String> ids = new HashSet<>(selectedIds);
                  selectedIds.clear();
                  a.io.execute(
                      () -> {
                        for (String id : ids) a.store.restore(id);
                      });
                });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, Ui.dp(host, 44));
        rp.setMarginEnd(Ui.dp(host, 6));
        batchBar.addView(restoreBtn, rp);
        Button purgeBtn =
            Ui.dangerButton(
                host,
                "영구 삭제(" + n + ")",
                R.drawable.ic_trash,
                () -> {
                  if (selectedIds.isEmpty()) return;
                  Set<String> ids = new HashSet<>(selectedIds);
                  new AlertDialog.Builder(host)
                      .setTitle(ids.size() + "장을 영구 삭제할까요?")
                      .setMessage("누니담 사본을 영구 삭제합니다. 원본 기기의 사진은 지우지 않습니다.")
                      .setPositiveButton(
                          ids.size() + "장 영구 삭제",
                          (d, w) -> {
                            selectedIds.clear();
                            a.io.execute(
                                () -> {
                                  for (String id : ids) {
                                    try {
                                      a.store.purge(id);
                                    } catch (Exception ignored) {
                                    }
                                  }
                                });
                          })
                      .setNegativeButton("취소", null)
                      .show();
                });
        batchBar.addView(purgeBtn, new LinearLayout.LayoutParams(-2, Ui.dp(host, 44)));
      } else {
        Button favBtn =
            Ui.button(
                host,
                "즐겨찾기(" + n + ")",
                false,
                R.drawable.ic_heart,
                () -> {
                  if (selectedIds.isEmpty()) return;
                  Set<String> ids = new HashSet<>(selectedIds);
                  selectedIds.clear();
                  a.io.execute(
                      () -> {
                        for (String id : ids) a.db.photos().favourite(id, true);
                        a.store.changed();
                      });
                });
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-2, Ui.dp(host, 44));
        fp.setMarginEnd(Ui.dp(host, 6));
        batchBar.addView(favBtn, fp);
        Button trashBtn =
            Ui.button(
                host,
                "휴지통(" + n + ")",
                false,
                R.drawable.ic_trash,
                () -> {
                  if (selectedIds.isEmpty()) return;
                  Set<String> ids = new HashSet<>(selectedIds);
                  selectedIds.clear();
                  a.io.execute(
                      () -> {
                        for (String id : ids) a.store.trash(id);
                      });
                  Ui.toast(host, ids.size() + "장을 휴지통으로 옮겼습니다. 휴지통 탭에서 복원할 수 있습니다.");
                });
        batchBar.addView(trashBtn, new LinearLayout.LayoutParams(-2, Ui.dp(host, 44)));
      }
    }

    public void refresh() {
      if (!Ui.alive(host)) return;
      String reqFilter = filter;
      a.io.execute(
          () -> {
            List<Photo> list;
            if ("TRASH".equals(reqFilter)) {
              list = a.db.photos().trash();
            } else {
              list = a.db.photos().active();
              if ("FAV".equals(reqFilter)) {
                Iterator<Photo> it = list.iterator();
                while (it.hasNext()) if (!it.next().favourite) it.remove();
              }
            }
            a.main.post(
                () -> {
                  if (!Ui.alive(host) || !reqFilter.equals(filter)) return;
                  rows = list;
                  String label =
                      "TRASH".equals(filter)
                          ? "휴지통"
                          : "FAV".equals(filter) ? "즐겨찾기 사진" : "사진 보관함";
                  headerTitle.setText(label + " · " + rows.size() + "장");
                  if ("FAV".equals(filter) && rows.isEmpty()) {
                    emptyView.setText("즐겨찾기한 사진이 없습니다. 사진을 눌러 즐겨찾기를 켜거나 전체 필터로 전환하세요.");
                  } else if ("TRASH".equals(filter) && rows.isEmpty()) {
                    emptyView.setText("휴지통이 비어 있습니다.");
                  } else {
                    emptyView.setText("아직 담긴 사진이 없습니다. ‘사진 추가’나 ‘QR 전송’으로 첫 사진을 담아보세요.");
                  }
                  adapter.notifyDataSetChanged();
                  if (detailContainer != null) {
                    Photo target = null;
                    for (Photo p : rows) if (p.id.equals(detailPhotoId)) target = p;
                    if (target == null && !rows.isEmpty()) target = rows.get(0);
                    renderDetailPane(detailContainer, target);
                  }
                });
          });
    }

    private void renderDetailPane(LinearLayout container, Photo p) {
      container.removeAllViews();
      ScrollView sv = new ScrollView(host);
      LinearLayout card = Ui.column(host);
      card.setPadding(Ui.dp(host, 18), Ui.dp(host, 18), Ui.dp(host, 18), Ui.dp(host, 18));
      card.setBackground(Ui.strokeBg(Ui.SURFACE, Ui.OUTLINE, Ui.dp(host, 1), Ui.dp(host, 16)));
      sv.addView(card);
      container.addView(sv, new LinearLayout.LayoutParams(-1, -1));
      if (p == null) {
        card.addView(Ui.text(host, "사진을 선택하면 상세 정보와 구도 편집이 여기에 표시됩니다.", 15, Ui.MUTED));
        return;
      }
      populateDetailEditor(card, p, null);
    }

    private void showDetailDialog(Photo p) {
      if ("TRASH".equals(filter)) {
        new AlertDialog.Builder(host)
            .setTitle(p.name)
            .setItems(
                new String[] {"보관함으로 복원", "1장 영구 삭제"},
                (d, k) -> {
                  if (k == 0) {
                    a.io.execute(() -> a.store.restore(p.id));
                    Ui.toast(host, "보관함으로 복원했습니다.");
                  } else {
                    confirmPurgeSingle(p);
                  }
                })
            .show();
        return;
      }
      ScrollView scroll = new ScrollView(host);
      LinearLayout box = Ui.column(host);
      box.setPadding(Ui.dp(host, 20), Ui.dp(host, 14), Ui.dp(host, 20), Ui.dp(host, 14));
      scroll.addView(box);
      AlertDialog dialog =
          new AlertDialog.Builder(host)
              .setTitle(p.name)
              .setView(scroll)
              .setNegativeButton("닫기", null)
              .create();
      populateDetailEditor(box, p, dialog::dismiss);
      dialog.show();
    }

    private void confirmPurgeSingle(Photo p) {
      new AlertDialog.Builder(host)
          .setTitle("1장을 영구 삭제할까요?")
          .setMessage("누니담 사본을 영구 삭제합니다. 원본 기기의 사진은 변경하지 않습니다.")
          .setPositiveButton(
              "1장 영구 삭제",
              (x, y) ->
                  a.io.execute(
                      () -> {
                        try {
                          a.store.purge(p.id);
                        } catch (Exception e) {
                          a.main.post(() -> Ui.error(host, e));
                        }
                      }))
          .setNegativeButton("취소", null)
          .show();
    }

    private void populateDetailEditor(LinearLayout box, Photo p, Runnable onSaved) {
      ImageView preview = new ImageView(host);
      preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
      preview.setBackground(Ui.bg(Ui.SURFACE_SUBTLE, Ui.dp(host, 12)));
      preview.setClipToOutline(true);
      box.addView(preview, new LinearLayout.LayoutParams(-1, Ui.dp(host, 180)));
      images.execute(
          () -> {
            Bitmap b = a.store.load(p, 640);
            a.main.post(
                () -> {
                  if (b != null) preview.setImageBitmap(b);
                });
          });
      Ui.gap(box, 10);
      box.addView(
          Ui.text(
              host,
              String.format(
                  Locale.KOREA,
                  "%s · %d×%d · %.1f MB",
                  p.name,
                  p.width,
                  p.height,
                  p.bytes / 1048576d),
              13,
              Ui.MUTED));
      if ("TRASH".equals(filter)) {
        Ui.gap(box, 12);
        Ui.add(
            box,
            "보관함으로 복원",
            true,
            R.drawable.ic_refresh,
            () -> {
              a.io.execute(() -> a.store.restore(p.id));
              if (onSaved != null) onSaved.run();
            });
        Ui.add(
            box,
            "1장 영구 삭제",
            false,
            R.drawable.ic_trash,
            () -> confirmPurgeSingle(p));
        return;
      }
      Ui.gap(box, 10);
      box.addView(Ui.text(host, "사진에 남길 기록 (400자 이내)", 14, Ui.MUTED));
      EditText note = Ui.input(host, "비워두면 명언·시 모음이 표시됩니다.", p.note);
      note.setMinLines(2);
      box.addView(note);
      Ui.gap(box, 8);
      SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
      fmt.setLenient(false);
      box.addView(Ui.text(host, "촬영일 (YYYY-MM-DD)", 14, Ui.MUTED));
      EditText date =
          Ui.input(
              host,
              "예: 2024-05-12 · 모르면 비워두세요",
              p.capturedAt > 0 ? fmt.format(new Date(p.capturedAt)) : "");
      date.setSingleLine();
      box.addView(date);
      Ui.gap(box, 8);
      Switch fav = new Switch(host);
      fav.setText("즐겨찾기 · 자동 삭제 보호");
      fav.setTextColor(Ui.INK);
      fav.setChecked(p.favourite);
      box.addView(fav);
      Ui.gap(box, 8);
      String focusStatus =
          p.focusX >= 0 && p.focusY >= 0 ? "현재 구도: 직접 지정됨" : "현재 구도: 얼굴 보호 자동 구도 / 전체 보기";
      box.addView(Ui.text(host, focusStatus, 13, Ui.MUTED));
      Ui.add(box, "사진 구도 직접 지정", false, R.drawable.ic_focus, () -> crop(p));
      Ui.add(
          box,
          "자동 구도(얼굴 보호/전체 보기)로 초기화",
          false,
          R.drawable.ic_refresh,
          () ->
              a.io.execute(
                  () -> {
                    a.db.photos().focus(p.id, -1, -1);
                    a.store.changed();
                    a.main.post(() -> Ui.toast(host, "자동 구도로 초기화했습니다."));
                  }));
      Ui.gap(box, 8);
      Ui.add(
          box,
          "변경 내용 저장",
          true,
          R.drawable.ic_check,
          () -> {
            try {
              if (note.length() > 400) {
                note.setError("기록은 400자 이내로 입력하세요.");
                return;
              }
              String value = date.getText().toString().trim();
              long captured = 0;
              if (!value.isEmpty()) {
                if (!value.matches("\\d{4}-\\d{2}-\\d{2}"))
                  throw new IllegalArgumentException("날짜 형식은 YYYY-MM-DD입니다.");
                captured = fmt.parse(value).getTime();
                if (captured < 0) throw new IllegalArgumentException("1970년 이후 날짜를 입력하세요.");
              }
              long stamp = captured;
              String text = note.getText().toString().trim();
              boolean favourite = fav.isChecked();
              a.io.execute(
                  () -> {
                    Photo latest = a.db.photos().get(p.id);
                    if (latest != null) {
                      latest.note = text;
                      latest.capturedAt = stamp;
                      latest.favourite = favourite;
                      a.db.photos().update(latest);
                      a.store.changed();
                    }
                  });
              Ui.toast(host, "저장했습니다.");
              if (onSaved != null) onSaved.run();
            } catch (Exception e) {
              date.setError(e.getMessage());
            }
          });
      Ui.add(
          box,
          "휴지통으로 이동 (복원 가능)",
          false,
          R.drawable.ic_trash,
          () -> {
            a.io.execute(() -> a.store.trash(p.id));
            Ui.toast(host, "휴지통으로 옮겼습니다.");
            if (onSaved != null) onSaved.run();
          });
    }

    private void crop(Photo p) {
      images.execute(
          () -> {
            Bitmap b = a.store.load(p, 1280);
            a.main.post(
                () -> {
                  if (!Ui.alive(host) || b == null) return;
                  FocusView v = new FocusView(host, b, p.focusX, p.focusY);
                  new AlertDialog.Builder(host)
                      .setTitle("중심으로 보여줄 곳을 터치하세요")
                      .setView(v)
                      .setPositiveButton(
                          "구도 적용",
                          (d, k) -> {
                            float x = v.fx, y = v.fy;
                            a.io.execute(
                                () -> {
                                  a.db.photos().focus(p.id, x, y);
                                  a.store.changed();
                                });
                          })
                      .setNeutralButton(
                          "자동 구도로 초기화",
                          (d, k) ->
                              a.io.execute(
                                  () -> {
                                    a.db.photos().focus(p.id, -1, -1);
                                    a.store.changed();
                                  }))
                      .setNegativeButton("취소", null)
                      .show();
                });
          });
    }

    private final class GridAdapter extends BaseAdapter {
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
        LinearLayout card;
        ImageView image;
        TextView caption;
        if (old instanceof LinearLayout) {
          card = (LinearLayout) old;
          image = (ImageView) card.getChildAt(0);
          caption = (TextView) card.getChildAt(1);
        } else {
          card = Ui.column(host);
          card.setPadding(Ui.dp(host, 8), Ui.dp(host, 8), Ui.dp(host, 8), Ui.dp(host, 10));
          image = new ImageView(host);
          image.setScaleType(ImageView.ScaleType.CENTER_CROP);
          image.setBackground(Ui.bg(Ui.SURFACE_SUBTLE, Ui.dp(host, 12)));
          image.setClipToOutline(true);
          card.addView(image, new LinearLayout.LayoutParams(-1, Ui.dp(host, 148)));
          caption = Ui.text(host, "", 13.5f, Ui.INK);
          caption.setPadding(Ui.dp(host, 4), Ui.dp(host, 6), Ui.dp(host, 4), 0);
          caption.setMaxLines(2);
          caption.setEllipsize(TextUtils.TruncateAt.END);
          card.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        }
        Photo p = rows.get(pos);
        boolean sel = selectedIds.contains(p.id);
        card.setBackground(
            Ui.strokeBg(
                sel ? Ui.SURFACE_SUBTLE : Ui.SURFACE,
                sel ? Ui.PRIMARY : Ui.OUTLINE,
                Ui.dp(host, sel ? 2 : 1),
                Ui.dp(host, 16)));
        String prefix = (sel ? "[선택됨] " : "") + (p.favourite ? "★ " : "");
        String label = prefix + (p.note.isEmpty() ? p.name : p.note);
        caption.setText(label);
        image.setTag(p.id);
        image.setContentDescription(label);
        Bitmap cached = cache.get(p.id);
        image.setImageBitmap(cached);
        if (cached == null && !images.isShutdown()) {
          images.execute(
              () -> {
                Bitmap loaded = a.store.load(p, 320);
                if (loaded != null) {
                  cache.put(p.id, loaded);
                  a.main.post(
                      () -> {
                        if (Ui.alive(host) && p.id.equals(image.getTag()))
                          image.setImageBitmap(loaded);
                      });
                } else {
                  a.main.post(
                      () -> {
                        if (Ui.alive(host) && p.id.equals(image.getTag()))
                          caption.setText(label + " (미리보기 재시도하려면 터치)");
                      });
                }
              });
        }
        return card;
      }
    }
  }

  private static final class FocusView extends View {
    final Bitmap bitmap;
    final Paint paint = new Paint(3);
    final RectF box = new RectF();
    float fx, fy;

    FocusView(android.content.Context c, Bitmap b, float x, float y) {
      super(c);
      bitmap = b;
      fx = x < 0 ? .5f : x;
      fy = y < 0 ? .5f : y;
      setContentDescription("사진 중심점을 터치해 선택하세요");
    }

    protected void onMeasure(int w, int h) {
      setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(getContext(), 320));
    }

    protected void onDraw(Canvas c) {
      c.drawColor(Ui.DARK_BG);
      float
          s =
              Math.min(
                  getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight()),
          w = bitmap.getWidth() * s,
          h = bitmap.getHeight() * s;
      box.set(
          (getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
      c.drawBitmap(bitmap, null, box, paint);
      paint.setStyle(Paint.Style.STROKE);
      paint.setColor(Color.WHITE);
      paint.setStrokeWidth(5);
      c.drawCircle(box.left + fx * box.width(), box.top + fy * box.height(), 22, paint);
      paint.setStyle(Paint.Style.FILL);
    }

    public boolean onTouchEvent(MotionEvent e) {
      if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE) {
        if (box.width() > 0 && box.height() > 0) {
          fx = Math.max(0, Math.min(1, (e.getX() - box.left) / box.width()));
          fy = Math.max(0, Math.min(1, (e.getY() - box.top) / box.height()));
          invalidate();
        }
        return true;
      }
      if (e.getAction() == MotionEvent.ACTION_UP) {
        performClick();
        return true;
      }
      return super.onTouchEvent(e);
    }

    public boolean performClick() {
      super.performClick();
      return true;
    }
  }
}
