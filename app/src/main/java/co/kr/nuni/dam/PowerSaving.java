package co.kr.nuni.dam;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.*;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.view.*;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Runtime diagnostics, presence detection sources, display power controller,
 * and camera luma motion detector (Section 7).
 */
public final class PowerSaving {
  private PowerSaving() {}

  public interface PresenceSource {
    public interface Listener {
      void onPresence(Core.PresenceEvent event);

      default void onFramePreview(
          Bitmap preview,
          float score,
          float threshold,
          boolean detected,
          Core.SourceHealth health,
          String detail) {}
    }

    void start(Listener l);

    void stop();

    Core.SourceHealth health();
  }

  public static final class DeviceCapabilities {
    public final int sdkInt;
    public final boolean hasFrontCamera;
    public final boolean hasAnyCamera;
    public final String cameraId;
    public final boolean cameraPermissionGranted;
    public final boolean hasLightSensor;
    public final String lightSensorSummary;
    public final boolean hasProximitySensor;
    public final String proximitySensorSummary;
    public final boolean proximityWakeUp;
    public final boolean dimmingSupported;
    public final boolean screenOffWakeVerified;

    public DeviceCapabilities(
        int sdkInt,
        boolean hasFrontCamera,
        boolean hasAnyCamera,
        String cameraId,
        boolean cameraPermissionGranted,
        boolean hasLightSensor,
        String lightSensorSummary,
        boolean hasProximitySensor,
        String proximitySensorSummary,
        boolean proximityWakeUp,
        boolean dimmingSupported,
        boolean screenOffWakeVerified) {
      this.sdkInt = sdkInt;
      this.hasFrontCamera = hasFrontCamera;
      this.hasAnyCamera = hasAnyCamera;
      this.cameraId = cameraId;
      this.cameraPermissionGranted = cameraPermissionGranted;
      this.hasLightSensor = hasLightSensor;
      this.lightSensorSummary = lightSensorSummary;
      this.hasProximitySensor = hasProximitySensor;
      this.proximitySensorSummary = proximitySensorSummary;
      this.proximityWakeUp = proximityWakeUp;
      this.dimmingSupported = dimmingSupported;
      this.screenOffWakeVerified = screenOffWakeVerified;
    }

    public static DeviceCapabilities inspect(Context c) {
      int sdk = Build.VERSION.SDK_INT;
      boolean camPerm =
          c.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
      boolean front = false, any = false;
      String chosenId = null;
      try {
        CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        if (cm != null) {
          for (String id : cm.getCameraIdList()) {
            any = true;
            CameraCharacteristics cc = cm.getCameraCharacteristics(id);
            Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
              front = true;
              chosenId = id;
              break;
            }
            if (chosenId == null) chosenId = id;
          }
        }
      } catch (Exception ignored) {
      }
      boolean light = false, prox = false, proxWake = false;
      String lightInfo = "미지원", proxInfo = "미지원";
      try {
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        if (sm != null) {
          Sensor ls = sm.getDefaultSensor(Sensor.TYPE_LIGHT);
          if (ls != null) {
            light = true;
            lightInfo =
                String.format(
                    Locale.KOREA,
                    "지원 (최대 %.0f lx · 해상도 %.2f)",
                    ls.getMaximumRange(),
                    ls.getResolution());
          }
          Sensor ps = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
          if (ps != null) {
            prox = true;
            proxWake = ps.isWakeUpSensor();
            proxInfo =
                String.format(
                    Locale.KOREA,
                    "존재 (최대 %.1f cm · 해상도 %.1f · wake-up %s — 원거리 사람 감지용 아님)",
                    ps.getMaximumRange(),
                    ps.getResolution(),
                    proxWake ? "예" : "아니오");
          }
        }
      } catch (Exception ignored) {
      }
      // Per Section 7.3 & 7.6: Real screen OFF + automatic wake requires hardware/OS verification.
      // We never fabricate screen-off wake verification on unverified hardware.
      boolean verifiedWake = false;
      return new DeviceCapabilities(
          sdk, front, any, chosenId, camPerm, light, lightInfo, prox, proxInfo, proxWake, true, verifiedWake);
    }

    public String motionSupportLabel() {
      if (!hasAnyCamera) return "미지원 (카메라 없음)";
      if (!cameraPermissionGranted) return "설정 필요 (카메라 권한 필요)";
      return hasFrontCamera ? "지원 (전면 카메라 밝기 변화 감지)" : "지원 (카메라 밝기 변화 감지)";
    }

