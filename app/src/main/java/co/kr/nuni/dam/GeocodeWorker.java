package co.kr.nuni.dam;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.List;

/** 촬영 좌표가 있는데 지명이 없는 사진을 백그라운드에서 채운다. 네트워크가 있을 때만 실행한다. */
public final class GeocodeWorker extends Worker {
  public GeocodeWorker(@NonNull Context c, @NonNull WorkerParameters p) {
    super(c, p);
  }

  public static void schedule(Context c) {
    WorkManager.getInstance(c)
        .enqueueUniqueWork(
            "geocode-places",
            ExistingWorkPolicy.REPLACE,
            new OneTimeWorkRequest.Builder(GeocodeWorker.class)
                .setConstraints(
                    new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .build());
  }

  @NonNull
  @Override
  public Result doWork() {
    NuniApp a = NuniApp.of(getApplicationContext());
    try {
      Places.scanExistingGps(a);
      Places.fillFromCache(a);
      List<Photo> pending = a.db.photos().needingPlaceName();
      boolean failed = false;
      for (Photo p : pending) {
        if (isStopped()) return Result.retry();
        if (p == null || !p.hasGps) continue;
        if (!p.placeName.isEmpty()) continue;
        if (p.geocodedAt > 0
            && System.currentTimeMillis() - p.geocodedAt < 6L * 60 * 60 * 1000) {
          continue;
        }
        if (!Places.hasNetwork(a)) return Result.retry();
        if (!Places.lookupAndStore(a, p)) failed = true;
      }
      return failed ? Result.retry() : Result.success();
    } catch (Exception e) {
      return getRunAttemptCount() < 5 ? Result.retry() : Result.failure();
    }
  }
}
