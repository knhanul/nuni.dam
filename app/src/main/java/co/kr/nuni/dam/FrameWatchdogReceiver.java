package co.kr.nuni.dam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class FrameWatchdogReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    FrameWatchdog.onAlarm(context);
  }
}
