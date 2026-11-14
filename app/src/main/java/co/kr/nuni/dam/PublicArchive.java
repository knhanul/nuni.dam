package co.kr.nuni.dam;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 앱이 소유한 사진의 공개 사본을 Pictures/누니담에 두고, 설정 백업 JSON을 Documents/누니담에 남긴다.
 * 앱을 지웠다가 다시 설치해도 사진을 되살릴 수 있게 한다.
 */
public final class PublicArchive {
  public static final String FOLDER = "누니담";
  public static final String IMAGE_PATH = "Pictures/누니담/";
  public static final String DOC_PATH = "Documents/누니담/";
  public static final String BACKUP_HIDDEN = ".nunidam_backup.json";
  public static final String BACKUP_VISIBLE = "nunidam_backup.json";
  public static final int REQ_READ = 71;
  public static final int REQ_WRITE = 72;
  public static final int REQ_DELETE = 74;

  static final String PREF_KEEP = "keepPublicCopies";
  static final String PREF_MIGRATED = "publicCopiesMigrated";
  static final String PREF_OFFER = "publicRestoreOffer";
  static final String PREF_OFFER_COUNT = "publicRestoreCount";
  static final String PREF_MIG_DONE = "publicCopyMigratedCount";
  static final String PREF_MIG_TOTAL = "publicCopyMigrateTotal";
  static final String PREF_PENDING_DELETES = "publicPendingDeletes";

  private PublicArchive() {}

  public static final class Stats {
    public final int count;
    public final long bytes;

    Stats(int count, long bytes) {
      this.count = count;
      this.bytes = bytes;
    }

    public String summary() {
      if (count <= 0) return "사진 폴더가 비어 있습니다";
      return String.format(Locale.KOREA, "사진 폴더 %d장 · %s", count, sizeText(bytes));
    }
  }

  public static boolean keep(Prefs prefs) {
    return prefs.raw.getBoolean(PREF_KEEP, true);
  }

  public static String displayName(String photoId) {
    return "nunidam_" + photoId + ".jpg";
  }

  /** 파일 이름에서 64자리 SHA-256 식별자를 꺼낸다. 짧은 이름이면 빈 문자열. */
  public static String photoIdFromName(String name) {
    if (name == null) return "";
    String n = name.trim();
    int dot = n.lastIndexOf('.');
    if (dot > 0) n = n.substring(0, dot);
    if (n.regionMatches(true, 0, "nunidam_", 0, 8)) n = n.substring(8);
    if (n.matches("[a-fA-F0-9]{64}")) return n.toLowerCase(Locale.ROOT);
    return "";
  }

