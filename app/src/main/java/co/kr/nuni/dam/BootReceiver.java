package co.kr.nuni.dam;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;

public final class BootReceiver extends BroadcastReceiver {
  public void onReceive(Context c, Intent i) {
    if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
    FolderSync.schedule(c);
    if (Build.VERSION.SDK_INT >= 33
        && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return;
    PendingIntent p =
        PendingIntent.getActivity(
            c,
            10,
            new Intent(c, FrameActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder b =
        Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(c, "frame")
            : new Notification.Builder(c);
    c.getSystemService(NotificationManager.class)
        .notify(
            10,
            b.setSmallIcon(R.drawable.ic_nuni)
                .setContentTitle("누니담 액자를 다시 켜세요")
                .setContentText("사진은 그대로 보관되어 있습니다.")
                .setContentIntent(p)
                .setAutoCancel(true)
                .build());
  }
}
