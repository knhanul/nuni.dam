package co.kr.nuni.dam;

import android.content.Context;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public final class FrameWatchdogWorker extends Worker {
  public FrameWatchdogWorker(Context context, WorkerParameters params) {
    super(context, params);
  }

  @Override
  public Result doWork() {
    FrameWatchdog.tick(getApplicationContext());
    return Result.success();
  }
}