  public static boolean looksLikeArchiveName(String name) {
    if (name == null) return false;
    String n = name.trim();
    if (BACKUP_HIDDEN.equals(n) || BACKUP_VISIBLE.equals(n)) return false;
    String lower = n.toLowerCase(Locale.ROOT);
    return lower.startsWith("nunidam_")
        && (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png"));
  }

  public static String sizeText(long bytes) {
    if (bytes < 1024) return bytes + "B";
    if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
    return String.format(Locale.KOREA, "%.1fMB", bytes / (1024.0 * 1024.0));
  }

  public static String[] readPermissions() {
    if (Build.VERSION.SDK_INT >= 33) {
      return new String[] {"android.permission.READ_MEDIA_IMAGES"};
    }
    return new String[] {"android.permission.READ_EXTERNAL_STORAGE"};
  }

  public static String[] writePermissions() {
    if (Build.VERSION.SDK_INT >= 29) return new String[0];
    return new String[] {"android.permission.WRITE_EXTERNAL_STORAGE"};
  }

  public static boolean hasReadPermission(Context c) {
    for (String p : readPermissions()) {
      if (ContextCompat.checkSelfPermission(c, p) != PackageManager.PERMISSION_GRANTED) {
        return false;
      }
    }
    return true;
  }

  public static boolean hasWritePermission(Context c) {
    for (String p : writePermissions()) {
      if (ContextCompat.checkSelfPermission(c, p) != PackageManager.PERMISSION_GRANTED) {
        return false;
      }
    }
    return true;
  }

  public static void onAppStart(NuniApp a) {
    detectRestoreOffer(a);
    if (!keep(a.prefs)) return;
    if (!a.prefs.raw.getBoolean(PREF_MIGRATED, false) || needsCopy(a)) {
      PublicCopyWorker.scheduleMigration(a);
    }
    PublicCopyWorker.scheduleBackup(a);
  }

  static boolean needsCopy(NuniApp a) {
    try {
      for (Photo p : a.db.photos().owned()) {
        if (p == null) continue;
        if (!a.store.file(p.id).isFile() && !a.store.originalFile(p.id).isFile()) continue;
        if (p.publicUri == null || p.publicUri.isEmpty()) return true;
      }
    } catch (Exception ignored) {
    }
    return false;
  }

  static void detectRestoreOffer(NuniApp a) {
    try {
      String state = a.prefs.raw.getString(PREF_OFFER, "");
      if ("declined".equals(state) || "done".equals(state)) return;
      if (a.db.photos().count() > 0) return;
      int n = 0;
      if (hasReadPermission(a)) n = countPublicPhotos(a);
      a.prefs.raw.edit().putString(PREF_OFFER, "pending").putInt(PREF_OFFER_COUNT, n).apply();
    } catch (Exception ignored) {
    }
  }

  public static boolean hasPendingRestoreOffer(Context c) {
    return "pending".equals(NuniApp.of(c).prefs.raw.getString(PREF_OFFER, ""));
  }

  public static int pendingRestoreCount(Context c) {
    return NuniApp.of(c).prefs.raw.getInt(PREF_OFFER_COUNT, 0);
  }

  public static void markOfferDeclined(Context c) {
    NuniApp.of(c).prefs.raw.edit().putString(PREF_OFFER, "declined").apply();
  }

  public static void markOfferDone(Context c) {
    NuniApp.of(c).prefs.raw.edit().putString(PREF_OFFER, "done").apply();
  }

  public static void refreshOfferCount(Context c) {
    if (!hasReadPermission(c)) return;
    int n = countPublicPhotos(c);
    NuniApp.of(c).prefs.raw.edit().putInt(PREF_OFFER_COUNT, n).apply();
  }

  public static Uri ensureCopy(NuniApp a, Photo p) {
    File src = p == null ? null : sourceFile(a.store, p.id);
    return copyFromFile(a, p, src);
  }

  public static Uri copyFromFile(NuniApp a, Photo p, File src) {
    if (p == null || !p.owned || !keep(a.prefs) || src == null || !src.isFile()) return null;
    if (!hasWritePermission(a)) return null;
    try {
      Uri uri = findExisting(a, p.id);
      if (uri == null) uri = insertCopy(a, p.id, src);
      if (uri != null) {
        String s = uri.toString();
        if (!s.equals(p.publicUri)) {
          p.publicUri = s;
          a.db.photos().setPublicUri(p.id, s);
        }
      }
      return uri;
    } catch (Exception ignored) {
      return null;
    }
  }

  public static Uri deleteCopy(NuniApp a, Photo p) {
    if (p == null) return null;
    Uri uri = null;
    if (p.publicUri != null && !p.publicUri.isEmpty()) {
      try {
        uri = Uri.parse(p.publicUri);
      } catch (Exception ignored) {
      }
    }
    if (uri == null) uri = findExisting(a, p.id);
    if (uri == null) return null;
    Uri pending = tryDelete(a, uri);
    if (pending != null) addPendingDelete(a, pending);
    return pending;
  }

  public static List<Uri> emptyFolder(Context c) {
    List<Uri> needConfirm = new ArrayList<>();
    for (Found f : listPublic(c, true)) {
      Uri pending = tryDelete(c, f.uri);
      if (pending != null) needConfirm.add(pending);
    }
    for (Uri u : needConfirm) addPendingDelete(c, u);
    return needConfirm;
  }

  public static void removeAllCopies(NuniApp a) {
    List<Uri> need = new ArrayList<>();
    try {
      for (Photo p : a.db.photos().owned()) {
        Uri u = deleteCopy(a, p);
        if (u != null) need.add(u);
        p.publicUri = "";
        try {
          a.db.photos().setPublicUri(p.id, "");
        } catch (Exception ignored) {
        }
      }
    } catch (Exception ignored) {
    }
    need.addAll(emptyFolder(a));
  }

  public static Stats stats(Context c) {
    int n = 0;
    long bytes = 0;
    for (Found f : listPublic(c, false)) {
      n++;
      bytes += f.bytes;
    }
    return new Stats(n, bytes);
  }

  public static int countPublicPhotos(Context c) {
    return listPublic(c, false).size();
  }

  public static String migrationStatus(Prefs prefs) {
    int total = prefs.raw.getInt(PREF_MIG_TOTAL, 0);
    int done = prefs.raw.getInt(PREF_MIG_DONE, 0);
    if (total <= 0) return "";
    if (done >= total && prefs.raw.getBoolean(PREF_MIGRATED, false)) {
      return "기존 사진을 사진 폴더에 복사해 두었습니다.";
    }
    return String.format(Locale.KOREA, "사진 폴더로 복사 중 %d/%d장", done, total);
  }

  public static void requestRead(Activity a, Runnable onAlreadyGranted) {
    if (hasReadPermission(a)) {
      if (onAlreadyGranted != null) onAlreadyGranted.run();
      return;
    }
    new android.app.AlertDialog.Builder(a)
        .setTitle("사진 폴더 접근")
        .setMessage(
            "앱을 다시 설치한 뒤에도 사진을 되살리려면, 기기의 사진/누니담 폴더를 읽을 권한이 필요합니다. 다른 사진은 사용하지 않습니다.")
        .setPositiveButton(
            "허용",
            (d, w) -> a.requestPermissions(readPermissions(), REQ_READ))
        .setNegativeButton("취소", null)
        .show();
  }

  public static void requestWrite(Activity a, Runnable onAlreadyGranted) {
    if (hasWritePermission(a)) {
      if (onAlreadyGranted != null) onAlreadyGranted.run();
      return;
    }
    if (writePermissions().length == 0) {
      if (onAlreadyGranted != null) onAlreadyGranted.run();
      return;
    }
    new android.app.AlertDialog.Builder(a)
        .setTitle("사진 저장 권한")
        .setMessage("사진/누니담 폴더에 보관본을 두기 위해 저장 권한이 필요합니다.")
        .setPositiveButton(
            "허용", (d, w) -> a.requestPermissions(writePermissions(), REQ_WRITE))
        .setNegativeButton("취소", null)
        .show();
  }

  public static void launchPendingDeletes(Activity a) {
    if (!Ui.alive(a)) return;
    List<Uri> pending = pendingDeletes(a);
    if (pending.isEmpty()) return;
    try {
      if (Build.VERSION.SDK_INT >= 30) {
        PendingIntent pi =
            MediaStore.createDeleteRequest(a.getContentResolver(), pending);
        a.startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
      } else if (Build.VERSION.SDK_INT == 29) {
        try {
          a.getContentResolver().delete(pending.get(0), null, null);
          removePendingDelete(a, pending.get(0));
          launchPendingDeletes(a);
        } catch (RecoverableSecurityException e) {
          PendingIntent pi = e.getUserAction().getActionIntent();
          a.startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
        }
      }
    } catch (IntentSender.SendIntentException ignored) {
    } catch (Exception ignored) {
    }
  }

  public static void onDeleteResult(Context c, int req, int result) {
    if (req != REQ_DELETE) return;
    if (result == Activity.RESULT_OK) {
      clearPendingDeletes(c);
    }
  }

  public static File sourceFile(PhotoStore store, String id) {
    try {
      File orig = store.originalFile(id);
      if (orig.isFile() && orig.length() > 0) return orig;
      File f = store.file(id);
      if (f.isFile() && f.length() > 0) return f;
    } catch (Exception ignored) {
    }
    return null;
  }

  public static int restoreAll(NuniApp a) throws Exception {
    List<Found> files = listPublic(a, false);
    int imported = 0;
    JSONObject backup = readBackup(a);
    JSONObject photoMeta = indexPhotoMeta(backup);
    for (Found f : files) {
      try {
        if (restoreOne(a, f, photoMeta) != null) imported++;
      } catch (Exception ignored) {
      }
    }
    if (backup != null) applyBackupMetadata(a, backup);
    markOfferDone(a);
    PublicCopyWorker.scheduleBackup(a);
    a.store.changed();
    return imported;
  }

  /** 파일 이름이 기존 식별자면 그 ID로 되돌리고, 원본 바이트면 해시로 가져온다. */
  static Photo restoreOne(NuniApp a, Found f, JSONObject photoMeta) throws Exception {
    File tmp = File.createTempFile("restore-", ".jpg", a.getCacheDir());
    try {
      try (InputStream in = a.getContentResolver().openInputStream(f.uri);
          OutputStream out = new FileOutputStream(tmp)) {
        if (in == null) return null;
        Core.copy(in, out, Core.MAX_INPUT);
      }
      String hash = sha256(tmp);
      String named = photoIdFromName(f.name);
      String hint = f.name == null || f.name.isEmpty() ? "사진" : f.name;
      if (named.isEmpty() || named.equals(hash)) {
        try (InputStream in = new FileInputStream(tmp)) {
          PhotoStore.ImportResult r = a.store.importStreamResult(in, hint, true, true);
          if (r == null || r.photo == null) return null;
          applyPhotoMeta(a, r.photo, photoMeta.optJSONObject(r.photo.id));
          return r.photo;
        }
      }
      Photo p = restoreProcessedAsId(a, named, tmp, hint);
      if (p != null) applyPhotoMeta(a, p, photoMeta.optJSONObject(p.id));
      return p;
    } finally {
      //noinspection ResultOfMethodCallIgnored
      tmp.delete();
    }
  }

  private static Photo restoreProcessedAsId(NuniApp a, String id, File src, String name)
      throws Exception {
    if (id == null || !id.matches("[a-f0-9]{64}")) return null;
    Photo existing = a.db.photos().get(id);
    File dest = a.store.file(id);
    if (!dest.isFile()) {
      try (InputStream in = new FileInputStream(src);
          OutputStream out = new FileOutputStream(dest)) {
        Core.copy(in, out, Core.MAX_INPUT);
      }
    }
    Photo p = existing != null ? existing : new Photo();
    p.id = id;
    if (existing == null) {
      p.name = name == null || name.isEmpty() ? "사진" : name;
      p.importedAt = System.currentTimeMillis();
    }
    p.owned = true;
    p.trashedAt = 0;
    p.bytes = dest.length();
    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
    o.inJustDecodeBounds = true;
    android.graphics.BitmapFactory.decodeFile(dest.getPath(), o);
    if (o.outWidth > 0) p.width = o.outWidth;
    if (o.outHeight > 0) p.height = o.outHeight;
    if (existing == null) a.db.photos().insert(p);
    else a.db.photos().update(p);
    return p;
  }

  static String sha256(File file) throws Exception {
    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(file)) {
      byte[] b = new byte[16384];
      int n;
      while ((n = in.read(b)) != -1) digest.update(b, 0, n);
    }
    StringBuilder hex = new StringBuilder();
    for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
    return hex.toString();
  }

