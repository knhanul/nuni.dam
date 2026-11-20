package co.kr.nuni.dam;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.work.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class FolderSync extends Worker {
  private static final Object LOCK = new Object();

  public FolderSync(Context c, WorkerParameters p) {
    super(c, p);
  }

  public static void schedule(Context c) {
    NuniApp a = NuniApp.of(c);
    if (a.prefs.folder().isEmpty()) {
      WorkManager.getInstance(c).cancelUniqueWork("folder-sync");
      return;
    }
    PeriodicWorkRequest w =
        new PeriodicWorkRequest.Builder(FolderSync.class, 6, TimeUnit.HOURS)
            .setConstraints(new Constraints.Builder().setRequiresStorageNotLow(true).build())
            .build();
    WorkManager.getInstance(c)
        .enqueueUniquePeriodicWork("folder-sync", ExistingPeriodicWorkPolicy.KEEP, w);
  }

  public static void now(Context c) {
    WorkManager.getInstance(c)
        .enqueueUniqueWork(
            "folder-now",
            ExistingWorkPolicy.KEEP,
            new OneTimeWorkRequest.Builder(FolderSync.class).build());
  }

  /** Continues a sync that stopped early (memory pressure or per-run limit) after a pause. */
  static void later(Context c, long delayMinutes) {
    WorkManager.getInstance(c)
        .enqueueUniqueWork(
            "folder-continue",
            ExistingWorkPolicy.REPLACE,
            new OneTimeWorkRequest.Builder(FolderSync.class)
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder().setRequiresStorageNotLow(true).build())
                .build());
  }

  @Override
  public Result doWork() {
    synchronized (LOCK) {
      NuniApp a = NuniApp.of(getApplicationContext());
      String root = a.prefs.folder();
      if (root.isEmpty()) return Result.success();
      long scan = System.currentTimeMillis();
      int seen = 0, imported = 0, failed = 0;
      String stopReason = "";
      ExitLog.logJob(a, "폴더 동기화 시작");
      a.store.beginBatch();
      try {
        Uri tree = Uri.parse(root),
            children =
                DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
        String[] cols = {
          DocumentsContract.Document.COLUMN_DOCUMENT_ID,
          DocumentsContract.Document.COLUMN_DISPLAY_NAME,
          DocumentsContract.Document.COLUMN_MIME_TYPE,
          DocumentsContract.Document.COLUMN_LAST_MODIFIED,
          DocumentsContract.Document.COLUMN_SIZE
        };
        try (Cursor c =
            getApplicationContext().getContentResolver().query(children, cols, null, null, null)) {
          if (c == null || c.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING, false))
            throw new java.io.IOException("폴더 목록을 완전히 읽지 못했습니다.");
          while (c.moveToNext()) {
            if (isStopped()) {
              stopReason = "시스템 중단";
              return Result.retry();
            }
            String mime = c.getString(2);
            if (mime == null || !mime.startsWith("image/")) continue;
            if (++seen > 5000) throw new java.io.IOException("폴더당 5,000장까지 지원합니다.");
            String doc = c.getString(0);
            long modified = c.isNull(3) ? 0 : c.getLong(3), size = c.isNull(4) ? 0 : c.getLong(4);
            SourceLink link = a.db.photos().source(root, doc);
            Photo p = link == null ? null : a.db.photos().get(link.photoId);
            boolean same =
                Core.sourceUnchanged(
                    link != null && p != null,
                    p != null && a.store.file(p.id).isFile(),
                    link == null ? 0 : link.modified,
                    link == null ? 0 : link.size,
                    modified,
                    size);
            if (!same) {
              // Heavy step: copy, decode and analyse one photo. Stop early instead of pushing the
              // device into a low-memory kill; the rest continues in a later run.
              if (MemoryGuard.shouldBackOff(a)) {
                stopReason = "메모리 부족";
                later(a, 20);
                return Result.success();
              }
              if (imported >= Core.FOLDER_IMPORTS_PER_RUN) {
                stopReason = "이번 회차 " + Core.FOLDER_IMPORTS_PER_RUN + "장 처리";
                later(a, 3);
                return Result.success();
              }
              try {
                String photoId =
                    a.store.fromUri(
                            DocumentsContract.buildDocumentUriUsingTree(tree, doc),
                            c.getString(1),
                            false)
                        .id;
                if (link == null) {
                  link = new SourceLink();
                  link.root = root;
                  link.document = doc;
                }
                link.photoId = photoId;
                link.modified = modified;
                link.size = size;
                imported++;
              } catch (SecurityException e) {
                throw e;
              } catch (Exception e) {
                failed++;
                // Keep an old link alive so a single bad file is not treated as deleted.
                if (link == null) continue;
              }
              try {
                Thread.sleep(300);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopReason = "시스템 중단";
                return Result.retry();
              }
            } else {
              link.modified = modified;
              link.size = size;
            }
            link.scan = scan;
            a.db.photos().link(link);
          }
          if (c.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING, false))
            throw new java.io.IOException("부분 목록은 삭제 판정에 사용하지 않습니다.");
        }
        if (isStopped() || !root.equals(a.prefs.folder())) {
          stopReason = "시스템 중단";
          return Result.retry();
        }
        if (failed == 0
            && Core.reconcile(true, false, a.prefs.raw.getBoolean("mirror", false), seen))
          a.db.runInTransaction(
              () -> {
                List<Photo> missing = a.db.photos().missing(root, scan);
                a.db.photos().removeMissing(root, scan);
                for (Photo p : missing)
                  if (Core.mayTrash(p.owned, p.favourite, a.db.photos().references(p.id)))
                    a.db.photos().trash(p.id, System.currentTimeMillis());
              });
        a.prefs.text("syncStatus", seen == 0 ? "빈 폴더 · 기존 사진 보존" : seen + "장 확인 · 동기화 완료");
        a.prefs.raw.edit().putLong("lastSync", System.currentTimeMillis()).apply();
        a.store.changed();
        return Result.success();
      } catch (SecurityException e) {
        stopReason = "폴더 권한 만료";
        a.prefs.text("syncStatus", "폴더 권한이 만료되었습니다. 다시 선택해 주세요.");
        return Result.failure();
      } catch (Exception e) {
        stopReason = "오류";
        a.prefs.text("syncStatus", "동기화 보류: " + e.getMessage());
        return getRunAttemptCount() < 3 ? Result.retry() : Result.failure();
      } finally {
        a.store.endBatch();
        long secs = (System.currentTimeMillis() - scan) / 1000;
        ExitLog.logJob(
            a,
            "폴더 동기화 "
                + (stopReason.isEmpty() ? "끝" : "멈춤(" + stopReason + ", 나머지는 나중에)")
                + ": 확인 "
                + seen
                + "장 · 새로/바뀐 사진 "
                + imported
                + "장"
                + (failed > 0 ? " · 실패 " + failed + "장" : "")
                + " · "
                + secs
                + "초");
      }
    }
  }
}
