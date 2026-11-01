package co.kr.nuni.dam;

import android.os.*;
import android.service.dreams.DreamService;
import android.view.WindowManager;

public final class NuniDream extends DreamService {
  private FrameView view;
  private FrameSession session;
  private final Handler h = new Handler(Looper.getMainLooper());
  private final Runnable dim =
      new Runnable() {
        public void run() {
          if (getWindow() != null) {
            Prefs p = NuniApp.of(NuniDream.this).prefs;
            boolean night = p.night();
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = night ? .01f : p.brightness() / 100f;
            getWindow().setAttributes(lp);
            if (view != null) {
              view.setDimmedOverlay(night, night ? "잠시 쉬는 중 · 화면을 터치하면 밝아집니다." : "");
            }
            if (session != null) session.setPowerPaused(night);
          }
          h.postDelayed(this, 15000);
        }
      };

  public void onAttachedToWindow() {
    super.onAttachedToWindow();
    setInteractive(false);
    setFullscreen(true);
    setScreenBright(true);
    view = new FrameView(this);
    setContentView(view);
  }

  public void onDreamingStarted() {
    super.onDreamingStarted();
    session = new FrameSession(this, view);
    session.start();
    h.post(dim);
  }

  public void onDreamingStopped() {
    h.removeCallbacksAndMessages(null);
    if (session != null) session.stop();
    super.onDreamingStopped();
  }

  public void onDetachedFromWindow() {
    h.removeCallbacksAndMessages(null);
    if (session != null) session.stop();
    super.onDetachedFromWindow();
  }
}
