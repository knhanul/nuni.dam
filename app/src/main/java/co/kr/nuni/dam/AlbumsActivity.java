package co.kr.nuni.dam;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public final class AlbumsActivity extends Activity implements PhotoStore.Listener {
  public static final int REQ_PICK_ALBUM_PHOTOS = 31;

  private NuniApp a;
  private boolean alive;
  private Controller controller;
  private AlertDialog progressDialog;
  private String pendingImportAlbumId = "";

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
    String initAlbumId = getIntent() != null ? getIntent().getStringExtra("albumId") : null;
    controller = buildView(root, this, this::launchPhotoPickerForAlbum, initAlbumId);
    a.store.listen(this);
  }

  @Override
  protected void onDestroy() {
    alive = false;
    a.store.unlisten(this);
    if (controller != null) controller.release();
    if (progressDialog != null) progressDialog.dismiss();
    super.onDestroy();
  }

  @Override
  public void changed() {
    runOnUiThread(
        () -> {
          if (alive && controller != null) controller.refresh();
        });
  }

  private void launchPhotoPickerForAlbum(String albumId) {
    pendingImportAlbumId = albumId == null ? "" : albumId;
    Intent i =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("image/*")
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    startActivityForResult(i, REQ_PICK_ALBUM_PHOTOS);
  }

  @Override
  protected void onActivityResult(int req, int result, Intent data) {
    super.onActivityResult(req, result, data);
    if (req == REQ_PICK_ALBUM_PHOTOS && result == RESULT_OK && data != null) {
      importPhotosIntoAlbum(this, a, data, pendingImportAlbumId, () -> {
        if (controller != null) controller.refresh();
      });
    }
  }

  public static void importPhotosIntoAlbum(
      Activity host, NuniApp a, Intent data, String albumId, Runnable onComplete) {
    if (data == null || albumId == null || albumId.isEmpty() || Album.isAllPhotos(albumId)) {
      return;
    }
    List<Uri> list = new ArrayList<>();
    if (data.getClipData() != null) {
      for (int i = 0; i < data.getClipData().getItemCount(); i++) {
        list.add(data.getClipData().getItemAt(i).getUri());
      }
    } else if (data.getData() != null) {
      list.add(data.getData());
    }
    runAlbumUriImport(host, a, list, albumId, onComplete);
  }

  private static void runAlbumUriImport(
      Activity host, NuniApp a, List<Uri> list, String albumId, Runnable onComplete) {
    if (list.isEmpty()) return;
    TextView progress = Ui.text(host, "앨범에 사진을 준비하고 있어요…", 16, Ui.INK);
    progress.setPadding(Ui.dp(host, 22), Ui.dp(host, 18), Ui.dp(host, 22), Ui.dp(host, 18));
    AlertDialog dlg =
        new AlertDialog.Builder(host)
            .setTitle("앨범에 사진 추가 중")
            .setView(progress)
            .setCancelable(false)
            .create();
    dlg.show();
    a.imports.execute(
        () -> {
          List<String> importedIds = new ArrayList<>();
          List<Uri> failed = new ArrayList<>();
          String lastErr = "";
          for (int i = 0; i < list.size(); i++) {
            Uri u = list.get(i);
            try {
              Photo p = a.store.fromUri(u, queryDisplayName(host, u), true);
              if (p != null) importedIds.add(p.id);
            } catch (Exception e) {
              failed.add(u);
              lastErr = e.getMessage() == null ? "가져오기 실패" : e.getMessage();
            }
            int done = importedIds.size() + failed.size();
            a.main.post(
                () -> {
                  if (Ui.alive(host)) progress.setText(done + " / " + list.size() + "장 처리 중");
                });
          }
          int linkedCount = 0;
          String albumName = "선택한 앨범";
          try {
            Album target = a.studio.dao().album(albumId);
            if (target != null) albumName = target.name;
            linkedCount = a.studio.addPhotosToAlbum(albumId, importedIds);
          } catch (Exception e) {
            lastErr = e.getMessage() == null ? lastErr : e.getMessage();
          }
          int finalLinked = linkedCount;
          int finalImported = importedIds.size();
          String finalAlbumName = albumName;
          String errText = lastErr;
          a.main.post(
              () -> {
                if (!Ui.alive(host)) return;
                dlg.dismiss();
                if (onComplete != null) onComplete.run();
                if (failed.isEmpty()) {
                  Ui.toast(
                      host,
                      "‘"
                          + finalAlbumName
                          + "’ 앨범에 "
                          + finalLinked
                          + "장 반영 완료 (가져온 사진 "
                          + finalImported
                          + "장).");
                } else {
                  new AlertDialog.Builder(host)
                      .setTitle("일부 사진을 추가하지 못했어요")
                      .setMessage(
                          "성공: "
                              + finalImported
                              + "장 · 실패: "
                              + failed.size()
                              + "장\n사유: "
                              + errText)
                      .setPositiveButton(
                          "실패한 " + failed.size() + "장 재시도",
                          (d, w) -> runAlbumUriImport(host, a, failed, albumId, onComplete))
                      .setNegativeButton("닫기", null)
                      .show();
                }
              });
        });
  }

  private static String queryDisplayName(Context c, Uri u) {
    try (Cursor cur =
        c.getContentResolver()
            .query(u, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (cur != null && cur.moveToFirst()) return cur.getString(0);
    } catch (Exception ignored) {
    }
    return "사진";
  }

  public interface DevicePhotoPicker {
    void pickForAlbum(String albumId);
  }

  public static Controller buildView(
      LinearLayout container, Activity host, DevicePhotoPicker picker, String initialAlbumId) {
    Controller c = new Controller(host, picker, initialAlbumId);
    c.attach(container);
    return c;
  }

  public static final class Controller {
    private static final String[] SORT_VALUES = {
      "CUSTOM", "CAPTURED_DESC", "CAPTURED_ASC", "NAME_ASC"
    };
    private static final String[] SORT_LABELS = {
      "지정한 순서대로", "최근 촬영일 순", "오래된 촬영일 순", "이름 순"
    };

    private final Activity host;
    private final NuniApp a;
    private final DevicePhotoPicker devicePicker;
    private LinearLayout rootContainer;
    private String currentAlbumId;
    private boolean selectionMode;
    private final Set<String> selectedPhotoIds = new LinkedHashSet<>();
    private List<Album> albums = new ArrayList<>();
    private Map<String, Integer> albumCounts = new HashMap<>();
    private List<Photo> currentPhotos = new ArrayList<>();
    private GridAdapter gridAdapter;
    private TextView photosHeadingView, emptyPhotosView, batchInfoView;
    private LinearLayout albumListContainer, editorContainer, batchBar;
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> cache =
        new LruCache<String, Bitmap>(4 * 1024 * 1024) {
          @Override
          protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
          }
        };

    public Controller(Activity h, DevicePhotoPicker picker, String initialAlbumId) {
      host = h;
      a = NuniApp.of(h);
      devicePicker = picker;
      currentAlbumId =
          (initialAlbumId == null || initialAlbumId.isEmpty())
              ? Album.ALL_PHOTOS_ID
              : initialAlbumId;
    }

    public void selectAlbum(String albumId) {
      if (albumId != null && !albumId.isEmpty()) {
        currentAlbumId = albumId;
        selectedPhotoIds.clear();
        refresh();
      }
    }

    public void release() {
      images.shutdownNow();
      cache.evictAll();
    }

    public void attach(LinearLayout parent) {
      rootContainer = parent;
      buildLayout();
      refresh();
    }

    private void buildLayout() {
      if (rootContainer == null || !Ui.alive(host)) return;
      rootContainer.removeAllViews();
      int widthDp = host.getResources().getConfiguration().screenWidthDp;
      boolean twoPane = widthDp >= 840;

      Ui.header(
          host,
          rootContainer,
          "앨범 및 사진 관리",
          "PC 스튜디오와 동일하게 앨범을 만들고 정렬·전환 간격·대표 사진·수록 사진을 관리합니다.");
      Ui.gap(rootContainer, 12);

      if (twoPane) {
        LinearLayout split = Ui.row(host);
        split.setGravity(Gravity.TOP);
        rootContainer.addView(split, new LinearLayout.LayoutParams(-1, 0, 1));

        ScrollView leftScroll = new ScrollView(host);
        LinearLayout leftCol = Ui.column(host);
        leftScroll.addView(leftCol);
        LinearLayout.LayoutParams lpLeft = new LinearLayout.LayoutParams(0, -1, 4);
        lpLeft.setMarginEnd(Ui.dp(host, 16));
        split.addView(leftScroll, lpLeft);

        albumListContainer = Ui.column(host);
        leftCol.addView(albumListContainer);
        editorContainer = Ui.column(host);
        leftCol.addView(editorContainer);

        LinearLayout rightCol = Ui.column(host);
        split.addView(rightCol, new LinearLayout.LayoutParams(0, -1, 6));
        buildPhotosPane(rightCol, widthDp);
      } else {
        LinearLayout mainCol = Ui.column(host);
        rootContainer.addView(mainCol, new LinearLayout.LayoutParams(-1, 0, 1));

        albumListContainer = Ui.column(host);
        mainCol.addView(albumListContainer);
        editorContainer = null;
        buildPhotosPane(mainCol, widthDp);
      }
    }

    private void buildPhotosPane(LinearLayout container, int widthDp) {
      LinearLayout card = Ui.column(host);
      card.setPadding(Ui.dp(host, 16), Ui.dp(host, 16), Ui.dp(host, 16), Ui.dp(host, 16));
      card.setBackground(Ui.strokeBg(Ui.SURFACE, Ui.OUTLINE, Ui.dp(host, 1), Ui.dp(host, 18)));
      container.addView(card, new LinearLayout.LayoutParams(-1, 0, 1));

      LinearLayout headRow = Ui.row(host);
      photosHeadingView = Ui.title(host, "앨범 사진", 18);
      headRow.addView(photosHeadingView, new LinearLayout.LayoutParams(0, -2, 1));
      Button selectBtn =
          Ui.button(
              host,
              "선택",
              false,
              R.drawable.ic_check,
              () -> {
                selectionMode = !selectionMode;
                if (!selectionMode) selectedPhotoIds.clear();
                renderBatchBar();
                if (gridAdapter != null) gridAdapter.notifyDataSetChanged();
              });
      selectBtn.setTextSize(13.5f);
      headRow.addView(selectBtn, new LinearLayout.LayoutParams(-2, Ui.dp(host, 42)));
      card.addView(headRow);
      Ui.gap(card, 8);

      HorizontalScrollView actionScroll = new HorizontalScrollView(host);
      actionScroll.setHorizontalScrollBarEnabled(false);
      LinearLayout actionRow = Ui.row(host);
      actionScroll.addView(actionRow);

      Button addFromLibBtn =
          Ui.button(
              host,
              "보관함 사진 담기",
              true,
              R.drawable.ic_plus,
              this::showPickFromLibraryDialog);
      addFromLibBtn.setTextSize(13f);
      LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(-2, Ui.dp(host, 42));
      p1.setMarginEnd(Ui.dp(host, 6));
      actionRow.addView(addFromLibBtn, p1);

      if (devicePicker != null) {
        Button uploadBtn =
            Ui.button(
                host,
                "기기에서 새 사진 추가",
                false,
                R.drawable.ic_upload,
                () -> {
                  if (Album.isAllPhotos(currentAlbumId)) {
                    Ui.toast(
                        host,
                        "‘모든 사진’은 전체 사진을 자동 표시합니다. 먼저 다른 앨범을 선택하거나 새 앨범을 만들어 주세요.");
                    return;
                  }
                  devicePicker.pickForAlbum(currentAlbumId);
                });
        uploadBtn.setTextSize(13f);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(-2, Ui.dp(host, 42));
        p2.setMarginEnd(Ui.dp(host, 6));
        actionRow.addView(uploadBtn, p2);
      }

      if (editorContainer == null) {
        Button editMetaBtn =
            Ui.button(
                host,
                "앨범 설정·편집",
                false,
                R.drawable.ic_tune,
                this::showAlbumSettingsDialog);
        editMetaBtn.setTextSize(13f);
        actionRow.addView(editMetaBtn, new LinearLayout.LayoutParams(-2, Ui.dp(host, 42)));
      }
      card.addView(actionScroll);

      batchBar = Ui.column(host);
      batchBar.setVisibility(View.GONE);
      card.addView(batchBar);
      Ui.gap(card, 8);

      GridView grid = new GridView(host);
      int cols = widthDp >= 1100 ? 4 : widthDp >= 600 ? 3 : 2;
      grid.setNumColumns(cols);
      grid.setHorizontalSpacing(Ui.dp(host, 10));
      grid.setVerticalSpacing(Ui.dp(host, 10));
      grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
      grid.setSelector(android.R.color.transparent);
      gridAdapter = new GridAdapter();
      grid.setAdapter(gridAdapter);
      grid.setOnItemClickListener(
          (p, v, pos, id) -> {
            if (pos < 0 || pos >= currentPhotos.size()) return;
            Photo item = currentPhotos.get(pos);
            if (selectionMode) {
              if (!selectedPhotoIds.remove(item.id)) selectedPhotoIds.add(item.id);
              renderBatchBar();
              gridAdapter.notifyDataSetChanged();
            } else {
              showPhotoActionDialog(item, pos);
            }
          });
      card.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
      emptyPhotosView = Ui.text(host, "이 앨범에 담긴 사진이 없습니다.", 14.5f, Ui.MUTED);
      emptyPhotosView.setPadding(Ui.dp(host, 8), Ui.dp(host, 18), Ui.dp(host, 8), Ui.dp(host, 18));
      card.addView(emptyPhotosView);
      grid.setEmptyView(emptyPhotosView);
    }

    public void refresh() {
      if (!Ui.alive(host)) return;
      final String reqAlbumId = currentAlbumId;
      a.io.execute(
          () -> {
            a.studio.ensureSeeded();
            StudioDao sdao = a.studio.dao();
            List<Album> allAlbums = sdao.albums();
            Map<String, Integer> counts = new HashMap<>();
            Album matched = null;
            for (Album al : allAlbums) {
              counts.put(al.id, sdao.albumPhotoCount(al.id));
              if (al.id.equals(reqAlbumId)) matched = al;
            }
            if (matched == null && !allAlbums.isEmpty()) {
              matched = allAlbums.get(0);
            }
            String resolvedId = matched != null ? matched.id : Album.ALL_PHOTOS_ID;
            List<Photo> photos =
                Album.isAllPhotos(resolvedId)
                    ? a.db.photos().active()
                    : sdao.albumPhotos(resolvedId);
            if (matched != null && !Album.isAllPhotos(resolvedId)) {
              if ("CAPTURED_DESC".equals(matched.sortOrder)) {
                Collections.sort(photos, (x, y) -> Long.compare(y.capturedAt, x.capturedAt));
              } else if ("CAPTURED_ASC".equals(matched.sortOrder)) {
                Collections.sort(photos, (x, y) -> Long.compare(x.capturedAt, y.capturedAt));
              } else if ("NAME_ASC".equals(matched.sortOrder)) {
                Collections.sort(photos, (x, y) -> x.name.compareToIgnoreCase(y.name));
              }
            }
            Album finalAlbum = matched;
            a.main.post(
                () -> {
                  if (!Ui.alive(host)) return;
                  albums = allAlbums;
                  albumCounts = counts;
                  currentAlbumId = resolvedId;
                  currentPhotos = photos;
                  Set<String> validIds = new HashSet<>();
                  for (Photo p : photos) validIds.add(p.id);
                  selectedPhotoIds.retainAll(validIds);
                  renderAlbumSelector(finalAlbum);
                  if (editorContainer != null) {
                    renderEditorCard(editorContainer, finalAlbum, null);
                  }
                  renderPhotosState(finalAlbum);
                  renderBatchBar();
                  if (gridAdapter != null) gridAdapter.notifyDataSetChanged();
                });
          });
    }

    private void renderAlbumSelector(Album activeAlbum) {
      if (albumListContainer == null) return;
      albumListContainer.removeAllViews();
      boolean compact = editorContainer == null;
      LinearLayout card = Ui.card(albumListContainer);

      LinearLayout top = Ui.row(host);
      TextView t = Ui.title(host, "앨범 목록 (" + albums.size() + "개)", 17);
      top.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
      Button newBtn =
          Ui.button(
              host,
              "새 앨범",
              true,
              R.drawable.ic_plus,
              this::showCreateAlbumDialog);
      newBtn.setTextSize(13f);
      top.addView(newBtn, new LinearLayout.LayoutParams(-2, Ui.dp(host, 40)));
      card.addView(top);
      Ui.gap(card, 8);

      if (compact) {
        HorizontalScrollView hsv = new HorizontalScrollView(host);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = Ui.row(host);
        hsv.addView(chipRow);
        for (Album al : albums) {
          boolean sel = al.id.equals(currentAlbumId);
          int cnt = albumCounts.containsKey(al.id) ? albumCounts.get(al.id) : 0;
          String label =
              (Album.isAllPhotos(al.id) ? "★ 모든 사진" : al.name) + " (" + cnt + ")";
          Button chip =
              Ui.button(
                  host,
                  label,
                  sel,
                  () -> {
                    currentAlbumId = al.id;
                    selectedPhotoIds.clear();
                    refresh();
                  });
          chip.setTextSize(13f);
          LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(host, 40));
          lp.setMarginEnd(Ui.dp(host, 6));
          chipRow.addView(chip, lp);
        }
        card.addView(hsv);
        if (activeAlbum != null) {
          Ui.gap(card, 6);
          String sub =
              Album.isAllPhotos(activeAlbum.id)
                  ? "기본 보기 · 모든 보관 사진을 자동으로 표시합니다."
                  : String.format(
                      Locale.KOREA,
                      "%s · 정렬: %s · %d초 · 셔플 %s",
                      activeAlbum.description.isEmpty() ? "사용자 앨범" : activeAlbum.description,
                      sortLabel(activeAlbum.sortOrder),
                      activeAlbum.seconds,
                      activeAlbum.shuffle ? "켜짐" : "꺼짐");
          card.addView(Ui.text(host, sub, 12.5f, Ui.MUTED));
        }
      } else {
        for (int i = 0; i < albums.size(); i++) {
          Album al = albums.get(i);
          boolean sel = al.id.equals(currentAlbumId);
          int cnt = albumCounts.containsKey(al.id) ? albumCounts.get(al.id) : 0;
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
                currentAlbumId = al.id;
                selectedPhotoIds.clear();
                refresh();
              });
          LinearLayout info = Ui.column(host);
          String titleStr =
              (Album.isAllPhotos(al.id) ? "★ " + Album.ALL_PHOTOS_NAME : al.name)
                  + " ("
                  + cnt
                  + "장)";
          TextView tvName = Ui.text(host, titleStr, 15, sel ? Ui.PRIMARY : Ui.INK);
          tvName.setTypeface(Typeface.DEFAULT_BOLD);
          info.addView(tvName);
          String meta =
              Album.isAllPhotos(al.id)
                  ? "기본 전체 보기"
                  : (sortLabel(al.sortOrder)
                      + " · "
                      + al.seconds
                      + "초 · "
                      + (al.shuffle ? "셔플" : "순차")
                      + (al.description.isEmpty() ? "" : " · " + al.description));
          TextView tvMeta = Ui.text(host, meta, 12.5f, Ui.MUTED);
          tvMeta.setMaxLines(1);
          tvMeta.setEllipsize(TextUtils.TruncateAt.END);
          info.addView(tvMeta);
          row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
          LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
          if (i < albums.size() - 1) rp.bottomMargin = Ui.dp(host, 6);
          card.addView(row, rp);
        }
      }
    }

    private void renderPhotosState(Album activeAlbum) {
      String name =
          activeAlbum == null
              ? Album.ALL_PHOTOS_NAME
              : (Album.isAllPhotos(activeAlbum.id) ? Album.ALL_PHOTOS_NAME : activeAlbum.name);
      if (photosHeadingView != null) {
        photosHeadingView.setText(name + " · " + currentPhotos.size() + "장");
      }
      if (emptyPhotosView != null) {
        if (Album.isAllPhotos(currentAlbumId)) {
          emptyPhotosView.setText(
              "보관된 사진이 없습니다. 사진 탭이나 QR 전송으로 사진을 추가한 뒤 앨범별로 분류해 보세요.");
        } else {
          emptyPhotosView.setText(
              "‘"
                  + name
                  + "’ 앨범이 비어 있습니다. 위 ‘보관함 사진 담기’ 또는 ‘기기에서 새 사진 추가’ 버튼으로 사진을 담아 보세요.");
        }
      }
    }

    private void renderBatchBar() {
      if (batchBar == null) return;
      batchBar.removeAllViews();
      if (!selectionMode) {
        batchBar.setVisibility(View.GONE);
        return;
      }
      batchBar.setVisibility(View.VISIBLE);
      LinearLayout box = Ui.column(host);
      box.setPadding(Ui.dp(host, 12), Ui.dp(host, 10), Ui.dp(host, 12), Ui.dp(host, 10));
      box.setBackground(Ui.strokeBg(Ui.SURFACE_SUBTLE, Ui.OUTLINE, Ui.dp(host, 1), Ui.dp(host, 12)));
      LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
      bp.topMargin = Ui.dp(host, 8);
      batchBar.addView(box, bp);

      int n = selectedPhotoIds.size();
      boolean isAll = Album.isAllPhotos(currentAlbumId);
      LinearLayout top = Ui.row(host);
      batchInfoView = Ui.text(host, n + "장 선택됨", 14, Ui.INK);
      batchInfoView.setTypeface(Typeface.DEFAULT_BOLD);
      top.addView(batchInfoView, new LinearLayout.LayoutParams(0, -2, 1));
      Button toggleAllBtn =
          Ui.button(
              host,
              n == currentPhotos.size() && !currentPhotos.isEmpty() ? "전체 해제" : "전체 선택",
              false,
              () -> {
                if (selectedPhotoIds.size() == currentPhotos.size()) {
                  selectedPhotoIds.clear();
                } else {
                  for (Photo p : currentPhotos) selectedPhotoIds.add(p.id);
                }
                renderBatchBar();
                if (gridAdapter != null) gridAdapter.notifyDataSetChanged();
              });
      toggleAllBtn.setTextSize(12.5f);
      top.addView(toggleAllBtn, new LinearLayout.LayoutParams(-2, Ui.dp(host, 38)));
      box.addView(top);
      Ui.gap(box, 6);

      HorizontalScrollView hsv = new HorizontalScrollView(host);
      hsv.setHorizontalScrollBarEnabled(false);
      LinearLayout btns = Ui.row(host);
      hsv.addView(btns);

      if (!isAll) {
        addBatchBtn(
            btns,
            "대표 사진 지정",
            false,
            () -> {
              if (selectedPhotoIds.isEmpty()) {
                Ui.toast(host, "대표 사진으로 지정할 사진을 먼저 선택하세요.");
                return;
              }
              String first = selectedPhotoIds.iterator().next();
              setAlbumCover(first);
            });
      }
      addBatchBtn(
          btns,
          "다른 앨범에 추가",
          false,
          () -> {
            if (selectedPhotoIds.isEmpty()) {
              Ui.toast(host, "사진을 먼저 선택하세요.");
              return;
            }
            showTargetAlbumDialog(new LinkedHashSet<>(selectedPhotoIds), false);
          });
      if (!isAll) {
        addBatchBtn(
            btns,
            "다른 앨범으로 이동",
            false,
            () -> {
              if (selectedPhotoIds.isEmpty()) {
                Ui.toast(host, "이동할 사진을 먼저 선택하세요.");
                return;
              }
              showTargetAlbumDialog(new LinkedHashSet<>(selectedPhotoIds), true);
            });
        addBatchBtn(
            btns,
            "앨범에서 제외",
            false,
            () -> {
              if (selectedPhotoIds.isEmpty()) return;
              Set<String> ids = new LinkedHashSet<>(selectedPhotoIds);
              selectedPhotoIds.clear();
              a.io.execute(
                  () -> {
                    try {
                      a.studio.removePhotosFromAlbum(currentAlbumId, ids);
                      a.main.post(
                          () -> {
                            Ui.toast(host, ids.size() + "장을 현재 앨범에서 제외했습니다 (‘모든 사진’에는 유지됨).");
                            refresh();
                          });
                    } catch (Exception e) {
                      a.main.post(() -> Ui.error(host, e));
                    }
                  });
            });
      }
      addBatchBtn(
          btns,
          "휴지통으로",
          true,
          () -> {
            if (selectedPhotoIds.isEmpty()) return;
            Set<String> ids = new LinkedHashSet<>(selectedPhotoIds);
            new AlertDialog.Builder(host)
                .setTitle("선택한 " + ids.size() + "장을 휴지통으로 옮길까요?")
                .setMessage("사진 보관함의 휴지통 탭에서 언제든 복원할 수 있습니다.")
                .setPositiveButton(
                    "휴지통으로 이동",
                    (d, w) -> {
                      selectedPhotoIds.clear();
                      a.io.execute(
                          () -> {
                            for (String pid : ids) a.store.trash(pid);
                            a.main.post(
                                () -> {
                                  Ui.toast(host, ids.size() + "장을 휴지통으로 옮겼습니다.");
                                  refresh();
                                });
                          });
                    })
                .setNegativeButton("취소", null)
                .show();
          });
      box.addView(hsv);
    }

    private void addBatchBtn(LinearLayout row, String text, boolean danger, Runnable action) {
      Button b =
          danger
              ? Ui.dangerButton(host, text, R.drawable.ic_trash, action)
              : Ui.button(host, text, false, action);
      b.setTextSize(12.5f);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(host, 40));
      lp.setMarginEnd(Ui.dp(host, 6));
      row.addView(b, lp);
    }

    private void renderEditorCard(LinearLayout parent, Album album, Runnable onSavedOrClosed) {
      parent.removeAllViews();
      if (album == null) return;
      boolean isAll = Album.isAllPhotos(album.id);
      LinearLayout card = Ui.card(parent);
      Ui.section(
          card,
          R.drawable.ic_folder,
          isAll ? "모든 사진 (기본 전체 보기)" : ("앨범 상세 설정 · " + album.name));
      if (isAll) {
        card.addView(
            Ui.text(
                host,
                "‘모든 사진’은 누니담에 보관된 전체 사진을 자동으로 모아 보여주는 기본 보기입니다. 이름이나 정렬 설정을 변경하거나 삭제할 수 없으며, 복제하여 새 사용자 앨범을 만들 수 있습니다.",
                13.5f,
                Ui.MUTED));
        Ui.gap(card, 10);
        Ui.add(
            card,
            "현재 사진 구성으로 새 앨범 복제",
            false,
            R.drawable.ic_plus,
            () -> cloneCurrentAlbum(album, onSavedOrClosed));
        return;
      }

      card.addView(Ui.text(host, "앨범 이름", 13.5f, Ui.MUTED));
      EditText nameIn = Ui.input(host, "예: 가족 여행, 아침 풍경", album.name);
      nameIn.setSingleLine();
      card.addView(nameIn);

      Ui.gap(card, 8);
      card.addView(Ui.text(host, "앨범 설명", 13.5f, Ui.MUTED));
      EditText descIn = Ui.input(host, "앨범에 대한 간단한 메모", album.description);
      descIn.setSingleLine();
      card.addView(descIn);

      Ui.gap(card, 8);
      card.addView(Ui.text(host, "재생 정렬 기준", 13.5f, Ui.MUTED));
      Spinner sortSpin = new Spinner(host);
      sortSpin.setAdapter(
          new ArrayAdapter<>(host, android.R.layout.simple_spinner_dropdown_item, SORT_LABELS));
      int sortIdx = 0;
      for (int i = 0; i < SORT_VALUES.length; i++) {
        if (SORT_VALUES[i].equals(album.sortOrder)) sortIdx = i;
      }
      sortSpin.setSelection(sortIdx);
      card.addView(sortSpin, new LinearLayout.LayoutParams(-1, Ui.dp(host, 48)));

      Ui.gap(card, 8);
      card.addView(Ui.text(host, "기본 전환 간격(초, 5~3600)", 13.5f, Ui.MUTED));
      EditText secIn = Ui.input(host, "30", String.valueOf(album.seconds));
      secIn.setInputType(InputType.TYPE_CLASS_NUMBER);
      secIn.setSingleLine();
      card.addView(secIn);

      Ui.gap(card, 8);
      Switch shuffleSw = new Switch(host);
      shuffleSw.setText("앨범 내 사진 무작위(셔플) 재생");
      shuffleSw.setTextColor(Ui.INK);
      shuffleSw.setChecked(album.shuffle);
      card.addView(shuffleSw);

      Ui.gap(card, 12);
      Button saveBtn =
          Ui.button(
              host,
              "앨범 설정 저장",
              true,
              R.drawable.ic_check,
              () -> {
                String n = nameIn.getText().toString().trim();
                String d = descIn.getText().toString().trim();
                String sort = SORT_VALUES[Math.max(0, sortSpin.getSelectedItemPosition())];
                int sec = 30;
                try {
                  sec = Integer.parseInt(secIn.getText().toString().trim());
                } catch (NumberFormatException ignored) {
                }
                int finalSec = sec;
                boolean shuf = shuffleSw.isChecked();
                a.io.execute(
                    () -> {
                      try {
                        a.studio.saveAlbumMetadata(
                            album.id, n, d, album.coverPhotoId, sort, finalSec, shuf);
                        a.main.post(
                            () -> {
                              Ui.toast(host, "앨범 설정을 저장했습니다.");
                              if (onSavedOrClosed != null) onSavedOrClosed.run();
                              refresh();
                            });
                      } catch (Exception e) {
                        a.main.post(() -> Ui.error(host, e));
                      }
                    });
              });
      card.addView(saveBtn, new LinearLayout.LayoutParams(-1, Ui.dp(host, 50)));

      Ui.gap(card, 8);
      LinearLayout subActions = Ui.row(host);
      Button cloneBtn =
          Ui.button(
              host,
              "복제",
              false,
              () -> cloneCurrentAlbum(album, onSavedOrClosed));
      LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, Ui.dp(host, 46), 1);
      cp.setMarginEnd(Ui.dp(host, 8));
      subActions.addView(cloneBtn, cp);
      Button delBtn =
          Ui.dangerButton(
              host,
              "앨범 삭제",
              R.drawable.ic_trash,
              () ->
                  new AlertDialog.Builder(host)
                      .setTitle("‘" + album.name + "’ 앨범을 삭제할까요?")
                      .setMessage("앨범 묶음만 삭제되며, 사진 파일은 ‘모든 사진’ 보관함에 그대로 유지됩니다.")
                      .setPositiveButton(
                          "앨범 삭제",
                          (d, w) ->
                              a.io.execute(
                                  () -> {
                                    try {
                                      a.studio.deleteAlbum(album.id);
                                      currentAlbumId = Album.ALL_PHOTOS_ID;
                                      a.main.post(
                                          () -> {
                                            Ui.toast(host, "앨범을 삭제했습니다.");
                                            if (onSavedOrClosed != null) onSavedOrClosed.run();
                                            refresh();
                                          });
                                    } catch (Exception e) {
                                      a.main.post(() -> Ui.error(host, e));
                                    }
                                  }))
                      .setNegativeButton("취소", null)
                      .show());
      subActions.addView(delBtn, new LinearLayout.LayoutParams(0, Ui.dp(host, 46), 1));
      card.addView(subActions);
    }

    private void showAlbumSettingsDialog() {
      Album cur = findCurrentAlbum();
      if (cur == null) return;
      ScrollView sv = new ScrollView(host);
      LinearLayout box = Ui.column(host);
      box.setPadding(Ui.dp(host, 16), Ui.dp(host, 12), Ui.dp(host, 16), Ui.dp(host, 8));
      sv.addView(box);
      AlertDialog dlg =
          new AlertDialog.Builder(host)
              .setView(sv)
              .setNegativeButton("닫기", null)
              .create();
      renderEditorCard(box, cur, dlg::dismiss);
      dlg.show();
    }

    private void cloneCurrentAlbum(Album src, Runnable after) {
      a.io.execute(
          () -> {
            try {
              Album copy = a.studio.cloneAlbum(src.id, null);
              currentAlbumId = copy.id;
              a.main.post(
                  () -> {
                    Ui.toast(host, "‘" + copy.name + "’ 앨범으로 복제했습니다.");
                    if (after != null) after.run();
                    refresh();
                  });
            } catch (Exception e) {
              a.main.post(() -> Ui.error(host, e));
            }
          });
    }

    private void showCreateAlbumDialog() {
      LinearLayout box = Ui.column(host);
      box.setPadding(Ui.dp(host, 20), Ui.dp(host, 12), Ui.dp(host, 20), Ui.dp(host, 4));
      box.addView(Ui.text(host, "앨범 이름", 13.5f, Ui.MUTED));
      EditText nameIn = Ui.input(host, "예: 가족 여행, 손주 사진, 봄날 풍경", "");
      nameIn.setSingleLine();
      box.addView(nameIn);
      Ui.gap(box, 8);
      box.addView(Ui.text(host, "설명 (선택)", 13.5f, Ui.MUTED));
      EditText descIn = Ui.input(host, "예: 함께 떠난 제주도 여행 사진", "");
      descIn.setSingleLine();
      box.addView(descIn);
      new AlertDialog.Builder(host)
          .setTitle("새 앨범 만들기")
          .setView(box)
          .setPositiveButton(
              "만들기",
              (d, w) -> {
                String n = nameIn.getText().toString().trim();
                String desc = descIn.getText().toString().trim();
                a.io.execute(
                    () -> {
                      try {
                        Album created =
                            a.studio.saveAlbumMetadata("", n, desc, "", "CUSTOM", 30, true);
                        currentAlbumId = created.id;
                        selectedPhotoIds.clear();
                        a.main.post(
                            () -> {
                              Ui.toast(
                                  host,
                                  "‘" + created.name + "’ 앨범을 만들었습니다. 이제 사진을 담아 보세요.");
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

    private void showPickFromLibraryDialog() {
      if (Album.isAllPhotos(currentAlbumId)) {
        new AlertDialog.Builder(host)
            .setTitle("사진을 담을 앨범을 선택해 주세요")
            .setMessage("‘모든 사진’은 보관함 전체를 자동 표시하는 기본 보기입니다. 새 앨범을 만들거나 다른 앨범을 선택한 뒤 사진을 담아 주세요.")
            .setPositiveButton("새 앨범 만들기", (d, w) -> showCreateAlbumDialog())
            .setNegativeButton("닫기", null)
            .show();
        return;
      }
      final String targetAlbumId = currentAlbumId;
      a.io.execute(
          () -> {
            List<Photo> allActive = a.db.photos().active();
            Set<String> alreadyIn = new HashSet<>();
            for (Photo p : a.studio.dao().albumPhotos(targetAlbumId)) alreadyIn.add(p.id);
            List<Photo> candidates = new ArrayList<>();
            for (Photo p : allActive) {
              if (!alreadyIn.contains(p.id)) candidates.add(p);
            }
            a.main.post(
                () -> {
                  if (!Ui.alive(host)) return;
                  if (allActive.isEmpty()) {
                    Ui.toast(host, "보관함에 저장된 사진이 없습니다. 먼저 기기에서 새 사진을 추가해 주세요.");
                    return;
                  }
                  if (candidates.isEmpty()) {
                    Ui.toast(host, "보관함의 모든 사진이 이미 이 앨범에 담겨 있습니다.");
                    return;
                  }
                  String[] labels = new String[candidates.size()];
                  boolean[] checked = new boolean[candidates.size()];
                  for (int i = 0; i < candidates.size(); i++) {
                    Photo p = candidates.get(i);
                    labels[i] =
                        (p.favourite ? "★ " : "")
                            + (p.note.isEmpty() ? p.name : (p.note + " (" + p.name + ")"));
                  }
                  new AlertDialog.Builder(host)
                      .setTitle("보관함에서 앨범에 담을 사진 선택")
                      .setMultiChoiceItems(labels, checked, (d, idx, isChecked) -> checked[idx] = isChecked)
                      .setPositiveButton(
                          "선택 사진 담기",
                          (d, w) -> {
                            List<String> chosen = new ArrayList<>();
                            for (int i = 0; i < candidates.size(); i++) {
                              if (checked[i]) chosen.add(candidates.get(i).id);
                            }
                            if (chosen.isEmpty()) return;
                            a.io.execute(
                                () -> {
                                  try {
                                    int cnt = a.studio.addPhotosToAlbum(targetAlbumId, chosen);
                                    a.main.post(
                                        () -> {
                                          Ui.toast(host, cnt + "장을 앨범에 추가했습니다.");
                                          refresh();
                                        });
                                  } catch (Exception e) {
                                    a.main.post(() -> Ui.error(host, e));
                                  }
                                });
                          })
                      .setNegativeButton("취소", null)
                      .show();
                });
          });
    }

    private void showTargetAlbumDialog(Set<String> photoIds, boolean moveInsteadOfCopy) {
      List<Album> targets = new ArrayList<>();
      for (Album al : albums) {
        if (!Album.isAllPhotos(al.id) && !(moveInsteadOfCopy && al.id.equals(currentAlbumId))) {
          targets.add(al);
        }
      }
      if (targets.isEmpty()) {
        new AlertDialog.Builder(host)
            .setTitle("대상 사용자 앨범이 없습니다")
            .setMessage("사진을 담을 새 사용자 앨범을 먼저 만들어 주세요.")
            .setPositiveButton("새 앨범 만들기", (d, w) -> showCreateAlbumDialog())
            .setNegativeButton("취소", null)
            .show();
        return;
      }
      String[] labels = new String[targets.size()];
      for (int i = 0; i < targets.size(); i++) {
        Album t = targets.get(i);
        int cnt = albumCounts.containsKey(t.id) ? albumCounts.get(t.id) : 0;
        labels[i] = t.name + " (" + cnt + "장)";
      }
      new AlertDialog.Builder(host)
          .setTitle(
              moveInsteadOfCopy
                  ? ("선택한 " + photoIds.size() + "장을 어느 앨범으로 이동할까요?")
                  : ("선택한 " + photoIds.size() + "장을 어느 앨범에 추가할까요?"))
          .setItems(
              labels,
              (d, idx) -> {
                Album target = targets.get(idx);
                String srcId = currentAlbumId;
                selectedPhotoIds.clear();
                a.io.execute(
                    () -> {
                      try {
                        if (moveInsteadOfCopy) {
                          a.studio.movePhotosBetweenAlbums(srcId, target.id, photoIds);
                        } else {
                          a.studio.addPhotosToAlbum(target.id, photoIds);
                        }
                        a.main.post(
                            () -> {
                              Ui.toast(
                                  host,
                                  moveInsteadOfCopy
                                      ? ("‘" + target.name + "’ 앨범으로 이동했습니다.")
                                      : ("‘" + target.name + "’ 앨범에 추가했습니다 (중복 저장 없음)."));
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

    private void setAlbumCover(String photoId) {
      Album cur = findCurrentAlbum();
      if (cur == null || Album.isAllPhotos(cur.id)) return;
      a.io.execute(
          () -> {
            try {
              a.studio.saveAlbumMetadata(
                  cur.id,
                  cur.name,
                  cur.description,
                  photoId,
                  cur.sortOrder,
                  cur.seconds,
                  cur.shuffle);
              a.main.post(
                  () -> {
                    Ui.toast(host, "앨범 대표 사진을 지정했습니다.");
                    refresh();
                  });
            } catch (Exception e) {
              a.main.post(() -> Ui.error(host, e));
            }
          });
    }

    private void showPhotoActionDialog(Photo p, int position) {
      Album cur = findCurrentAlbum();
      boolean isAll = cur == null || Album.isAllPhotos(cur.id);
      ScrollView sv = new ScrollView(host);
      LinearLayout box = Ui.column(host);
      box.setPadding(Ui.dp(host, 20), Ui.dp(host, 14), Ui.dp(host, 20), Ui.dp(host, 10));
      sv.addView(box);

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
      Ui.gap(box, 8);
      box.addView(
          Ui.text(
              host,
              String.format(
                  Locale.KOREA,
                  "%s · %d×%d · %.1f MB",
                  p.note.isEmpty() ? p.name : p.note,
                  p.width,
                  p.height,
                  p.bytes / 1048576d),
              13,
              Ui.MUTED));
      Ui.gap(box, 8);

      AlertDialog dlg =
          new AlertDialog.Builder(host)
              .setTitle(p.name)
              .setView(sv)
              .setNegativeButton("닫기", null)
              .create();

      if (!isAll) {
        Ui.add(
            box,
            "이 앨범의 대표 사진으로 지정",
            true,
            R.drawable.ic_check,
            () -> {
              dlg.dismiss();
              setAlbumCover(p.id);
            });
        if (position > 0) {
          Ui.add(
              box,
              "순서 앞으로 한 칸 이동 (↑)",
              false,
              R.drawable.ic_prev,
              () -> {
                dlg.dismiss();
                reorderPhoto(p.id, -1);
              });
        }
        if (position < currentPhotos.size() - 1) {
          Ui.add(
              box,
              "순서 뒤로 한 칸 이동 (↓)",
              false,
              R.drawable.ic_next,
              () -> {
                dlg.dismiss();
                reorderPhoto(p.id, 1);
              });
        }
      }
      Ui.add(
          box,
          "다른 앨범에 추가",
          false,
          R.drawable.ic_plus,
          () -> {
            dlg.dismiss();
            showTargetAlbumDialog(Collections.singleton(p.id), false);
          });
      if (!isAll) {
        Ui.add(
            box,
            "다른 앨범으로 이동",
            false,
            R.drawable.ic_folder,
            () -> {
              dlg.dismiss();
              showTargetAlbumDialog(Collections.singleton(p.id), true);
            });
        Ui.add(
            box,
            "현재 앨범에서 제외 (‘모든 사진’ 유지)",
            false,
            R.drawable.ic_exit,
            () -> {
              dlg.dismiss();
              a.io.execute(
                  () -> {
                    try {
                      a.studio.removePhotosFromAlbum(currentAlbumId, Collections.singleton(p.id));
                      a.main.post(
                          () -> {
                            Ui.toast(host, "앨범에서 제외했습니다.");
                            refresh();
                          });
                    } catch (Exception e) {
                      a.main.post(() -> Ui.error(host, e));
                    }
                  });
            });
      }
      Ui.add(
          box,
          "휴지통으로 이동",
          false,
          R.drawable.ic_trash,
          () -> {
            dlg.dismiss();
            a.io.execute(
                () -> {
                  a.store.trash(p.id);
                  a.main.post(
                      () -> {
                        Ui.toast(host, "휴지통으로 옮겼습니다.");
                        refresh();
                      });
                });
          });
      dlg.show();
    }

    private void reorderPhoto(String photoId, int delta) {
      String aid = currentAlbumId;
      a.io.execute(
          () -> {
            try {
              a.studio.reorderAlbumPhoto(aid, photoId, delta);
              a.main.post(this::refresh);
            } catch (Exception e) {
              a.main.post(() -> Ui.error(host, e));
            }
          });
    }

    private Album findCurrentAlbum() {
      for (Album al : albums) {
        if (al.id.equals(currentAlbumId)) return al;
      }
      return albums.isEmpty() ? null : albums.get(0);
    }

    private static String sortLabel(String sort) {
      for (int i = 0; i < SORT_VALUES.length; i++) {
        if (SORT_VALUES[i].equals(sort)) return SORT_LABELS[i];
      }
      return "지정한 순서대로";
    }

    private final class GridAdapter extends BaseAdapter {
      @Override
      public int getCount() {
        return currentPhotos.size();
      }

      @Override
      public Object getItem(int i) {
        return currentPhotos.get(i);
      }

      @Override
      public long getItemId(int i) {
        return i;
      }

      @Override
      public View getView(int pos, View old, ViewGroup parent) {
        LinearLayout card;
        ImageView image;
        TextView caption;
        if (old instanceof LinearLayout && ((LinearLayout) old).getChildCount() >= 2) {
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
          card.addView(image, new LinearLayout.LayoutParams(-1, Ui.dp(host, 140)));
          caption = Ui.text(host, "", 13f, Ui.INK);
          caption.setPadding(Ui.dp(host, 4), Ui.dp(host, 6), Ui.dp(host, 4), 0);
          caption.setMaxLines(2);
          caption.setEllipsize(TextUtils.TruncateAt.END);
          card.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        }
        Photo p = currentPhotos.get(pos);
        Album cur = findCurrentAlbum();
        boolean isCover =
            cur != null && !Album.isAllPhotos(cur.id) && p.id.equals(cur.coverPhotoId);
        boolean sel = selectedPhotoIds.contains(p.id);
        card.setBackground(
            Ui.strokeBg(
                sel ? Ui.SURFACE_SUBTLE : Ui.SURFACE,
                sel ? Ui.PRIMARY : (isCover ? Ui.PRIMARY : Ui.OUTLINE),
                Ui.dp(host, sel || isCover ? 2 : 1),
                Ui.dp(host, 16)));
        String badge =
            (sel ? "[선택됨] " : "")
                + (isCover ? "[대표] " : "#" + (pos + 1) + " ")
                + (p.favourite ? "★ " : "");
        String label = badge + (p.note.isEmpty() ? p.name : p.note);
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
                        if (Ui.alive(host) && p.id.equals(image.getTag())) {
                          image.setImageBitmap(loaded);
                        }
                      });
                }
              });
        }
        return card;
      }
    }
  }
}
