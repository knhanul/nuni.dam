package co.kr.nuni.dam;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 소유한 사진을 Pictures/누니담에 복사하고, 설정 백업 JSON을 갱신한다. 액자 재생을 막지 않는다. */
public final class PublicCopyWorker extends Worker {
  public PublicCopyWorker(@NonNull Context c, @NonNull WorkerParameters p) {
    super(c, p);
  }

  public static void scheduleMigration(Context c) {
    WorkManager.getInstance(c)
        .enqueueUniqueWork(
            "public-copy-migrate",
            ExistingWorkPolicy.KEEP,
            new OneTimeWorkRequest.Builder(PublicCopyWorker.class)
                .setInputData(
                    new androidx.work.Data.Builder().putString("op", "migrate").build())
                .build());
  }

  public static void scheduleBackup(Context c) {
    if (!PublicArchive.keep(NuniApp.of(c).prefs)) return;
    WorkManager.getInstance(c)
        .enqueueUniqueWork(
            "public-backup",
            ExistingWorkPolicy.REPLACE,
            new OneTimeWorkRequest.Builder(PublicCopyWorker.class)
                .setInitialDelay(2, TimeUnit.SECONDS)
                .setInputData(
                    new androidx.work.Data.Builder().putString("op", "backup").build())
                .build());
  }

  @NonNull
  @Override
  public Result doWork() {
    NuniApp a = NuniApp.of(getApplicationContext());
    String op = getInputData().getString("op");
    if ("backup".equals(op)) {
      PublicArchive.writeBackup(a);
      return Result.success();
    }
    if (!PublicArchive.keep(a.prefs)) {
      a.prefs.raw.edit().putBoolean(PublicArchive.PREF_MIGRATED, true).apply();
      return Result.success();
    }
    if (!PublicArchive.hasWritePermission(a)) return Result.failure();
    try {
      List<Photo> owned = a.db.photos().owned();
      int total = 0;
      for (Photo p : owned) {
        if (PublicArchive.sourceFile(a.store, p.id) != null) total++;
      }
      a.prefs.number(PublicArchive.PREF_MIG_TOTAL, total);
      int done = 0;
      for (Photo p : owned) {
        if (isStopped()) return Result.retry();
        if (PublicArchive.sourceFile(a.store, p.id) == null) continue;
        PublicArchive.ensureCopy(a, p);
        done++;
        a.prefs.number(PublicArchive.PREF_MIG_DONE, done);
      }
      a.prefs.raw.edit().putBoolean(PublicArchive.PREF_MIGRATED, true).apply();
      PublicArchive.writeBackup(a);
      return Result.success();
    } catch (Exception e) {
      return getRunAttemptCount() < 3 ? Result.retry() : Result.failure();
    }
  }
}