    public String screenOffSupportLabel() {
      return screenOffWakeVerified
          ? "지원 (검증됨)"
          : "아직 검증되지 않음 · 화면 감광(어둡게) 대체 동작 제공";
    }
  }

  public static final class Diagnostics {
    private Core.PowerState currentState = Core.PowerState.ACTIVE;
    private long stateEnteredElapsedMs = SystemClock.elapsedRealtime();
    private long activeMs, idlePendingMs, dimmedMs, screenOffMs, unavailableMs;
    private int motionEvents, falseOrIgnoredEvents, errorCount;
    private String lastError = "오류 없음";
    private boolean cameraInUse;

    public synchronized void transition(Core.PowerState next) {
      long now = SystemClock.elapsedRealtime();
      long delta = Math.max(0L, now - stateEnteredElapsedMs);
      accumulate(currentState, delta);
      currentState = next;
      stateEnteredElapsedMs = now;
    }

    private void accumulate(Core.PowerState s, long delta) {
      switch (s) {
        case ACTIVE:
          activeMs += delta;
          break;
        case IDLE_PENDING:
          idlePendingMs += delta;
          break;
        case DIMMED:
          dimmedMs += delta;
          break;
        case SCREEN_OFF:
          screenOffMs += delta;
          break;
        case UNAVAILABLE:
          unavailableMs += delta;
          break;
      }
    }

    public synchronized void recordMotion(boolean accepted) {
      if (accepted) motionEvents++;
      else falseOrIgnoredEvents++;
    }

    public synchronized void recordError(String message) {
      errorCount++;
      lastError = message == null || message.isEmpty() ? "감지 오류" : message;
    }

    public synchronized void setCameraInUse(boolean inUse) {
      cameraInUse = inUse;
    }

    public synchronized boolean isCameraInUse() {
      return cameraInUse;
    }

    public synchronized Core.PowerState currentState() {
      return currentState;
    }

    public synchronized String summaryText() {
      long now = SystemClock.elapsedRealtime();
      long curDelta = Math.max(0L, now - stateEnteredElapsedMs);
      long act = activeMs + (currentState == Core.PowerState.ACTIVE ? curDelta : 0);
      long dim = dimmedMs + (currentState == Core.PowerState.DIMMED ? curDelta : 0);
      long off = screenOffMs + (currentState == Core.PowerState.SCREEN_OFF ? curDelta : 0);
      return String.format(
          Locale.KOREA,
          "현재 상태: %s · 카메라 사용: %s\n"
              + "누적 표시 %d분 · 감광 %d분 · 꺼짐 %d분\n"
              + "유효 움직임 %d회 · 무시된 변화 %d회 · 오류 %d회 (%s)\n"
              + "전력 절감량: 절감량은 아직 측정하지 않았어요.",
          stateLabel(currentState),
          cameraInUse ? "동작 중" : "사용 안 함",
          act / 60000L,
          dim / 60000L,
          off / 60000L,
          motionEvents,
          falseOrIgnoredEvents,
          errorCount,
          lastError);
    }

    public static String stateLabel(Core.PowerState s) {
      switch (s) {
        case ACTIVE:
          return "표시 중 (ACTIVE)";
        case IDLE_PENDING:
          return "부재 대기 중 (IDLE_PENDING)";
        case DIMMED:
          return "잠시 쉬는 중 · 감광 (DIMMED)";
        case SCREEN_OFF:
          return "화면 꺼짐 (SCREEN_OFF)";
        case UNAVAILABLE:
          return "감지 경로 사용 불가 (UNAVAILABLE)";
        default:
          return s.name();
      }
    }
  }

  /**
   * Low-resolution Y-channel camera motion detector (Section 7.5).
   * Uses 160x120 or 320x240 YUV_420_888 at ~2 fps, keeps only latest frame,
   * always closes Image frames, and never stores or transmits video.
   */
  public static final class CameraMotionSource implements PresenceSource {
    private final Context context;
    private final Prefs prefs;
    private final Diagnostics diag;
    private final boolean buildPreviewBitmap;
    private HandlerThread thread;
    private Handler handler;
    private final Handler main = new Handler(Looper.getMainLooper());
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Listener listener;
    private volatile Core.SourceHealth health = Core.SourceHealth.UNKNOWN;
    private byte[] prevLuma;
    private int consecutiveHits;
    private long lastAnalyzeMs;

    public CameraMotionSource(
        Context c, Prefs p, Diagnostics d, boolean buildPreview) {
      context = c.getApplicationContext();
      prefs = p;
      diag = d;
      buildPreviewBitmap = buildPreview;
    }

    @Override
    public Core.SourceHealth health() {
      return health;
    }

    @Override
    public synchronized void start(Listener l) {
      stop();
      listener = l;
      DeviceCapabilities caps = DeviceCapabilities.inspect(context);
      if (!caps.hasAnyCamera || caps.cameraId == null) {
        health = Core.SourceHealth.UNSUPPORTED;
        diag.recordError("카메라 없음");
        emit(Core.PresenceEvent.failure(SystemClock.elapsedRealtime(), Core.PresenceKind.CAMERA_MOTION, health));
        return;
      }
      if (!caps.cameraPermissionGranted) {
        health = Core.SourceHealth.PERMISSION_DENIED;
        diag.recordError("카메라 권한 없음");
        emit(Core.PresenceEvent.failure(SystemClock.elapsedRealtime(), Core.PresenceKind.CAMERA_MOTION, health));
        return;
      }
      thread = new HandlerThread("nuni-motion");
      thread.start();
      handler = new Handler(thread.getLooper());
      handler.post(() -> openCamera(caps.cameraId));
    }

    @Override
    public synchronized void stop() {
      listener = null;
      diag.setCameraInUse(false);
      if (handler != null) {
        handler.post(this::closeCameraInternal);
      }
      if (thread != null) {
        thread.quitSafely();
        thread = null;
      }
      handler = null;
      prevLuma = null;
      consecutiveHits = 0;
    }

    private void openCamera(String camId) {
      try {
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) throw new IllegalStateException("카메라 서비스를 열 수 없습니다.");
        reader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2);
        reader.setOnImageAvailableListener(this::onFrame, handler);
        cm.openCamera(
            camId,
            new CameraDevice.StateCallback() {
              @Override
              public void onOpened(CameraDevice cd) {
                camera = cd;
                diag.setCameraInUse(true);
                createSession();
              }

              @Override
              public void onDisconnected(CameraDevice cd) {
                health = Core.SourceHealth.ERROR;
                diag.setCameraInUse(false);
                diag.recordError("카메라 연결이 끊겼습니다.");
                emit(Core.PresenceEvent.failure(SystemClock.elapsedRealtime(), Core.PresenceKind.CAMERA_MOTION, health));
                closeCameraInternal();
              }

              @Override
              public void onError(CameraDevice cd, int error) {
                health = Core.SourceHealth.ERROR;
                diag.setCameraInUse(false);
                diag.recordError("카메라 점유 또는 오류(" + error + ")");
                emit(Core.PresenceEvent.failure(SystemClock.elapsedRealtime(), Core.PresenceKind.CAMERA_MOTION, health));
                closeCameraInternal();
              }
            },
            handler);
      } catch (SecurityException se) {
        health = Core.SourceHealth.PERMISSION_DENIED;
        diag.setCameraInUse(false);
        diag.recordError("카메라 권한이 철회되었습니다.");
        emit(Core.PresenceEvent.failure(SystemClock.elapsedRealtime(), Core.PresenceKind.CAMERA_MOTION, health));
      } catch (Exception e) {
        health = Core.SourceHealth.ERROR;
        diag.setCameraInUse(false);
        diag.recordError(e.getMessage());
        emit(Core.PresenceEvent.failure(SystemClock.elapsedRealtime(), Core.PresenceKind.CAMERA_MOTION, health));
      }
    }

    private void createSession() {
      if (camera == null || reader == null) return;
      try {
        Surface surface = reader.getSurface();
        CaptureRequest.Builder req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        req.addTarget(surface);
        camera.createCaptureSession(
            Collections.singletonList(surface),
            new CameraCaptureSession.StateCallback() {
              @Override
              public void onConfigured(CameraCaptureSession cs) {
                if (camera == null) return;
                session = cs;
                try {
                  session.setRepeatingRequest(req.build(), null, handler);
                  health = Core.SourceHealth.OK;
                } catch (Exception e) {
                  health = Core.SourceHealth.ERROR;
                  diag.recordError("카메라 세션 시작 실패");
                }
              }

              @Override
              public void onConfigureFailed(CameraCaptureSession cs) {
                health = Core.SourceHealth.ERROR;
                diag.recordError("카메라 구성 실패");
              }
            },
            handler);
      } catch (Exception e) {
        health = Core.SourceHealth.ERROR;
        diag.recordError(e.getMessage());
      }
    }

    private void onFrame(ImageReader r) {
      Image img = null;
      try {
        img = r.acquireLatestImage();
        if (img == null) return;
        long now = SystemClock.elapsedRealtime();
        // Throttle analysis to ~2 fps (500ms)
        if (now - lastAnalyzeMs < 480L) return;
        lastAnalyzeMs = now;
        int w = img.getWidth(), h = img.getHeight();
        Image.Plane yPlane = img.getPlanes()[0];
        ByteBuffer buf = yPlane.getBuffer();
        int rowStride = yPlane.getRowStride();
        int pixelStride = yPlane.getPixelStride();
        byte[] curr = new byte[w * h];
        for (int y = 0; y < h; y++) {
          int rowStart = y * rowStride;
          for (int x = 0; x < w; x++) {
            int idx = rowStart + x * pixelStride;
            if (idx < buf.limit()) curr[y * w + x] = buf.get(idx);
          }
        }
        int sens = prefs.motionSensitivity(); // 1=낮음, 2=보통, 3=민감
        int diffThreshold = sens == 3 ? 14 : sens == 1 ? 24 : 18;
        float ratioThreshold = sens == 3 ? 0.025f : sens == 1 ? 0.065f : 0.04f;
        float score = 0f;
        boolean confirmed = false;
        if (prevLuma != null && prevLuma.length == curr.length) {
          score =
              Core.lumaMotionScore(
                  prevLuma,
                  curr,
                  w,
                  h,
                  prefs.roiLeft(),
                  prefs.roiTop(),
                  prefs.roiRight(),
                  prefs.roiBottom(),
                  diffThreshold);
          if (score >= ratioThreshold) {
            consecutiveHits++;
            if (consecutiveHits >= 2) {
              confirmed = true;
              consecutiveHits = 0;
              diag.recordMotion(true);
            } else {
              diag.recordMotion(false);
            }
          } else {
            consecutiveHits = 0;
          }
        }
        prevLuma = curr;
        health = Core.SourceHealth.OK;
        emit(Core.PresenceEvent.motion(now, confirmed, score));
        if (buildPreviewBitmap && listener != null) {
          Bitmap bmp = lumaToBitmap(curr, w, h);
          float sc = score;
          boolean det = confirmed;
          Listener l = listener;
          main.post(
              () -> {
                if (l != null)
                  l.onFramePreview(
                      bmp, sc, ratioThreshold, det, health, det ? "움직임 감지됨" : "주변 살피는 중");
              });
        }
      } catch (Exception ignored) {
      } finally {
        if (img != null) img.close();
      }
    }

    private static Bitmap lumaToBitmap(byte[] luma, int w, int h) {
      int[] pixels = new int[w * h];
      for (int i = 0; i < luma.length; i++) {
        int v = luma[i] & 0xff;
        pixels[i] = 0xff000000 | (v << 16) | (v << 8) | v;
      }
      return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
    }

    private void emit(Core.PresenceEvent ev) {
      Listener l = listener;
      if (l != null) main.post(() -> l.onPresence(ev));
    }

    private void closeCameraInternal() {
      try {
        if (session != null) {
          session.close();
          session = null;
        }
      } catch (Exception ignored) {
      }
      try {
        if (camera != null) {
          camera.close();
          camera = null;
        }
      } catch (Exception ignored) {
      }
      try {
        if (reader != null) {
          reader.close();
          reader = null;
        }
      } catch (Exception ignored) {
      }
      diag.setCameraInUse(false);
    }
  }

  /**
   * Separates display power requests (dimming / allowing screen off) from observed display state
   * (Section 7.6 & Section 10).
   */
  public static final class DisplayPowerController {
    private final Window window;
    private final FrameView frameView;
    private final Prefs prefs;

    public DisplayPowerController(Window w, FrameView fv, Prefs p) {
      window = w;
      frameView = fv;
      prefs = p;
    }

    public void apply(Core.PowerState state, DeviceCapabilities caps) {
      if (window == null) return;
      WindowManager.LayoutParams lp = window.getAttributes();
      boolean dimmed =
          state == Core.PowerState.DIMMED
              || state == Core.PowerState.SCREEN_OFF
              || prefs.night();
      lp.screenBrightness = dimmed ? 0.01f : prefs.brightness() / 100f;
      window.setAttributes(lp);
      // Only release FLAG_KEEP_SCREEN_ON if real screen-off & wake is verified AND requested
      boolean allowSystemOff =
          state == Core.PowerState.DIMMED
              && "SCREEN_OFF".equals(prefs.idleAction())
              && caps != null
              && caps.screenOffWakeVerified;
      if (allowSystemOff) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      } else {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      }
      if (frameView != null) {
        String msg = "";
        if (dimmed) {
          msg =
              prefs.motionEnabled() && caps != null && caps.cameraPermissionGranted
                  ? "잠시 쉬는 중 · 움직이거나 화면을 터치하면 밝아집니다."
                  : "잠시 쉬는 중 · 화면을 터치하면 밝아집니다.";
        }
        frameView.setDimmedOverlay(dimmed, msg);
      }
    }

    public static boolean isDisplayActuallyOff(Context c) {
      try {
        android.hardware.display.DisplayManager dm =
            (android.hardware.display.DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) {
          Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
          if (d != null) return d.getState() == Display.STATE_OFF;
        }
      } catch (Exception ignored) {
      }
      return false;
    }
  }
}