  public static void writeBackup(NuniApp a) {
    if (!keep(a.prefs) || !hasWritePermission(a)) return;
    try {
      byte[] data = buildBackup(a).toString(2).getBytes(StandardCharsets.UTF_8);
      writeBackupBytes(a, data);
    } catch (Exception ignored) {
    }
  }

  public static JSONObject readBackup(Context c) {
    for (Uri uri : findBackupUris(c)) {
      try (InputStream in = c.getContentResolver().openInputStream(uri)) {
        if (in == null) continue;
        String s = new String(Core.read(in, 2 * 1024 * 1024), StandardCharsets.UTF_8);
        JSONObject o = new JSONObject(s);
        if (o.has("schemaVersion")) return o;
      } catch (Exception ignored) {
      }
    }
    if (Build.VERSION.SDK_INT < 29) {
      File[] roots =
          new File[] {
            new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                FOLDER),
            new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                FOLDER)
          };
      for (File dir : roots) {
        for (String name : new String[] {BACKUP_HIDDEN, BACKUP_VISIBLE}) {
          File f = new File(dir, name);
          if (!f.isFile()) continue;
          try (InputStream in = new FileInputStream(f)) {
            JSONObject o =
                new JSONObject(new String(Core.read(in, 2 * 1024 * 1024), StandardCharsets.UTF_8));
            if (o.has("schemaVersion")) return o;
          } catch (Exception ignored) {
          }
        }
      }
    }
    return null;
  }

  public static JSONObject buildBackup(NuniApp a) throws Exception {
    JSONObject root = new JSONObject();
    root.put("schemaVersion", 1);
    root.put("savedAt", System.currentTimeMillis());
    root.put("prefs", a.prefs.exportJson());
    JSONArray cats = new JSONArray();
    for (String c : a.prefs.textCategories()) cats.put(c);
    root.put("textCategories", cats);

    JSONArray photos = new JSONArray();
    List<Photo> all = new ArrayList<>();
    all.addAll(a.db.photos().active());
    all.addAll(a.db.photos().trash());
    for (Photo p : all) {
      if (p == null || !p.owned) continue;
      photos.put(
          new JSONObject()
              .put("id", p.id)
              .put("name", p.name)
              .put("note", p.note)
              .put("favourite", p.favourite)
              .put("focusX", p.focusX)
              .put("focusY", p.focusY)
              .put("capturedAt", p.capturedAt)
              .put("importedAt", p.importedAt)
              .put("accent", p.accent)
              .put("faces", p.faces)
              .put("width", p.width)
              .put("height", p.height));
    }
    root.put("photos", photos);

    StudioDao dao = a.db.studio();
    root.put("albums", albumsJson(dao));
    root.put("albumItems", albumItemsJson(dao));
    root.put("textItems", textItemsJson(dao));
    root.put("textCollections", textCollectionsJson(dao));
    root.put("scenes", scenesJson(dao));
    root.put("sceneAlbums", sceneAlbumsJson(dao));
    root.put("sceneTextCollections", sceneTextsJson(dao));
    root.put("scheduleRules", schedulesJson(dao));
    JSONArray quotes = new JSONArray();
    for (Quote q : a.db.photos().quotes()) {
      quotes.put(new JSONObject().put("id", q.id).put("text", q.text));
    }
    root.put("quotes", quotes);
    return root;
  }

  static void applyBackupMetadata(NuniApp a, JSONObject backup) {
    if (backup == null) return;
    try {
      JSONObject prefs = backup.optJSONObject("prefs");
      if (prefs != null) {
        try {
          a.prefs.restore(prefs);
        } catch (Exception ignored) {
        }
      }
      JSONArray cats = backup.optJSONArray("textCategories");
      if (cats != null) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < cats.length(); i++) {
          String s = cats.optString(i, "").trim();
          if (!s.isEmpty()) list.add(s);
        }
        if (!list.isEmpty()) a.prefs.setTextCategories(list);
      }
    } catch (Exception ignored) {
    }
    try {
      StudioDao dao = a.db.studio();
      upsertAlbums(dao, backup.optJSONArray("albums"));
      upsertAlbumItems(dao, backup.optJSONArray("albumItems"));
      upsertTextItems(dao, backup.optJSONArray("textItems"));
      upsertTextCollections(dao, backup.optJSONArray("textCollections"));
      upsertScenes(dao, backup.optJSONArray("scenes"));
      upsertSceneAlbums(dao, backup.optJSONArray("sceneAlbums"));
      upsertSceneTexts(dao, backup.optJSONArray("sceneTextCollections"));
      upsertSchedules(dao, backup.optJSONArray("scheduleRules"));
      JSONArray quotes = backup.optJSONArray("quotes");
      if (quotes != null) {
        for (int i = 0; i < quotes.length(); i++) {
          JSONObject o = quotes.optJSONObject(i);
          if (o == null) continue;
          Quote q = new Quote();
          q.id = o.optInt("id");
          q.text = o.optString("text", "");
          if (q.id > 0 && !q.text.isEmpty()) a.db.photos().quote(q);
        }
      }
    } catch (Exception ignored) {
    }
  }

  private static void applyPhotoMeta(NuniApp a, Photo p, JSONObject meta) {
    if (p == null || meta == null) return;
    try {
      if (meta.has("name")) p.name = meta.optString("name", p.name);
      if (meta.has("note")) p.note = meta.optString("note", p.note);
      p.favourite = meta.optBoolean("favourite", p.favourite);
      p.focusX = (float) meta.optDouble("focusX", p.focusX);
      p.focusY = (float) meta.optDouble("focusY", p.focusY);
      if (meta.has("capturedAt") && p.capturedAt == 0) p.capturedAt = meta.optLong("capturedAt");
      a.db.photos().update(p);
    } catch (Exception ignored) {
    }
  }

  private static JSONObject indexPhotoMeta(JSONObject backup) {
    JSONObject map = new JSONObject();
    if (backup == null) return map;
    JSONArray arr = backup.optJSONArray("photos");
    if (arr == null) return map;
    for (int i = 0; i < arr.length(); i++) {
      JSONObject o = arr.optJSONObject(i);
      if (o == null) continue;
      String id = o.optString("id", "");
      if (!id.isEmpty()) {
        try {
          map.put(id, o);
        } catch (Exception ignored) {
        }
      }
    }
    return map;
  }

  static final class Found {
    final Uri uri;
    final String name;
    final long bytes;

    Found(Uri uri, String name, long bytes) {
      this.uri = uri;
      this.name = name;
      this.bytes = bytes;
    }
  }

  static List<Found> listPublic(Context c, boolean includeBackup) {
    List<Found> out = new ArrayList<>();
    if (Build.VERSION.SDK_INT >= 29) {
      queryMedia(
          c,
          imageCollection(),
          MediaStore.Images.Media.RELATIVE_PATH,
          IMAGE_PATH,
          true,
          includeBackup,
          out);
      if (includeBackup) {
        queryMedia(
            c,
            fileCollection(),
            MediaStore.MediaColumns.RELATIVE_PATH,
            DOC_PATH,
            false,
            true,
            out);
        queryMedia(
            c,
            fileCollection(),
            MediaStore.MediaColumns.RELATIVE_PATH,
            IMAGE_PATH,
            false,
            true,
            out);
      }
    } else {
      addLegacyFiles(
          new File(
              Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
              FOLDER),
          includeBackup,
          out);
      if (includeBackup) {
        addLegacyFiles(
            new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                FOLDER),
            true,
            out);
      }
    }
    return out;
  }

  private static void queryMedia(
      Context c,
      Uri collection,
      String pathCol,
      String pathPrefix,
      boolean imagesOnly,
      boolean includeBackup,
      List<Found> out) {
    String[] proj =
        new String[] {
          MediaStore.MediaColumns._ID,
          MediaStore.MediaColumns.DISPLAY_NAME,
          MediaStore.MediaColumns.SIZE
        };
    String sel = pathCol + " LIKE ?";
    String[] args = new String[] {pathPrefix.replaceAll("/$", "") + "%"};
    try (Cursor cur = c.getContentResolver().query(collection, proj, sel, args, null)) {
      if (cur == null) return;
      int idIx = cur.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
      int nameIx = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
      int sizeIx = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
      while (cur.moveToNext()) {
        String name = cur.getString(nameIx);
        boolean backup = BACKUP_HIDDEN.equals(name) || BACKUP_VISIBLE.equals(name);
        if (backup && !includeBackup) continue;
        if (!backup && imagesOnly && !looksLikeArchiveName(name)) continue;
        if (!backup && !imagesOnly && !includeBackup) continue;
        if (!backup && !imagesOnly && !looksLikeArchiveName(name)) continue;
        Uri uri = ContentUris.withAppendedId(collection, cur.getLong(idIx));
        out.add(new Found(uri, name, cur.getLong(sizeIx)));
      }
    } catch (Exception ignored) {
    }
  }

  private static void addLegacyFiles(File dir, boolean includeBackup, List<Found> out) {
    File[] files = dir.listFiles();
    if (files == null) return;
    for (File f : files) {
      if (!f.isFile()) continue;
      String name = f.getName();
      boolean backup = BACKUP_HIDDEN.equals(name) || BACKUP_VISIBLE.equals(name);
      if (backup && !includeBackup) continue;
      if (!backup && !looksLikeArchiveName(name)) continue;
      out.add(new Found(Uri.fromFile(f), name, f.length()));
    }
  }

  static Uri findExisting(Context c, String photoId) {
    String want = displayName(photoId);
    for (Found f : listPublic(c, false)) {
      if (want.equalsIgnoreCase(f.name)) return f.uri;
      if (photoId.equals(photoIdFromName(f.name))) return f.uri;
    }
    return null;
  }

  private static Uri insertCopy(Context c, String photoId, File src) throws Exception {
    if (Build.VERSION.SDK_INT >= 29) {
      ContentResolver cr = c.getContentResolver();
      ContentValues v = new ContentValues();
      v.put(MediaStore.Images.Media.DISPLAY_NAME, displayName(photoId));
      v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
      v.put(MediaStore.Images.Media.RELATIVE_PATH, IMAGE_PATH);
      v.put(MediaStore.Images.Media.IS_PENDING, 1);
      Uri uri = cr.insert(imageCollection(), v);
      if (uri == null) throw new java.io.IOException("사진 폴더에 파일을 만들지 못했습니다.");
      try (InputStream in = new FileInputStream(src);
          OutputStream out = cr.openOutputStream(uri, "w")) {
        if (out == null) throw new java.io.IOException("사진 폴더에 쓰지 못했습니다.");
        Core.copy(in, out, Core.MAX_INPUT);
      } catch (Exception e) {
        try {
          cr.delete(uri, null, null);
        } catch (Exception ignored) {
        }
        throw e;
      }
      ContentValues done = new ContentValues();
      done.put(MediaStore.Images.Media.IS_PENDING, 0);
      cr.update(uri, done, null, null);
      return uri;
    }
    File dir =
        new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER);
    if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("사진 폴더를 만들지 못했습니다.");
    File dest = new File(dir, displayName(photoId));
    if (!(dest.isFile() && dest.length() > 0)) {
      try (InputStream in = new FileInputStream(src);
          OutputStream out = new FileOutputStream(dest)) {
        Core.copy(in, out, Core.MAX_INPUT);
      }
    }
    MediaScannerConnection.scanFile(
        c, new String[] {dest.getAbsolutePath()}, new String[] {"image/jpeg"}, null);
    return Uri.fromFile(dest);
  }

  private static Uri tryDelete(Context c, Uri uri) {
    if (uri == null) return null;
    try {
      if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
        File f = new File(uri.getPath());
        if (!f.exists() || f.delete()) return null;
        return uri;
      }
      int n = c.getContentResolver().delete(uri, null, null);
      return n > 0 ? null : uri;
    } catch (SecurityException e) {
      if (Build.VERSION.SDK_INT >= 29 && e instanceof RecoverableSecurityException) return uri;
      return uri;
    } catch (Exception e) {
      return uri;
    }
  }

  private static void writeBackupBytes(Context c, byte[] data) throws Exception {
    if (Build.VERSION.SDK_INT >= 29) {
      Uri existing = null;
      List<Uri> found = findBackupUris(c);
      if (!found.isEmpty()) existing = found.get(0);
      ContentResolver cr = c.getContentResolver();
      Uri uri = existing;
      if (uri == null) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, BACKUP_HIDDEN);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, DOC_PATH);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        try {
          uri = cr.insert(fileCollection(), v);
        } catch (Exception ignored) {
        }
        if (uri == null) {
          v.put(MediaStore.MediaColumns.DISPLAY_NAME, BACKUP_VISIBLE);
          uri = cr.insert(fileCollection(), v);
        }
        if (uri == null) throw new java.io.IOException("설정 백업 파일을 만들지 못했습니다.");
      } else {
        ContentValues pending = new ContentValues();
        pending.put(MediaStore.MediaColumns.IS_PENDING, 1);
        try {
          cr.update(uri, pending, null, null);
        } catch (Exception ignored) {
        }
      }
      try (OutputStream out = cr.openOutputStream(uri, "wt")) {
        if (out == null) throw new java.io.IOException("설정 백업 파일에 쓰지 못했습니다.");
        out.write(data);
      } catch (Exception e) {
        throw e;
      } finally {
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        try {
          cr.update(uri, done, null, null);
        } catch (Exception ignored) {
        }
      }
      return;
    }
    File dir =
        new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            FOLDER);
    if (!dir.exists() && !dir.mkdirs()) {
      dir =
          new File(
              Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
              FOLDER);
      if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("백업 폴더를 만들지 못했습니다.");
    }
    File dest = new File(dir, BACKUP_HIDDEN);
    try (OutputStream out = new FileOutputStream(dest)) {
      out.write(data);
    }
  }

  private static List<Uri> findBackupUris(Context c) {
    List<Uri> out = new ArrayList<>();
    if (Build.VERSION.SDK_INT < 29) return out;
    for (Found f : listPublic(c, true)) {
      if (BACKUP_HIDDEN.equals(f.name) || BACKUP_VISIBLE.equals(f.name)) out.add(f.uri);
    }
    return out;
  }

  private static Uri imageCollection() {
    if (Build.VERSION.SDK_INT >= 29) {
      return MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    }
    return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
  }

  private static Uri fileCollection() {
    if (Build.VERSION.SDK_INT >= 29) {
      return MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    }
    return MediaStore.Files.getContentUri("external");
  }

  static void addPendingDelete(Context c, Uri uri) {
    if (uri == null) return;
    List<String> list = pendingDeleteStrings(c);
    String s = uri.toString();
    if (!list.contains(s)) list.add(s);
    savePendingDeletes(c, list);
  }

  static void removePendingDelete(Context c, Uri uri) {
    List<String> list = pendingDeleteStrings(c);
    list.remove(uri.toString());
    savePendingDeletes(c, list);
  }

  static void clearPendingDeletes(Context c) {
    NuniApp.of(c).prefs.raw.edit().remove(PREF_PENDING_DELETES).apply();
  }

  static List<Uri> pendingDeletes(Context c) {
    List<Uri> out = new ArrayList<>();
    for (String s : pendingDeleteStrings(c)) {
      try {
        out.add(Uri.parse(s));
      } catch (Exception ignored) {
      }
    }
    return out;
  }

  private static List<String> pendingDeleteStrings(Context c) {
    List<String> out = new ArrayList<>();
    String raw = NuniApp.of(c).prefs.raw.getString(PREF_PENDING_DELETES, "");
    if (raw == null || raw.isEmpty()) return out;
    try {
      JSONArray arr = new JSONArray(raw);
      for (int i = 0; i < arr.length(); i++) {
        String s = arr.optString(i, "");
        if (!s.isEmpty()) out.add(s);
      }
    } catch (Exception ignored) {
    }
    return out;
  }

  private static void savePendingDeletes(Context c, List<String> list) {
    JSONArray arr = new JSONArray();
    for (String s : list) arr.put(s);
    NuniApp.of(c).prefs.raw.edit().putString(PREF_PENDING_DELETES, arr.toString()).apply();
  }

  private static JSONArray albumsJson(StudioDao dao) throws Exception {
    JSONArray arr = new JSONArray();
    for (Album al : dao.albums()) {
      arr.put(
          new JSONObject()
              .put("id", al.id)
              .put("name", al.name)
              .put("description", al.description)
              .put("coverPhotoId", al.coverPhotoId)
              .put("sortOrder", al.sortOrder)
              .put("seconds", al.seconds)
              .put("shuffle", al.shuffle)
              .put("isDefault", al.isDefault)
              .put("createdAt", al.createdAt)
              .put("updatedAt", al.updatedAt));
    }
    return arr;
  }

  private static JSONArray albumItemsJson(StudioDao dao) throws Exception {
    JSONArray arr = new JSONArray();
    for (AlbumItem it : dao.allAlbumItems()) {
      arr.put(
          new JSONObject()
              .put("albumId", it.albumId)
              .put("photoId", it.photoId)
              .put("position", it.position)
              .put("addedAt", it.addedAt));
    }
    return arr;
  }

  private static JSONArray textItemsJson(StudioDao dao) throws Exception {
    JSONArray arr = new JSONArray();
    for (TextItem t : dao.textItems()) {
      arr.put(
          new JSONObject()
              .put("id", t.id)
              .put("title", t.title)
              .put("body", t.body)
              .put("author", t.author)
              .put("source", t.source)
              .put("kind", t.kind)
              .put("category", t.category)
              .put("tags", t.tags)
              .put("licenseNote", t.licenseNote)
              .put("displaySeconds", t.displaySeconds)
              .put("sortOrder", t.sortOrder)
              .put("enabled", t.enabled)
              .put("createdAt", t.createdAt)
              .put("updatedAt", t.updatedAt));
    }
    return arr;
  }

  private static JSONArray textCollectionsJson(StudioDao dao) throws Exception {
    JSONArray arr = new JSONArray();
    for (TextCollection t : dao.textCollections()) {
      arr.put(
          new JSONObject()
              .put("id", t.id)
              .put("name", t.name)
              .put("description", t.description)
              .put("categoryFilter", t.categoryFilter)
              .put("kindFilter", t.kindFilter)
              .put("itemIdsJson", t.itemIdsJson)
              .put("shuffle", t.shuffle)
              .put("avoidRepeat", t.avoidRepeat)
              .put("frequency", t.frequency)
              .put("createdAt", t.createdAt)
              .put("updatedAt", t.updatedAt));
    }
    return arr;
  }

  private static JSONArray scenesJson(StudioDao dao) throws Exception {
    JSONArray arr = new JSONArray();
    for (Scene s : dao.scenes()) {
      arr.put(
          new JSONObject()
              .put("id", s.id)
              .put("name", s.name)
              .put("description", s.description)
              .put("layout", s.layout)
              .put("transitionEffect", s.transitionEffect)
              .put("textDisplayMode", s.textDisplayMode)
              .put("quoteCycle", s.quoteCycle)
              .put("theme", s.theme)
              .put("textColor", s.textColor)
              .put("textAlign", s.textAlign)
              .put("seconds", s.seconds)
              .put("textEveryN", s.textEveryN)
              .put("brightness", s.brightness)
              .put("fontScale", s.fontScale)
              .put("textPadding", s.textPadding)
              .put("scrimOpacity", s.scrimOpacity)
              .put("showClock", s.showClock)
              .put("showWeather", s.showWeather)
              .put("showCaptureDate", s.showCaptureDate)
              .put("shufflePhotos", s.shufflePhotos)
              .put("isDefault", s.isDefault)
              .put("nightDim", s.nightDim)
              .put("createdAt", s.createdAt)
              .put("updatedAt", s.updatedAt));
    }
    return arr;
  }

  private static JSONArray sceneAlbumsJson(StudioDao dao) throws Exception {
    JSONArray arr = new JSONArray();
    for (SceneAlbum s : dao.allSceneAlbums()) {
      arr.put(
          new JSONObject()
              .put("sceneId", s.sceneId)
              .put("albumId", s.albumId)
              .put("weight", s.weight));
    }
    return arr;
  }

  private static JSONArray sceneTextsJson(StudioDao dao) throws Exception {
    JSONArray arr = new JSONArray();
    for (SceneTextCollection s : dao.allSceneTextCollections()) {
      arr.put(new JSONObject().put("sceneId", s.sceneId).put("collectionId", s.collectionId));
    }
    return arr;
  }

  private static JSONArray schedulesJson(StudioDao dao) throws Exception {
    JSONArray arr = new JSONArray();
    for (ScheduleRule r : dao.scheduleRules()) {
      arr.put(
          new JSONObject()
              .put("id", r.id)
              .put("name", r.name)
              .put("ruleType", r.ruleType)
              .put("specificDate", r.specificDate)
              .put("sceneId", r.sceneId)
              .put("daysMask", r.daysMask)
              .put("startMin", r.startMin)
              .put("endMin", r.endMin)
              .put("priority", r.priority)
              .put("enabled", r.enabled)
              .put("expiresAt", r.expiresAt)
              .put("createdAt", r.createdAt)
              .put("updatedAt", r.updatedAt));
    }
    return arr;
  }

  private static void upsertAlbums(StudioDao dao, JSONArray arr) {
    if (arr == null) return;
    for (int i = 0; i < arr.length(); i++) {
      JSONObject o = arr.optJSONObject(i);
      if (o == null) continue;
      Album al = new Album();
      al.id = o.optString("id", "");
      if (al.id.isEmpty()) continue;
      al.name = o.optString("name", al.name);
      al.description = o.optString("description", "");
      al.coverPhotoId = o.optString("coverPhotoId", "");
      al.sortOrder = o.optString("sortOrder", al.sortOrder);
      al.seconds = o.optInt("seconds", al.seconds);
      al.shuffle = o.optBoolean("shuffle", al.shuffle);
      al.isDefault = o.optBoolean("isDefault", al.isDefault);
      al.createdAt = o.optLong("createdAt");
      al.updatedAt = o.optLong("updatedAt");
      dao.saveAlbum(al);
    }
  }

  private static void upsertAlbumItems(StudioDao dao, JSONArray arr) {
    if (arr == null) return;
    for (int i = 0; i < arr.length(); i++) {
      JSONObject o = arr.optJSONObject(i);
      if (o == null) continue;
      AlbumItem it = new AlbumItem();
      it.albumId = o.optString("albumId", "");
      it.photoId = o.optString("photoId", "");
      if (it.albumId.isEmpty() || it.photoId.isEmpty()) continue;
      it.position = o.optInt("position");
      it.addedAt = o.optLong("addedAt");
      try {
        dao.saveAlbumItem(it);
      } catch (Exception ignored) {
      }
    }
  }

  private static void upsertTextItems(StudioDao dao, JSONArray arr) {
    if (arr == null) return;
    for (int i = 0; i < arr.length(); i++) {
      JSONObject o = arr.optJSONObject(i);
      if (o == null) continue;
      TextItem t = new TextItem();
      t.id = o.optString("id", "");
      if (t.id.isEmpty()) continue;
      t.title = o.optString("title", "");
      t.body = o.optString("body", "");
      t.author = o.optString("author", "");
      t.source = o.optString("source", "");
      t.kind = o.optString("kind", t.kind);
      t.category = o.optString("category", "");
      t.tags = o.optString("tags", "");
      t.licenseNote = o.optString("licenseNote", "");
      t.displaySeconds = o.optInt("displaySeconds", t.displaySeconds);
      t.sortOrder = o.optInt("sortOrder", t.sortOrder);
      t.enabled = o.optBoolean("enabled", t.enabled);
      t.createdAt = o.optLong("createdAt");
      t.updatedAt = o.optLong("updatedAt");
      dao.saveTextItem(t);
    }
  }

  private static void upsertTextCollections(StudioDao dao, JSONArray arr) {
    if (arr == null) return;
    for (int i = 0; i < arr.length(); i++) {
      JSONObject o = arr.optJSONObject(i);
      if (o == null) continue;
      TextCollection t = new TextCollection();
      t.id = o.optString("id", "");
      if (t.id.isEmpty()) continue;
      t.name = o.optString("name", t.name);
      t.description = o.optString("description", "");
      t.categoryFilter = o.optString("categoryFilter", "");
      t.kindFilter = o.optString("kindFilter", "");
      t.itemIdsJson = o.optString("itemIdsJson", "[]");
      t.shuffle = o.optBoolean("shuffle", t.shuffle);
      t.avoidRepeat = o.optBoolean("avoidRepeat", t.avoidRepeat);
      t.frequency = o.optInt("frequency", t.frequency);
      t.createdAt = o.optLong("createdAt");
      t.updatedAt = o.optLong("updatedAt");
      dao.saveTextCollection(t);
    }
  }

  private static void upsertScenes(StudioDao dao, JSONArray arr) {
    if (arr == null) return;
    for (int i = 0; i < arr.length(); i++) {
      JSONObject o = arr.optJSONObject(i);
      if (o == null) continue;
      Scene s = new Scene();
      s.id = o.optString("id", "");
      if (s.id.isEmpty()) continue;
      s.name = o.optString("name", s.name);
      s.description = o.optString("description", "");
      s.layout = o.optString("layout", s.layout);
      s.transitionEffect = o.optString("transitionEffect", s.transitionEffect);
      s.textDisplayMode = o.optString("textDisplayMode", s.textDisplayMode);
      s.quoteCycle = o.optString("quoteCycle", s.quoteCycle);
      s.theme = o.optString("theme", s.theme);
      s.textColor = o.optString("textColor", s.textColor);
      s.textAlign = o.optString("textAlign", s.textAlign);
      s.seconds = o.optInt("seconds", s.seconds);
      s.textEveryN = o.optInt("textEveryN", s.textEveryN);
      s.brightness = o.optInt("brightness", s.brightness);
      s.fontScale = o.optInt("fontScale", s.fontScale);
      s.textPadding = o.optInt("textPadding", s.textPadding);
      s.scrimOpacity = o.optInt("scrimOpacity", s.scrimOpacity);
      s.showClock = o.optBoolean("showClock", s.showClock);
      s.showWeather = o.optBoolean("showWeather", s.showWeather);
      s.showCaptureDate = o.optBoolean("showCaptureDate", s.showCaptureDate);
      s.shufflePhotos = o.optBoolean("shufflePhotos", s.shufflePhotos);
      s.isDefault = o.optBoolean("isDefault", s.isDefault);
      s.nightDim = o.optBoolean("nightDim", s.nightDim);
      s.createdAt = o.optLong("createdAt");
      s.updatedAt = o.optLong("updatedAt");
      dao.saveScene(s);
    }
  }

  private static void upsertSceneAlbums(StudioDao dao, JSONArray arr) {
    if (arr == null) return;
    for (int i = 0; i < arr.length(); i++) {
      JSONObject o = arr.optJSONObject(i);
      if (o == null) continue;
      SceneAlbum s = new SceneAlbum();
      s.sceneId = o.optString("sceneId", "");
      s.albumId = o.optString("albumId", "");
      s.weight = o.optInt("weight", 1);
      if (s.sceneId.isEmpty() || s.albumId.isEmpty()) continue;
      try {
        dao.saveSceneAlbum(s);
      } catch (Exception ignored) {
      }
    }
  }

  private static void upsertSceneTexts(StudioDao dao, JSONArray arr) {
    if (arr == null) return;
    for (int i = 0; i < arr.length(); i++) {
      JSONObject o = arr.optJSONObject(i);
      if (o == null) continue;
      SceneTextCollection s = new SceneTextCollection();
      s.sceneId = o.optString("sceneId", "");
      s.collectionId = o.optString("collectionId", "");
      if (s.sceneId.isEmpty() || s.collectionId.isEmpty()) continue;
      try {
        dao.saveSceneTextCollection(s);
      } catch (Exception ignored) {
      }
    }
  }

  private static void upsertSchedules(StudioDao dao, JSONArray arr) {
    if (arr == null) return;
    for (int i = 0; i < arr.length(); i++) {
      JSONObject o = arr.optJSONObject(i);
      if (o == null) continue;
      ScheduleRule r = new ScheduleRule();
      r.id = o.optString("id", "");
      if (r.id.isEmpty()) continue;
      r.name = o.optString("name", r.name);
      r.ruleType = o.optString("ruleType", r.ruleType);
      r.specificDate = o.optString("specificDate", "");
      r.sceneId = o.optString("sceneId", "");
      r.daysMask = o.optInt("daysMask", r.daysMask);
      r.startMin = o.optInt("startMin", r.startMin);
      r.endMin = o.optInt("endMin", r.endMin);
      r.priority = o.optInt("priority", r.priority);
      r.enabled = o.optBoolean("enabled", r.enabled);
      r.expiresAt = o.optLong("expiresAt");
      r.createdAt = o.optLong("createdAt");
      r.updatedAt = o.optLong("updatedAt");
      dao.saveScheduleRule(r);
    }
  }
}
