package co.kr.nuni.dam;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityManager;
import android.widget.*;
import java.util.Calendar;

public final class FrameActivity extends Activity {
  private NuniApp a;
  private FrameView frame;
  private FrameSession session;
  private LinearLayout controls;
  private Button pause;
  private ChargingController charging;
  private PowerSaving.DisplayPowerController displayPower;
  private PowerSaving.CameraMotionSource motionSource;
  private Core.PowerSavingStateMachine powerState;
  private PowerSaving.DeviceCapabilities capabilities;
  private final Handler h = new Handler(Looper.getMainLooper());
  private final Runnable hide =
      () -> {
        if (!isAccessibilityEnabled() && !controls.hasFocus()) {
          controls.setVisibility(View.GONE);
          syncPowerInhibit();
        }
      };
  private final Runnable powerTick =
      new Runnable() {
        public void run() {
          evaluatePowerState();
          h.postDelayed(this, 2000);
        }
      };

  public void onCreate(Bundle b) {
    super.onCreate(b);
    a = NuniApp.of(this);
    Ui.immersive(this);
    FrameLayout root = new FrameLayout(this);
    frame = new FrameView(this);
    root.addView(frame, new FrameLayout.LayoutParams(-1, -1));
    controls = Ui.column(this);
    int pad = Ui.dp(this, 14);
    controls.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 10));
    controls.setBackground(Ui.strokeBg(0xf4ffffff, Ui.OUTLINE, Ui.dp(this, 1), Ui.dp(this, 20)));
    controls.setElevation(Ui.dp(this, 6));
    LinearLayout one = Ui.row(this), two = Ui.row(this);
    one.setGravity(Gravity.CENTER);
    controls.addView(one);
    View line = Ui.divider(this);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 1));
    lp.setMargins(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
    controls.addView(line, lp);
    controls.addView(two);
    add(
        one,
        "이전",
        R.drawable.ic_prev,
        Ui.INK,
        false,
        () -> {
          onUserTouch();
          session.previous();
          showControls();
        });
    pause =
        add(
            one,
            "일시 정지",
            R.drawable.ic_pause,
            Ui.PRIMARY,
            false,
            () -> {
              onUserTouch();
              session.pause();
              pauseState();
              showControls();
            });
    pause.setBackground(Ui.bg(Ui.SURFACE_SUBTLE, Ui.dp(this, 14)));
    add(
        one,
        "다음",
        R.drawable.ic_next,
        Ui.INK,
        false,
        () -> {
          onUserTouch();
          session.next();
          showControls();
        });
    add(two, "사진 관리", R.drawable.ic_gallery, Ui.INK, true, () -> openManage("PHOTOS"));
    add(two, "꾸미기", R.drawable.ic_sun, Ui.INK, true, () -> openManage("CUSTOMIZE"));
    add(two, "설정", R.drawable.ic_tune, Ui.INK, true, () -> openManage("SETTINGS"));
    add(two, "관리 홈", R.drawable.ic_home, Ui.MUTED, true, this::finish);
    add(two, "앱 종료", R.drawable.ic_exit, Ui.DANGER, true, this::exitApp);
    FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    int margin = Ui.dp(this, 16);
    p.setMargins(margin, 0, margin, margin);
    root.addView(controls, p);
    setContentView(root);
    frame.setOnClickListener(
        v -> {
          // Section 6.2: Touch while dimmed wakes brightness first without opening/triggering actions
          if (frame.isDimmedOverlay()) {
            onUserTouch();
            return;
          }
          onUserTouch();
          if (controls.getVisibility() == View.VISIBLE && !isAccessibilityEnabled()) {
            controls.setVisibility(View.GONE);
            syncPowerInhibit();
          } else {
            showControls();
          }
        });
    root.setOnApplyWindowInsetsListener(
        (v, i) -> {
          controls.setPadding(
              pad + i.getSystemWindowInsetLeft(),
              Ui.dp(this, 10),
              pad + i.getSystemWindowInsetRight(),
              Ui.dp(this, 10) + i.getSystemWindowInsetBottom());
          return i;
        });
  }

  private void openManage(String tab) {
    android.content.Intent i = new android.content.Intent(this, MainActivity.class);
    i.putExtra("tab", tab);
    i.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
    startActivity(i);
    finish();
  }

  private void exitApp() {
    new AlertDialog.Builder(this)
        .setTitle("앱을 종료할까요?")
        .setMessage("누니담 액자를 완전히 종료합니다.\n화면이 홈으로 돌아갑니다.")
        .setPositiveButton(
            "종료",
            (d, w) -> {
              finishAffinity();
              android.content.Intent home =
                  new android.content.Intent(android.content.Intent.ACTION_MAIN);
              home.addCategory(android.content.Intent.CATEGORY_HOME);
              home.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
              try {
                startActivity(home);
              } catch (Exception ignored) {
              }
            })
        .setNegativeButton("취소", null)
        .show();
  }

  private void pauseState() {
    boolean paused = session != null && session.paused();
    pause.setText(paused ? "계속 재생" : "일시 정지");
    Ui.tileIcon(pause, paused ? R.drawable.ic_play : R.drawable.ic_pause, Ui.PRIMARY);
  }

  private Button add(
      LinearLayout row, String text, int icon, int color, boolean fill, Runnable r) {
    Button b = Ui.tile(this, text, icon, color, r);
    LinearLayout.LayoutParams p =
        new LinearLayout.LayoutParams(fill ? 0 : Ui.dp(this, 104), Ui.dp(this, 62), fill ? 1 : 0);
    int m = Ui.dp(this, 3);
    p.setMargins(m, m, m, m);
    row.addView(b, p);
    return b;
  }

  protected void onResume() {
    super.onResume();
    Ui.immersive(this);
    capabilities = PowerSaving.DeviceCapabilities.inspect(this);
    displayPower = new PowerSaving.DisplayPowerController(getWindow(), frame, a.prefs);
    powerState = new Core.PowerSavingStateMachine(SystemClock.elapsedRealtime());
    session = new FrameSession(this, frame);
    session.start();
    pauseState();
    charging = new ChargingController(this);
    charging.start();
    startMotionIfEnabled();
    controls.setVisibility(View.GONE);
    h.post(powerTick);
  }

  protected void onPause() {
    h.removeCallbacksAndMessages(null);
    if (motionSource != null) {
      motionSource.stop();
      motionSource = null;
    }
    if (session != null) session.stop();
    if (charging != null) charging.stop();
    super.onPause();
  }

  private void startMotionIfEnabled() {
    if (motionSource != null) {
      motionSource.stop();
      motionSource = null;
    }
    if (a.prefs.motionEnabled() && capabilities.hasAnyCamera && capabilities.cameraPermissionGranted) {
      motionSource = new PowerSaving.CameraMotionSource(this, a.prefs, a.powerDiagnostics, false);
      motionSource.start(
          ev -> {
            if (powerState == null) return;
            powerState.onEvent(ev, currentMinute(), buildPowerConfig());
            evaluatePowerState();
          });
    }
  }

  private void onUserTouch() {
    if (powerState != null) {
      powerState.onEvent(
          Core.PresenceEvent.touch(SystemClock.elapsedRealtime()),
          currentMinute(),
          buildPowerConfig());
      evaluatePowerState();
    }
  }

  private void syncPowerInhibit() {
    if (powerState != null) {
      powerState.setInhibited(
          controls.getVisibility() == View.VISIBLE, SystemClock.elapsedRealtime());
      evaluatePowerState();
    }
  }

  private void evaluatePowerState() {
    if (powerState == null || displayPower == null) return;
    Core.PowerConfig cfg = buildPowerConfig();
    boolean actualOff = PowerSaving.DisplayPowerController.isDisplayActuallyOff(this);
    powerState.notifyObservedScreenOff(actualOff, SystemClock.elapsedRealtime(), cfg);
    Core.PowerState st = powerState.evaluate(SystemClock.elapsedRealtime(), currentMinute(), cfg);
    a.powerDiagnostics.transition(st);
    displayPower.apply(st, capabilities);
    if (session != null) {
      session.setPowerPaused(
          st == Core.PowerState.DIMMED || st == Core.PowerState.SCREEN_OFF || a.prefs.night());
    }
  }

  private Core.PowerConfig buildPowerConfig() {
    boolean sourceOk =
        capabilities != null && capabilities.hasAnyCamera && capabilities.cameraPermissionGranted;
    return new Core.PowerConfig(
        a.prefs.raw.getBoolean("night", true),
        a.prefs.start(),
        a.prefs.end(),
        a.prefs.motionEnabled(),
        a.prefs.idleMinutes() * 60_000L,
        a.prefs.nightReact(),
        "SCREEN_OFF".equals(a.prefs.idleAction()),
        capabilities != null && capabilities.screenOffWakeVerified,
        sourceOk);
  }

  private static int currentMinute() {
    Calendar c = Calendar.getInstance();
    return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
  }

  private boolean isAccessibilityEnabled() {
    AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
    return am != null && am.isEnabled();
  }

  private void showControls() {
    controls.setVisibility(View.VISIBLE);
    syncPowerInhibit();
    Ui.immersive(this);
    h.removeCallbacks(hide);
    if (!isAccessibilityEnabled()) {
      h.postDelayed(hide, 5000);
    }
  }
}
