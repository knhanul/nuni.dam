package co.kr.nuni.dam;

import android.app.*;
import android.graphics.Bitmap;
import android.net.*;
import android.os.*;
import android.view.WindowManager;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.net.*;
import java.util.*;

public final class TransferActivity extends Activity {
  private TransferServer server;
  private TextView status, stateBadge;
  private AlertDialog approval;
  private final Handler h = new Handler(Looper.getMainLooper());
  private final Runnable clock =
      new Runnable() {
        public void run() {
          if (server == null) return;
          long t = server.remaining();
          status.setText(
              t > 0
                  ? "남은 유효 시간: "
                      + t / 60
                      + "분 "
                      + t % 60
                      + "초\n전송이 끝날 때까지 이 화면을 열어두세요. 화면이 잠시 꺼져도 서버는 유지됩니다."
                  : "연결 시간이 만료되었습니다. ‘새 QR 코드 만들기’를 눌러 다시 연결해 주세요.");
          if (t == 0) {
            stateBadge.setText("만료됨 · 새 QR 생성 필요");
            stateBadge.setTextColor(Ui.DANGER);
            if (server != null) server.stop();
            return;
          }
          h.postDelayed(this, 1000);
        }
      };

  public void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  protected void onResume() {
    super.onResume();
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    if (server == null) start();
  }

  protected void onPause() {
    // Do NOT stop the server here. Screen off / brief background should not kill active transfers.
    super.onPause();
  }

  protected void onDestroy() {
    stop();
    super.onDestroy();
  }

  private void stop() {
    h.removeCallbacksAndMessages(null);
    if (approval != null) {
      approval.dismiss();
      approval = null;
    }
    if (server != null) {
      server.stop();
      server = null;
    }
  }

  private void start() {
    stop();
    LinearLayout root =
        Ui.page(
            this,
            "스마트폰에서 사진 보내기",
            "태블릿과 스마트폰을 같은 개인 Wi-Fi에 연결한 뒤 QR 코드를 스캔하세요.");
    LinearLayout card = Ui.card(root);
    Ui.section(card, R.drawable.ic_phone, "QR 코드와 연결 상태");
    stateBadge = Ui.text(this, "연결 대기 중 · 스마트폰으로 QR을 스캔하세요", 14, Ui.PRIMARY);
    card.addView(stateBadge);
    Ui.gap(card, 10);
    ImageView qr = new ImageView(this);
    qr.setContentDescription("사진 전송 QR 코드");
    card.addView(qr, new LinearLayout.LayoutParams(-1, Ui.dp(this, 260)));
    Ui.gap(card, 8);
    TextView address = Ui.text(this, "", 14, Ui.INK);
    address.setTextIsSelectable(true);
    card.addView(address);
    status = Ui.text(this, "연결 준비 중", 14, Ui.MUTED);
    card.addView(status);
    Ui.gap(card, 8);
    Ui.add(
        card,
        "전송 완료 후 액자 시작",
        true,
        R.drawable.ic_play,
        () -> {
          stop();
          Ui.open(this, FrameActivity.class);
          finish();
        });
    Ui.add(card, "새 QR 코드 만들기", false, R.drawable.ic_refresh, this::start);
    Ui.add(
        card,
        "연결 종료하고 닫기",
        false,
        R.drawable.ic_exit,
        () -> {
          stop();
          finish();
        });
    root.addView(
        Ui.text(
            this,
            "1. 스마트폰 카메라로 위 QR 코드를 스캔합니다.\n"
                + "2. 태블릿에 뜨는 승인 창에서 연결을 허용합니다.\n"
                + "3. 스마트폰에서 사진을 보내거나 문구·분위기를 바꾼 뒤 ‘전송 완료 후 액자 시작’을 누르세요.\n\n"
                + "개인 Wi-Fi에서만 사용하세요. 게스트 Wi-Fi의 기기 간 차단, VPN, IPv6 전용망에서는 연결되지 않을 수 있습니다.\n"
                + "이 화면을 닫거나 ‘연결 종료하고 닫기’를 누르면 서버가 종료됩니다. 화면이 잠시 꺼져도 전송은 계속됩니다.",
            14,
            Ui.MUTED));
    try {
      String ip = ip();
      if (ip == null)
        throw new IllegalStateException(
            "Wi-Fi 또는 유선 네트워크에 연결한 뒤 다시 시도해 주세요.\n"
                + "(게스트 네트워크의 기기 간 통신 차단이나 IPv6 전용 환경일 수 있습니다.)");
      server = new TransferServer(this, ip, this::approve);
      server.start(30000, true); // longer accept timeout
      Map<EncodeHintType, Object> hints = new HashMap<>();
      hints.put(EncodeHintType.MARGIN, 2);
      BitMatrix matrix =
          new QRCodeWriter().encode(server.qr(), BarcodeFormat.QR_CODE, 600, 600, hints);
      int[] pixels = new int[360000];
      for (int y = 0; y < 600; y++)
        for (int x = 0; x < 600; x++)
          pixels[y * 600 + x] = matrix.get(x, y) ? Ui.INK : 0xffffffff;
      qr.setImageBitmap(Bitmap.createBitmap(pixels, 600, 600, Bitmap.Config.ARGB_8888));
      address.setText("접속 주소: " + server.address());
      h.post(clock);
    } catch (Exception e) {
      stateBadge.setText("네트워크 확인 필요");
      stateBadge.setTextColor(Ui.DANGER);
      status.setText(e.getMessage());
      if (server != null) {
        server.stop();
        server = null;
      }
    }
  }

  private void approve(String ip) {
    if (!Ui.alive(this) || server == null) return;
    if (approval != null && approval.isShowing()) return; // avoid stacking
    TransferServer current = server;
    stateBadge.setText("연결 승인 요청 도착 (" + ip + ")");
    approval =
        new AlertDialog.Builder(this)
            .setTitle("스마트폰 연결을 허용할까요?")
            .setMessage(
                "요청 기기: "
                    + ip
                    + "\n허용할 작업 범위: 사진 업로드, 사진별 기록·즐겨찾기·휴지통 이동, 액자 화면 분위기 변경")
            .setPositiveButton(
                "연결 허용",
                (d, k) -> {
                  if (server == current) {
                    current.approve(true);
                    stateBadge.setText("연결 승인됨 (" + ip + ") · 사진 전송 가능");
                    stateBadge.setTextColor(Ui.PRIMARY);
                  }
                })
            .setNegativeButton(
                "거절",
                (d, k) -> {
                  if (server == current) {
                    current.approve(false);
                    stateBadge.setText("연결 거절됨 · 다시 연결하려면 새 QR 코드를 만드세요");
                    stateBadge.setTextColor(Ui.DANGER);
                  }
                })
            .setOnCancelListener(
                d -> {
                  if (server == current) {
                    current.approve(false);
                    stateBadge.setText("연결 취소됨");
                  }
                })
            .setCancelable(true)
            .show();
  }

  private String ip() {
    ConnectivityManager m = getSystemService(ConnectivityManager.class);
    if (m == null) return null;
    String fallback = null;
    for (Network n : m.getAllNetworks()) {
      NetworkCapabilities caps = m.getNetworkCapabilities(n);
      if (caps == null
          || (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
              && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
      LinkProperties p = m.getLinkProperties(n);
      if (p == null) continue;
      for (LinkAddress l : p.getLinkAddresses()) {
        InetAddress addr = l.getAddress();
        if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
        String host = addr.getHostAddress();
        if (addr.isSiteLocalAddress()) return host; // prefer private LAN
        if (fallback == null) fallback = host;
      }
    }
    return fallback;
  }
}
