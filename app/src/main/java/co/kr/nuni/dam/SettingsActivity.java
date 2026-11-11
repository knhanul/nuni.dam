package co.kr.nuni.dam;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.*;

public final class SettingsActivity extends Activity {
  private NuniApp a;
  private Controller controller;

  public void onCreate(Bundle b) {
    super.onCreate(b);
    a = NuniApp.of(this);
    getWindow().setStatusBarColor(Ui.BG);
    getWindow().setNavigationBarColor(Ui.BG);
    LinearLayout root = Ui.column(this);
    root.setBackgroundColor(Ui.BG);
    int pad = Ui.dp(this, Ui.outerPaddingDp(getResources().getConfiguration().screenWidthDp));
    root.setPadding(pad, Ui.dp(this, 16), pad, Ui.dp(this, 16));
    setContentView(root);
    root.setOnApplyWindowInsetsListener(
        (v, i) -> {
          v.setPadding(
              pad + i.getSystemWindowInsetLeft(),
              Ui.dp(this, 16) + i.getSystemWindowInsetTop(),
              pad + i.getSystemWindowInsetRight(),
              Ui.dp(this, 16) + i.getSystemWindowInsetBottom());
          return i;
        });
    root.requestApplyInsets();
    controller = buildView(root, this, null);
  }

  public static Controller buildView(
      LinearLayout container, Activity host, Runnable onOpenCustomize) {
    Controller c = new Controller(host, onOpenCustomize);
    c.attach(container);
    return c;
  }

  public void handleActivityResult(int req, int result, Intent data) {
    if (controller != null) controller.onActivityResult(req, result, data);
  }

  @Override
  protected void onActivityResult(int req, int result, Intent data) {
    super.onActivityResult(req, result, data);
    handleActivityResult(req, result, data);
  }

  public static final class Controller {
    private final Activity host;
    private final NuniApp a;
    private final Runnable onOpenCustomize;
    private LinearLayout rootContainer;
    private String selectedGroup = "POWER"; // POWER, STORAGE, LAUNCH, BACKUP, ADVANCED, ABOUT
    private LinearLayout detailPane;

    public Controller(Activity h, Runnable openCustomize) {
      host = h;
      a = NuniApp.of(h);
      onOpenCustomize = openCustomize;
    }

    public void attach(LinearLayout parent) {
      rootContainer = parent;
      rebuild();
    }

    public void rebuild() {
      if (rootContainer == null || !Ui.alive(host)) return;
      rootContainer.removeAllViews();
      int widthDp = host.getResources().getConfiguration().screenWidthDp;
      boolean twoPane = widthDp >= 840;

      Ui.header(host, rootContainer, "설정", "설정은 항목별로 정리되어 있으며 변경 즉시 저장됩니다.");
      Ui.gap(rootContainer, 14);

      if (twoPane) {
        LinearLayout split = Ui.row(host);
        split.setGravity(Gravity.TOP);
        rootContainer.addView(split, new LinearLayout.LayoutParams(-1, 0, 1));

        ScrollView leftScroll = new ScrollView(host);
        LinearLayout leftCol = Ui.column(host);
        leftScroll.addView(leftCol);
        buildGroupList(leftCol, true);
        split.addView(leftScroll, new LinearLayout.LayoutParams(0, -1, 4));

        ScrollView rightScroll = new ScrollView(host);
        detailPane = Ui.column(host);
        detailPane.setPadding(Ui.dp(host, 20), 0, 0, Ui.dp(host, 24));
        rightScroll.addView(detailPane);
        split.addView(rightScroll, new LinearLayout.LayoutParams(0, -1, 6));
        renderGroupDetail(detailPane, selectedGroup);
      } else {
        ScrollView scroll = new ScrollView(host);
        LinearLayout col = Ui.column(host);
        scroll.addView(col);
        rootContainer.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        buildGroupList(col, false);
      }
    }

    private void buildGroupList(LinearLayout col, boolean twoPane) {
      PowerSaving.DeviceCapabilities caps = PowerSaving.DeviceCapabilities.inspect(host);
      LinearLayout groupCard = Ui.card(col);
      Ui.section(groupCard, R.drawable.ic_tune, "설정 그룹");
      String remoteIp = StudioServer.resolveLocalIp(host);
      boolean srvAlive = a.isStudioServerRunning();
      addGroupItem(
          groupCard,
          "REMOTE",
          R.drawable.ic_phone,
          "원격 관리 (누니담 스튜디오)",
          srvAlive
              ? ("활성화됨 · "
                  + (remoteIp == null
                      ? "Wi-Fi 확인 필요"
                      : "http://" + remoteIp + ":" + a.studioServer().port()))
              : "꺼짐 · PC/스마트폰 웹 브라우저에서 앨범·장면·편성표 관리",
          twoPane);
      groupCard.addView(Ui.divider(host), new LinearLayout.LayoutParams(-1, Ui.dp(host, 1)));
      addGroupItem(
          groupCard,
          "POWER",
          R.drawable.ic_moon,
          "화면과 절전",
          String.format(
              Locale.KOREA,
              "밝기 %d%% · 야간 %s~%s · 주변 움직임 반응 %s",
              a.prefs.brightness(),
              Core.timeText(a.prefs.start()),
              Core.timeText(a.prefs.end()),
              a.prefs.motionEnabled() && caps.cameraPermissionGranted ? "켜짐" : "꺼짐"),
          twoPane);
      groupCard.addView(Ui.divider(host), new LinearLayout.LayoutParams(-1, Ui.dp(host, 1)));
      addGroupItem(
          groupCard,
          "STORAGE",
          R.drawable.ic_folder,
          "사진 보관",
          String.format(
              Locale.KOREA,
              "상한 %d MB · 폴더 %s · 얼굴 분석 %s",
              a.prefs.quota(),
              a.prefs.folder().isEmpty() ? "미연결" : "연결됨",
              a.prefs.ai() ? "켜짐" : "꺼짐"),
          twoPane);
      groupCard.addView(Ui.divider(host), new LinearLayout.LayoutParams(-1, Ui.dp(host, 1)));
      addGroupItem(
          groupCard,
          "LAUNCH",
          R.drawable.ic_home,
          "액자 실행",
          "시스템 화면보호기 · 전용 액자 홈 · 재부팅 시작 알림",
          twoPane);
      groupCard.addView(Ui.divider(host), new LinearLayout.LayoutParams(-1, Ui.dp(host, 1)));
      addGroupItem(
          groupCard,
          "BACKUP",
          R.drawable.ic_upload,
          "설정 옮기기",
          "JSON 내보내기 · 가져오기 (사진·권한·웹훅 제외)",
          twoPane);
      groupCard.addView(Ui.divider(host), new LinearLayout.LayoutParams(-1, Ui.dp(host, 1)));
      addGroupItem(
          groupCard,
          "ADVANCED",
          R.drawable.ic_bolt,
          "고급",
          (a.prefs.low() ? "저사양 모드 켜짐" : "저사양 모드 꺼짐")
              + " · 절전 진단 · 실험적 충전 보조",
          twoPane);
      groupCard.addView(Ui.divider(host), new LinearLayout.LayoutParams(-1, Ui.dp(host, 1)));
      addGroupItem(
          groupCard,
          "ABOUT",
          R.drawable.ic_screen,
          "앱 정보",
          "누니담 0.1.0 · 오픈소스 고지 · 실제 구현 범위",
          twoPane);

      LinearLayout linkCard = Ui.card(col);
      Ui.section(linkCard, R.drawable.ic_scene, "앱 내 스튜디오 콘텐츠 · 편성 관리");
      Ui.settingRow(
          linkCard,
          R.drawable.ic_folder,
          "앨범 관리",
          "앨범 생성·정렬·대표 사진·수록 사진 정리",
          () -> openStudioTabOrActivity("ALBUMS", AlbumsActivity.class));
      linkCard.addView(Ui.divider(host), new LinearLayout.LayoutParams(-1, Ui.dp(host, 1)));
      Ui.settingRow(
          linkCard,
          R.drawable.ic_quote,
          "글귀 라이브러리",
          "카테고리별 명언·시 편집, CSV 불러오기·내보내기",
          () -> openStudioTabOrActivity("TEXTS", QuotesActivity.class));
      linkCard.addView(Ui.divider(host), new LinearLayout.LayoutParams(-1, Ui.dp(host, 1)));
      Ui.settingRow(
          linkCard,
          R.drawable.ic_scene,
          "재생 장면(Scene) 관리",
          "실시간 미리보기와 함께 레이아웃·색감·연결 앨범/글귀 구성",
          () -> openStudioTabOrActivity("SCENES", ScenesActivity.class));
      linkCard.addView(Ui.divider(host), new LinearLayout.LayoutParams(-1, Ui.dp(host, 1)));
      Ui.settingRow(
          linkCard,
          R.drawable.ic_calendar,
          "주간 편성표 관리",
          "요일별·시간대별 재생 장면 자동 전환 및 임시 즉시 재생",
          () -> openStudioTabOrActivity("SCHEDULES", SchedulesActivity.class));
      if (onOpenCustomize != null) {
        linkCard.addView(Ui.divider(host), new LinearLayout.LayoutParams(-1, Ui.dp(host, 1)));
        Ui.settingRow(
            linkCard,
            R.drawable.ic_sun,
            "간편 화면 꾸미기",
            "기본 화면 구성과 색감을 빠르게 변경합니다.",
            onOpenCustomize);
      }
    }

    private void openStudioTabOrActivity(String tabKey, Class<? extends Activity> fallbackCls) {
      if (host instanceof MainActivity) {
        ((MainActivity) host).switchTab(tabKey);
      } else {
        Ui.open(host, fallbackCls);
      }
    }

    private void addGroupItem(
        LinearLayout card,
        String key,
        int icon,
        String title,
        String summary,
        boolean twoPane) {
      LinearLayout row =
          Ui.settingRow(
              card,
              icon,
              title,
              summary,
              () -> {
                selectedGroup = key;
                if (twoPane && detailPane != null) {
                  rebuild();
                } else {
                  openGroupSheet(key, title);
                }
              });
      if (twoPane && key.equals(selectedGroup)) {
        row.setBackground(Ui.bg(Ui.SURFACE_SUBTLE, Ui.dp(host, 12)));
      }
    }

    private void openGroupSheet(String key, String title) {
      ScrollView sv = new ScrollView(host);
      LinearLayout box = Ui.column(host);
      box.setPadding(Ui.dp(host, 16), Ui.dp(host, 12), Ui.dp(host, 16), Ui.dp(host, 16));
      sv.addView(box);
      renderGroupDetail(box, key);
      AlertDialog d =
          new AlertDialog.Builder(host)
              .setTitle(title)
              .setView(sv)
              .setPositiveButton("완료", (x, y) -> rebuild())
              .create();
      d.setOnDismissListener(x -> rebuild());
      d.show();
    }

    private void renderGroupDetail(LinearLayout pane, String key) {
      pane.removeAllViews();
      switch (key) {
        case "REMOTE":
          buildRemoteSection(pane);
          break;
        case "POWER":
          buildPowerSection(pane);
          break;
        case "STORAGE":
          buildStorageSection(pane);
          break;
        case "LAUNCH":
          buildLaunchSection(pane);
          break;
        case "BACKUP":
          buildBackupSection(pane);
          break;
        case "ADVANCED":
          buildAdvancedSection(pane);
          break;
        case "ABOUT":
          buildAboutSection(pane);
          break;
      }
    }

    // Section 3.1: 설정 → 원격 관리 (누니담 스튜디오 내장 웹서버 제어·QR·인증·세션 관리)
    private void buildRemoteSection(LinearLayout pane) {
      LinearLayout card = Ui.card(pane);
      Ui.section(card, R.drawable.ic_phone, "원격 관리 (NuniDam Studio)");
      card.addView(
          Ui.text(
              host,
              "같은 내부 Wi-Fi에 연결된 PC 또는 스마트폰 웹브라우저에서 사진 앨범, 명언·시, 재생 장면, 요일별 시간표를 편집합니다. 원격 관리를 꺼도 액자 재생은 계속 유지됩니다.",
              14,
              Ui.MUTED));
      Ui.gap(card, 10);

      boolean running = a.isStudioServerRunning();
      Switch remoteSw = new Switch(host);
      remoteSw.setText("원격 관리 켜기 (내장 웹서버 활성화)");
      remoteSw.setTextColor(Ui.INK);
      remoteSw.setTextSize(15.5f);
      remoteSw.setChecked(running);
      remoteSw.setOnCheckedChangeListener(
          (v, on) -> {
            if (on) {
              boolean ok = a.startStudioServer();
              if (!ok) {
                Ui.toast(host, "웹서버 시작 실패: " + a.lastServerError());
              } else {
                Ui.toast(host, "원격 관리 웹서버를 시작했습니다.");
              }
            } else {
              a.prefs.flag("remoteEnabled", false);
              a.stopStudioServer();
              Ui.toast(host, "원격 관리 연결을 종료했습니다. 액자 재생은 유지됩니다.");
            }
            rebuild();
          });
      card.addView(remoteSw);
      Ui.gap(card, 10);

      String ip = StudioServer.resolveLocalIp(host);
      StudioServer srv = a.studioServer();
      String statusText =
          running
              ? (ip != null ? "연결 가능 (내장 웹서버 실행 중)" : "웹서버 실행 중 · Wi-Fi 네트워크 연결 필요")
              : "비활성화됨 (초기 기본값)";
      TextView badge = Ui.text(host, "현재 연결 상태: " + statusText, 14.5f, running && ip != null ? Ui.PRIMARY : Ui.MUTED);
      badge.setTypeface(Typeface.DEFAULT_BOLD);
      card.addView(badge);

      int portVal = running && srv != null ? srv.port() : a.prefs.remotePort();
      String addrStr =
          ip == null
              ? "Wi-Fi에 연결되어 있지 않습니다. 공유기 Wi-Fi 연결을 확인하세요."
              : ("http://" + ip + ":" + portVal);
      TextView addrView = Ui.text(host, "접속 주소: " + addrStr, 15f, Ui.INK);
      addrView.setTextIsSelectable(true);
      card.addView(addrView);
      card.addView(Ui.text(host, "접속 포트: " + portVal + " (내부 로컬 네트워크 전용)", 13.5f, Ui.MUTED));

      long lastAccess = a.prefs.lastRemoteAccessAt();
      String lastAccessStr =
          lastAccess <= 0
              ? "없음"
              : new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
                  .format(new java.util.Date(lastAccess));
      card.addView(Ui.text(host, "마지막 접근 시간: " + lastAccessStr, 13.5f, Ui.MUTED));

      if (running && srv != null && ip != null) {
        Ui.gap(card, 12);
        String pairCode = srv.pairingCode();
        TextView codeTv = Ui.text(host, "일회용 페어링 코드: " + pairCode, 18f, Ui.PRIMARY);
        codeTv.setTypeface(Typeface.DEFAULT_BOLD);
        codeTv.setTextIsSelectable(true);
        card.addView(codeTv);
        card.addView(
            Ui.text(
                host,
                "PC 브라우저에서 위 접속 주소를 열고 일회용 페어링 코드를 입력하거나, 아래 QR 코드를 스캔하세요.",
                13f,
                Ui.MUTED));
        Ui.gap(card, 8);
        try {
          java.util.Map<com.google.zxing.EncodeHintType, Object> hints = new java.util.HashMap<>();
          hints.put(com.google.zxing.EncodeHintType.MARGIN, 2);
          com.google.zxing.common.BitMatrix matrix =
              new com.google.zxing.qrcode.QRCodeWriter()
                  .encode(
                      srv.qrUrl(), com.google.zxing.BarcodeFormat.QR_CODE, 480, 480, hints);
          int[] pixels = new int[480 * 480];
          for (int y = 0; y < 480; y++)
            for (int x = 0; x < 480; x++)
              pixels[y * 480 + x] = matrix.get(x, y) ? Ui.INK : 0xffffffff;
          Bitmap qrBmp = Bitmap.createBitmap(pixels, 480, 480, Bitmap.Config.ARGB_8888);
          ImageView qrIv = new ImageView(host);
          qrIv.setImageBitmap(qrBmp);
          qrIv.setContentDescription("누니담 스튜디오 접속 QR 코드");
          card.addView(qrIv, new LinearLayout.LayoutParams(-1, Ui.dp(host, 210)));
        } catch (Exception ignored) {
        }
        Ui.add(
            card,
            "새 일회용 페어링 코드 발급",
            false,
            R.drawable.ic_refresh,
            () -> {
              srv.rotatePairingCode();
              rebuild();
              Ui.toast(host, "새 페어링 코드를 발급했습니다.");
            });
      }

      // Admin authentication & Port card
      LinearLayout authCard = Ui.card(pane);
      Ui.section(authCard, R.drawable.ic_tune, "관리자 인증 및 보안 설정");
      boolean hasPin = !a.prefs.adminPinHash().isEmpty();
      authCard.addView(
          Ui.text(
              host,
              "관리자 비밀번호(PIN): " + (hasPin ? "설정됨 (암호화 해시 저장)" : "미설정 (일회용 페어링 코드만 사용)"),
              14,
              Ui.INK));
      Ui.add(
          authCard,
          hasPin ? "관리자 비밀번호(PIN) 변경 / 해제" : "관리자 비밀번호(PIN) 설정",
          false,
          R.drawable.ic_tune,
          () -> {
            EditText pinIn = Ui.input(host, "새 관리자 비밀번호 (비워두면 해제)", "");
            pinIn.setSingleLine();
            LinearLayout box = Ui.column(host);
            box.setPadding(Ui.dp(host, 22), Ui.dp(host, 12), Ui.dp(host, 22), 0);
            box.addView(pinIn);
            new AlertDialog.Builder(host)
                .setTitle("관리자 인증 비밀번호 설정")
                .setMessage("평문으로 저장되지 않고 안전한 해시로만 저장됩니다.")
                .setView(box)
                .setPositiveButton(
                    "저장",
                    (d, w) -> {
                      a.prefs.setAdminPin(pinIn.getText().toString());
                      Ui.toast(host, "관리자 인증 설정을 저장했습니다.");
                      rebuild();
                    })
                .setNegativeButton("취소", null)
                .show();
          });
      number(authCard, "웹서버 접속 포트", "remotePort", a.prefs.remotePort(), 1024, 65535, "번");

      // Active sessions card
      LinearLayout sessCard = Ui.card(pane);
      Ui.section(sessCard, R.drawable.ic_check, "연결된 관리자 세션");
      LinearLayout sessList = Ui.column(host);
      sessCard.addView(sessList);
      a.io.execute(
          () -> {
            java.util.List<AdminSession> sessions =
                a.db.studio().activeSessions(System.currentTimeMillis());
            a.main.post(
                () -> {
                  if (!Ui.alive(host)) return;
                  sessList.removeAllViews();
                  if (sessions.isEmpty()) {
                    sessList.addView(Ui.text(host, "현재 승인된 관리자 세션이 없습니다.", 14, Ui.MUTED));
                  } else {
                    java.text.SimpleDateFormat df =
                        new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.KOREA);
                    for (AdminSession as : sessions) {
                      String rowLabel =
                          as.label
                              + " ("
                              + as.clientIp
                              + ") · 최근 접근 "
                              + df.format(new java.util.Date(as.lastAccessAt));
                      Ui.add(
                          sessList,
                          rowLabel + " — 연결 해제",
                          false,
                          R.drawable.ic_exit,
                          () ->
                              a.io.execute(
                                  () -> {
                                    a.db.studio().revokeSession(as.sessionId);
                                    a.main.post(
                                        () -> {
                                          Ui.toast(host, "해당 세션 연결을 해제했습니다.");
                                          rebuild();
                                        });
                                  }));
                    }
                    Ui.add(
                        sessList,
                        "모든 관리자 세션 일괄 해제",
                        false,
                        R.drawable.ic_trash,
                        () ->
                            a.io.execute(
                                () -> {
                                  a.db.studio().revokeAllSessions();
                                  a.main.post(
                                      () -> {
                                        Ui.toast(host, "모든 세션을 해제했습니다.");
                                        rebuild();
                                      });
                                }));
                  }
                });
          });
    }

    // Section 7.7: 절전 설정 UI (순서: 화면 쉬는 시간 → 주변 움직임에 반응 → 움직임이 없으면 → 움직임을 감지하면 → 동작 확인 → 자세히)
    private void buildPowerSection(LinearLayout pane) {
      PowerSaving.DeviceCapabilities caps = PowerSaving.DeviceCapabilities.inspect(host);

      LinearLayout card1 = Ui.card(pane);
      Ui.section(card1, R.drawable.ic_moon, "1. 화면 쉬는 시간 (야간 감광)");
      toggle(card1, "야간 시간표에 맞춰 화면 어둡게 하기", "night", true);
      time(card1, "어두워지는 시간", "nightStart", a.prefs.start());
      time(card1, "다시 밝아지는 시간", "nightEnd", a.prefs.end());
      number(card1, "주간 기본 밝기(%)", "brightness", a.prefs.brightness(), 10, 100, "%");
      card1.addView(
          Ui.text(
              host,
              (a.prefs.night() ? "현재 적용 상태: 잠시 쉬는 중 · 화면을 터치하면 밝아집니다.\n" : "현재 적용 상태: 주간 밝기 표시 중\n")
                  + "시작과 종료 시각이 같으면 야간 감광을 사용하지 않습니다.",
              13,
              Ui.MUTED));

      LinearLayout card2 = Ui.card(pane);
      Ui.section(card2, R.drawable.ic_focus, "2. 주변 움직임에 반응");
      String statusNotice =
          !caps.hasAnyCamera
              ? "이 기기에는 사용할 수 있는 카메라가 없어 시간표·터치 절전으로 동작합니다. 사진 액자는 계속 사용할 수 있어요."
              : !caps.cameraPermissionGranted
                  ? "움직임을 확인하려면 카메라 권한이 필요해요. 권한을 사용하지 않아도 사진 액자는 계속 사용할 수 있어요."
                  : "이 기기에서는 움직임에 따라 화면 밝기를 조절합니다. 영상은 기기 메모리에서만 밝기 변화량을 계산한 뒤 즉시 닫히며 저장·전송되지 않습니다.";
      card2.addView(Ui.text(host, statusNotice, 14, Ui.INK));
      Ui.gap(card2, 8);
      Switch motionSw = new Switch(host);
      motionSw.setText("주변 움직임에 반응 사용");
      motionSw.setTextColor(Ui.INK);
      motionSw.setEnabled(caps.hasAnyCamera);
      motionSw.setChecked(a.prefs.motionEnabled() && caps.cameraPermissionGranted);
      motionSw.setOnCheckedChangeListener(
          (b, on) -> {
            if (!on) {
              a.prefs.flag("motionEnabled", false);
              return;
            }
            if (!caps.cameraPermissionGranted) {
              motionSw.setChecked(false);
              host.requestPermissions(new String[] {Manifest.permission.CAMERA}, 40);
              Ui.toast(host, "카메라 권한을 허용한 뒤 ‘동작 확인’으로 시험해 보세요.");
              return;
            }
            a.prefs.flag("motionEnabled", true);
          });
      card2.addView(motionSw);
      if (caps.hasAnyCamera && !caps.cameraPermissionGranted) {
        Ui.add(
            card2,
            "카메라 권한 허용하고 사용해 보기",
            false,
            R.drawable.ic_focus,
            () -> host.requestPermissions(new String[] {Manifest.permission.CAMERA}, 40));
      }

      LinearLayout card3 = Ui.card(pane);
      Ui.section(card3, R.drawable.ic_clock, "3. 움직임이 없으면 (부재 대기 시간)");
      choiceInt(
          card3,
          "부재 대기 시간",
          new String[] {"1분", "3분", "5분 (권장)", "10분", "20분"},
          new int[] {1, 3, 5, 10, 20},
          "idleMinutes",
          a.prefs.idleMinutes());
      Ui.gap(card3, 8);
      card3.addView(Ui.text(host, "절전 방식", 14, Ui.MUTED));
      card3.addView(
          Ui.text(
              host,
              "• 화면 어둡게 (감광, 기본 지원): 밝기를 낮추고 확대 애니메이션을 멈춥니다.\n"
                  + "• 실제 화면 끄기: "
                  + caps.screenOffSupportLabel()
                  + "\n  (화면을 끈 뒤 자동으로 켜지는지는 아직 확인되지 않았어요.)",
              13,
              Ui.MUTED));

      LinearLayout card4 = Ui.card(pane);
      Ui.section(card4, R.drawable.ic_sun, "4. 움직임을 감지하면");
      toggle(card4, "야간 쉬는 시간에도 움직임에 반응해 잠시 밝히기", "nightReact", false);
      card4.addView(
          Ui.text(
              host,
              "기본적으로 야간 시간표가 우선하며, 위 옵션을 켜면 밤에도 움직임 감지 시 잠시 화면을 밝힙니다. 정지한 사람은 감지하지 못할 수 있으며 화면을 터치하면 언제든 즉시 밝아집니다.",
              13,
              Ui.MUTED));

      LinearLayout card5 = Ui.card(pane);
      Ui.section(card5, R.drawable.ic_play, "5. 동작 확인 (10초 시험)");
      card5.addView(
          Ui.text(
              host,
              "10초 동안 일시적으로 카메라 밝기 변화와 관심 영역을 확인하고 자동으로 종료합니다.",
              14,
              Ui.MUTED));
      Ui.add(
          card5,
          "10초 동작 확인 시작",
          true,
          R.drawable.ic_play,
          this::showMotionTestDialog);

      LinearLayout card6 = Ui.card(pane);
      Ui.section(card6, R.drawable.ic_tune, "6. 자세히 (민감도 · 관심 영역 · 진단)");
      choiceInt(
          card6,
          "움직임 민감도",
          new String[] {"낮음 (큰 움직임만)", "보통 (기본)", "민감 (작은 움직임도 반응)"},
          new int[] {1, 2, 3},
          "motionSensitivity",
          a.prefs.motionSensitivity());
      Ui.add(
          card6,
          String.format(
              Locale.KOREA,
              "관심 영역(ROI) 설정 · 현재 %.0f%%~%.0f%%",
              a.prefs.roiLeft() * 100,
              a.prefs.roiRight() * 100),
          false,
          R.drawable.ic_focus,
          this::showRoiDialog);
      Ui.gap(card6, 8);
      card6.addView(Ui.text(host, "기기 진단 및 누적 상태", 14, Ui.INK));
      card6.addView(
          Ui.text(
              host,
              "• 카메라 감지: "
                  + caps.motionSupportLabel()
                  + "\n• 조도 센서: "
                  + caps.lightSensorSummary
                  + "\n• 근접 센서: "
                  + caps.proximitySensorSummary
                  + "\n• "
                  + a.powerDiagnostics.summaryText(),
              13,
              Ui.MUTED));
    }

    private void showMotionTestDialog() {
      PowerSaving.DeviceCapabilities caps = PowerSaving.DeviceCapabilities.inspect(host);
      if (!caps.hasAnyCamera) {
        Ui.toast(host, "이 기기에는 카메라가 없어 움직임 감지를 시험할 수 없습니다.");
        return;
      }
      if (!caps.cameraPermissionGranted) {
        host.requestPermissions(new String[] {Manifest.permission.CAMERA}, 40);
        Ui.toast(host, "먼저 카메라 권한을 허용해 주세요.");
        return;
      }
      LinearLayout box = Ui.column(host);
      box.setPadding(Ui.dp(host, 20), Ui.dp(host, 14), Ui.dp(host, 20), Ui.dp(host, 14));
      RoiPreviewView previewView =
          new RoiPreviewView(
              host,
              a.prefs.roiLeft(),
              a.prefs.roiTop(),
              a.prefs.roiRight(),
              a.prefs.roiBottom(),
              false);
      box.addView(previewView, new LinearLayout.LayoutParams(-1, Ui.dp(host, 220)));
      Ui.gap(box, 10);
      TextView stateText = Ui.text(host, "카메라를 준비하고 있습니다… (10초 후 자동 종료)", 14, Ui.INK);
      box.addView(stateText);
      PowerSaving.CameraMotionSource src =
          new PowerSaving.CameraMotionSource(host, a.prefs, a.powerDiagnostics, true);
      Handler timer = new Handler(Looper.getMainLooper());
      AlertDialog d =
          new AlertDialog.Builder(host)
              .setTitle("주변 움직임 동작 확인 (10초)")
              .setView(box)
              .setPositiveButton("종료", null)
              .create();
      Runnable stopAll =
          () -> {
            timer.removeCallbacksAndMessages(null);
            src.stop();
          };
      d.setOnDismissListener(x -> stopAll.run());
      d.show();
      long started = SystemClock.elapsedRealtime();
      src.start(
          new PowerSaving.PresenceSource.Listener() {
            @Override
            public void onPresence(Core.PresenceEvent event) {
              if (event.health != Core.SourceHealth.OK) {
                stateText.setText("오류 또는 권한 필요: " + event.health.name());
              }
            }

            @Override
            public void onFramePreview(
                Bitmap preview,
                float score,
                float threshold,
                boolean detected,
                Core.SourceHealth health,
                String detail) {
              long leftSec =
                  Math.max(0L, 10L - (SystemClock.elapsedRealtime() - started) / 1000L);
              previewView.setFrame(preview, detected);
              stateText.setText(
                  String.format(
                      Locale.KOREA,
                      "%s · 변화율 %.1f%% (기준 %.1f%%) · %d초 남음",
                      detected ? "움직임 감지됨!" : "대기 중",
                      score * 100f,
                      threshold * 100f,
                      leftSec));
            }
          });
      timer.postDelayed(
          () -> {
            if (d.isShowing()) {
              d.dismiss();
              Ui.toast(host, "10초 동작 확인을 마쳤습니다.");
            }
          },
          10_000L);
    }

    private void showRoiDialog() {
      LinearLayout box = Ui.column(host);
      box.setPadding(Ui.dp(host, 20), Ui.dp(host, 12), Ui.dp(host, 20), Ui.dp(host, 12));
      box.addView(
          Ui.text(
              host,
              "TV, 창문, 커튼처럼 자주 움직이는 영역을 피하도록 드래그하여 감지 영역을 지정하세요.",
              14,
              Ui.MUTED));
      Ui.gap(box, 8);
      RoiPreviewView roiView =
          new RoiPreviewView(
              host,
              a.prefs.roiLeft(),
              a.prefs.roiTop(),
              a.prefs.roiRight(),
              a.prefs.roiBottom(),
              true);
      box.addView(roiView, new LinearLayout.LayoutParams(-1, Ui.dp(host, 220)));
      new AlertDialog.Builder(host)
          .setTitle("감지 관심 영역(ROI) 지정")
          .setView(box)
          .setPositiveButton(
              "적용",
              (d, w) -> {
                a.prefs.saveRoi(roiView.left, roiView.top, roiView.right, roiView.bottom);
                Ui.toast(host, "관심 영역을 저장했습니다.");
                rebuild();
              })
          .setNeutralButton(
              "기본 영역 복원",
              (d, w) -> {
                a.prefs.saveRoi(0.1f, 0.1f, 0.9f, 0.9f);
                rebuild();
              })
          .setNegativeButton("취소", null)
          .show();
    }

    private void buildStorageSection(LinearLayout pane) {
      LinearLayout storage = Ui.card(pane);
      Ui.section(storage, R.drawable.ic_folder, "사진 보관과 폴더 동기화");
      number(storage, "보관 용량 상한(MB)", "quota", a.prefs.quota(), 128, 16384, "MB");
      toggle(storage, "새 사진 가져올 때 기기 내 얼굴 위치 분석", "ai", true);
      Switch mirror = new Switch(host);
      mirror.setText("원본 폴더에서 사라진 사진을 휴지통으로 이동");
      mirror.setTextColor(Ui.INK);
      mirror.setChecked(a.prefs.raw.getBoolean("mirror", false));
      storage.addView(mirror);
      mirror.setOnCheckedChangeListener(
          (v, on) -> {
            if (!on) {
              a.prefs.flag("mirror", false);
              return;
            }
            new AlertDialog.Builder(host)
                .setTitle("원본 삭제를 연동할까요?")
                .setMessage(
                    "완전한 폴더 목록을 확인한 경우에만 적용합니다. 직접 가져온 사진·즐겨찾기는 보호하며 빈 폴더는 삭제 판정에 쓰지 않습니다.")
                .setPositiveButton("연동", (d, k) -> a.prefs.flag("mirror", true))
                .setNegativeButton("취소", (d, k) -> mirror.setChecked(false))
                .setOnCancelListener(d -> mirror.setChecked(false))
                .show();
          });
      Ui.add(
          storage,
          "폴더 지금 동기화",
          false,
          R.drawable.ic_refresh,
          () -> {
            if (a.prefs.folder().isEmpty()) Ui.toast(host, "사진 탭이나 첫 화면에서 폴더를 먼저 선택하세요.");
            else {
              FolderSync.now(host);
              Ui.toast(host, "동기화를 요청했습니다.");
            }
          });
      Ui.add(
          storage,
          "폴더 연결 해제 · 보관된 사진은 그대로 유지",
          false,
          R.drawable.ic_folder,
          () -> {
            String old = a.prefs.folder();
            a.prefs.text("folder", "");
            FolderSync.schedule(host);
            try {
              if (!old.isEmpty())
                host.getContentResolver()
                    .releasePersistableUriPermission(
                        android.net.Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            Ui.toast(host, "연결을 해제했습니다. 보관 사진은 그대로입니다.");
            rebuild();
          });
      String syncStatus = a.prefs.raw.getString("syncStatus", "폴더 동기화 이력 없음");
      storage.addView(Ui.text(host, "동기화 상태: " + syncStatus, 13, Ui.MUTED));
    }

    private void buildLaunchSection(LinearLayout pane) {
      LinearLayout system = Ui.card(pane);
      Ui.section(system, R.drawable.ic_home, "액자 실행과 시작");
      Ui.add(
          system,
          "시스템 화면보호기(DreamService) 설정 열기",
          false,
          R.drawable.ic_screen,
          () -> {
            try {
              host.startActivity(new Intent(Settings.ACTION_DREAM_SETTINGS));
            } catch (Exception e) {
              Ui.toast(host, "이 기기는 화면보호기 설정을 제공하지 않습니다.");
            }
          });
      Ui.add(
          system,
          "재부팅 후 시작 알림 권한 허용",
          false,
          R.drawable.ic_bell,
          () -> {
            if (Build.VERSION.SDK_INT >= 33)
              host.requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 30);
            else Ui.toast(host, "별도 알림 권한 요청이 필요 없는 Android 버전입니다.");
          });
      Ui.add(system, "전용 액자 홈 설정", false, R.drawable.ic_home, this::homeDialog);
      system.addView(
          Ui.text(
              host,
              "부팅 자동 실행은 기본 홈·화면 잠금·제조사 설정에 따라 다릅니다. 강제 종료 후 자동 재시작을 보장하지 않습니다.",
              13,
              Ui.MUTED));
    }

    private void buildBackupSection(LinearLayout pane) {
      LinearLayout pkgCard = Ui.card(pane);
      Ui.section(pkgCard, R.drawable.ic_upload, "누니담 패키지(.nunidam) 전체 백업 · 복원");
      pkgCard.addView(
          Ui.text(
              host,
              "인터넷 없이 USB나 파일 전송으로 사진, 앨범, 명언·시, 재생 장면, 요일별 시간표를 한 번에 백업하고 복원합니다 (SHA-256 체크섬 무결성 검증 포함).",
              13.5f,
              Ui.MUTED));
      Ui.add(
          pkgCard,
          "전체 백업 패키지(.nunidam) 내보내기",
          true,
          R.drawable.ic_upload,
          () ->
              host.startActivityForResult(
                  new Intent(Intent.ACTION_CREATE_DOCUMENT)
                      .setType("application/octet-stream")
                      .addCategory(Intent.CATEGORY_OPENABLE)
                      .putExtra(Intent.EXTRA_TITLE, "family.nunidam"),
                  12));
      Ui.add(
          pkgCard,
          "백업 패키지(.nunidam) 가져오기 · 복원",
          false,
          R.drawable.ic_download,
          () ->
              host.startActivityForResult(
                  new Intent(Intent.ACTION_OPEN_DOCUMENT)
                      .setType("*/*")
                      .addCategory(Intent.CATEGORY_OPENABLE),
                  13));

      LinearLayout backup = Ui.card(pane);
      Ui.section(backup, R.drawable.ic_upload, "기본 화면 설정만 옮기기 (JSON)");
      Ui.add(
          backup,
          "설정 JSON 내보내기",
          false,
          R.drawable.ic_upload,
          () ->
              host.startActivityForResult(
                  new Intent(Intent.ACTION_CREATE_DOCUMENT)
                      .setType("application/json")
                      .addCategory(Intent.CATEGORY_OPENABLE)
                      .putExtra(Intent.EXTRA_TITLE, "nunidam-settings.json"),
                  10));
      Ui.add(
          backup,
          "설정 JSON 가져오기",
          false,
          R.drawable.ic_download,
          () ->
              host.startActivityForResult(
                  new Intent(Intent.ACTION_OPEN_DOCUMENT)
                      .setType("*/*")
                      .addCategory(Intent.CATEGORY_OPENABLE),
                  11));
      backup.addView(
          Ui.text(
              host,
              "JSON 내보내기는 가벼운 기본 설정만 포함하며, 사진·앨범·편성표 전체 백업은 위 ‘.nunidam 패키지’를 사용하세요.",
              13,
              Ui.MUTED));
    }

    private void buildAdvancedSection(LinearLayout pane) {
      LinearLayout adv = Ui.card(pane);
      Ui.section(adv, R.drawable.ic_tune, "성능과 진단");
      toggle(adv, "저사양 모드 · 작은 사진(1280px), 확대 애니메이션 생략", "low", false);
      adv.addView(Ui.text(host, a.powerDiagnostics.summaryText(), 13, Ui.MUTED));

      LinearLayout plug = Ui.card(pane);
      Ui.section(plug, R.drawable.ic_bolt, "충전 관리 보조 · 실험 기능");
      plug.addView(
          Ui.text(
              host,
              "액자 앱 실행 중 배터리 30% 이하에서 켜기, 80% 이상에서 끄기 웹훅을 요청합니다. 배터리 보호를 보장하지 않으며 화면 절전과 스마트 플러그 전원 차단은 별개입니다.",
              13,
              Ui.MUTED));
      EditText on = Ui.input(host, "켜기 POST 웹훅 (https://...)", a.prefs.raw.getString("plugOn", "")),
          off = Ui.input(host, "끄기 POST 웹훅 (https://...)", a.prefs.raw.getString("plugOff", ""));
      on.setSingleLine();
      off.setSingleLine();
      plug.addView(on);
      Ui.gap(plug, 6);
      plug.addView(off);
      Ui.add(
          plug,
          "주소 적용 · 충전 보조 켜기",
          false,
          R.drawable.ic_bolt,
          () -> {
            try {
              ChargingController.validate(on.getText().toString().trim());
              ChargingController.validate(off.getText().toString().trim());
              new AlertDialog.Builder(host)
                  .setTitle("플러그의 자체 복구 일정을 설정했나요?")
                  .setMessage("앱·Wi-Fi가 멈춰도 플러그 자체에서 다시 충전을 켤 수 있어야 합니다. 화면보호기에서는 동작하지 않습니다.")
                  .setPositiveButton(
                      "설정했고 적용합니다",
                      (d, k) -> {
                        a.prefs.text("plugOn", on.getText().toString().trim());
                        a.prefs.text("plugOff", off.getText().toString().trim());
                        a.prefs.flag("plugEnabled", true);
                        Ui.toast(host, "액자 앱 실행 중 적용합니다.");
                      })
                  .setNegativeButton("취소", null)
                  .show();
            } catch (Exception e) {
              on.setError(e.getMessage());
            }
          });
      Ui.add(
          plug,
          "충전 보조 끄기",
          false,
          R.drawable.ic_exit,
          () -> {
            a.prefs.flag("plugEnabled", false);
            Ui.toast(host, "호출을 중지했습니다. 플러그 전원은 직접 확인해 주세요.");
          });
      plug.addView(
          Ui.text(host, a.prefs.raw.getString("plugStatus", "기본값: 사용 안 함"), 12, Ui.MUTED));
    }

    private void buildAboutSection(LinearLayout pane) {
      LinearLayout about = Ui.card(pane);
      Ui.section(about, R.drawable.ic_screen, "누니담 0.1.0");
      about.addView(
          Ui.text(
              host,
              "눈부신 순간을 담다 · nuni dam\n\n"
                  + "• 구현 완료: 로컬 사진 보관(SHA-256 중복 방지·EXIF 보정), 기기 내 ML Kit 얼굴 분석·수동 구도, 3가지 액자 구성과 3가지 색감, 명언·시 모음 및 사용자 문구 관리, QR 로컬 Wi-Fi 전송, 야간 감광 및 주변 움직임 반응 감광.\n"
                  + "• 기기 조건부: 시스템 화면보호기(DreamService), 전면 카메라 밝기 변화 감지, 전용 액자 홈 진입.\n"
                  + "• 후속 개발(미구현): 실제 화면 완전 종료 후 저전력 하드웨어 자동 복귀 검증, Google Photos/NAS 클라우드 직접 연동, Play 결제(Pro), 기기 캘린더 일정 연동, 사용자 폰트 업로드.\n\n"
                  + "오픈소스 고지: AndroidX Room, WorkManager, ExifInterface, Palette, Google ML Kit Face Detection, NanoHTTPD, ZXing Core (자세한 라이선스는 THIRD_PARTY_NOTICES.md 참조).\n"
                  + "앱 삭제 시 앱 내부에 보관된 사본과 기록도 함께 삭제됩니다. 원본 사진 백업은 별도로 보관해 주세요.",
              14,
              Ui.INK));
    }

    private void toggle(LinearLayout p, String label, String key, boolean fallback) {
      Switch v = new Switch(host);
      v.setText(label);
      v.setTextColor(Ui.INK);
      v.setTextSize(15);
      v.setPadding(Ui.dp(host, 4), Ui.dp(host, 12), 0, Ui.dp(host, 12));
      v.setChecked(a.prefs.raw.getBoolean(key, fallback));
      v.setOnCheckedChangeListener((b, on) -> a.prefs.flag(key, on));
      p.addView(v);
    }

    private void choiceInt(
        LinearLayout p, String label, String[] names, int[] vals, String key, int current) {
      Ui.gap(p, 8);
      p.addView(Ui.text(host, label, 14, Ui.MUTED));
      Spinner s = new Spinner(host);
      s.setAdapter(
          new ArrayAdapter<>(host, android.R.layout.simple_spinner_dropdown_item, names));
      int initial = 0;
      for (int i = 0; i < vals.length; i++) if (vals[i] == current) initial = i;
      s.setSelection(initial);
      s.setOnItemSelectedListener(
          new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) {}

            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
              a.prefs.number(key, vals[pos]);
            }
          });
      p.addView(s, new LinearLayout.LayoutParams(-1, Ui.dp(host, 48)));
    }

    private void number(
        LinearLayout p, String label, String key, int value, int min, int max, String unit) {
      Ui.add(
          p,
          label + " · " + value + " " + unit,
          false,
          R.drawable.ic_tune,
          () -> {
            LinearLayout box = Ui.column(host);
            box.setPadding(Ui.dp(host, 22), Ui.dp(host, 10), Ui.dp(host, 22), 0);
            EditText e = Ui.input(host, min + "~" + max + " " + unit, "" + value);
            e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            TextView errText = Ui.text(host, "", 13, Ui.DANGER);
            box.addView(e);
            box.addView(errText);
            AlertDialog d =
                new AlertDialog.Builder(host)
                    .setTitle(label)
                    .setView(box)
                    .setPositiveButton("적용", null)
                    .setNegativeButton("취소", null)
                    .create();
            d.setOnShowListener(
                x ->
                    d.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(
                            v -> {
                              try {
                                int n = Integer.parseInt(e.getText().toString().trim());
                                if (n < min || n > max) throw new IllegalArgumentException();
                                a.prefs.number(key, n);
                                d.dismiss();
                                rebuild();
                              } catch (Exception ex) {
                                String msg = min + "~" + max + " " + unit + " 범위의 정수를 입력하세요.";
                                e.setError(msg);
                                errText.setText(msg);
                              }
                            }));
            d.show();
          });
    }

    private void time(LinearLayout p, String label, String key, int value) {
      Ui.add(
          p,
          label + " · " + Core.timeText(value),
          false,
          R.drawable.ic_clock,
          () ->
              new TimePickerDialog(
                      host,
                      (v, h, m) -> {
                        a.prefs.number(key, h * 60 + m);
                        rebuild();
                      },
                      value / 60,
                      value % 60,
                      true)
                  .show());
    }

    private void homeDialog() {
      new AlertDialog.Builder(host)
          .setTitle("전용 액자 홈")
          .setItems(
              new String[] {"홈 후보에 추가하고 시스템 설정 열기", "홈 후보에서 누니담 제거"},
              (d, k) -> {
                host.getPackageManager()
                    .setComponentEnabledSetting(
                        new ComponentName(host, "co.kr.nuni.dam.HomeEntry"),
                        k == 0
                            ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
                if (k == 0)
                  try {
                    host.startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
                  } catch (Exception e) {
                    Ui.toast(host, "시스템 설정에서 기본 홈을 선택하세요.");
                  }
                else Ui.toast(host, "누니담 홈 후보를 제거했습니다.");
              })
          .show();
    }

    public void onActivityResult(int req, int result, Intent data) {
      if (result != RESULT_OK || data == null || data.getData() == null) return;
      a.io.execute(
          () -> {
            try {
              if (req == 10) {
                try (OutputStream out =
                    host.getContentResolver().openOutputStream(data.getData(), "wt")) {
                  if (out == null) throw new IOException("파일을 열 수 없습니다.");
                  out.write(a.prefs.exportJson().toString(2).getBytes(StandardCharsets.UTF_8));
                }
              } else if (req == 11) {
                try (InputStream in =
                    host.getContentResolver().openInputStream(data.getData())) {
                  if (in == null) throw new IOException("파일을 열 수 없습니다.");
                  a.prefs.restore(
                      new JSONObject(new String(Core.read(in, 32768), StandardCharsets.UTF_8)));
                }
              } else if (req == 12) {
                try (OutputStream out =
                    host.getContentResolver().openOutputStream(data.getData(), "wt")) {
                  if (out == null) throw new IOException("패키지 파일을 생성할 수 없습니다.");
                  a.studio.exportNunidamPackage(out, null);
                }
              } else if (req == 13) {
                try (InputStream in =
                    host.getContentResolver().openInputStream(data.getData())) {
                  if (in == null) throw new IOException("패키지 파일을 열 수 없습니다.");
                  a.studio.importNunidamPackage(in, true);
                }
              }
              a.main.post(
                  () -> {
                    if (Ui.alive(host)) {
                      rebuild();
                      Ui.toast(host, "설정을 반영했습니다.");
                    }
                  });
            } catch (Exception e) {
              a.main.post(
                  () -> {
                    if (Ui.alive(host)) Ui.error(host, e);
                  });
            }
          });
    }
  }

  /** Interactive ROI and camera motion test view (Section 7.5). */
  private static final class RoiPreviewView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private Bitmap frame;
    private boolean detected;
    private final boolean editable;
    float left, top, right, bottom;
    private float startX, startY;

    RoiPreviewView(
        Context c, float l, float t, float r, float b, boolean canEdit) {
      super(c);
      left = l;
      top = t;
      right = r;
      bottom = b;
      editable = canEdit;
      setContentDescription("카메라 감지 관심 영역 미리보기");
    }

    void setFrame(Bitmap b, boolean det) {
      frame = b;
      detected = det;
      invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
      float w = getWidth(), h = getHeight();
      c.drawColor(Ui.DARK_BG);
      if (frame != null && !frame.isRecycled()) {
        box.set(0, 0, w, h);
        c.drawBitmap(frame, null, box, paint);
      } else {
        paint.setColor(Ui.DARK_SECONDARY);
        paint.setTextSize(Ui.dp(getContext(), 14));
        c.drawText("카메라 밝기 채널 영역 (창문·TV를 피해 드래그하세요)", Ui.dp(getContext(), 16), h / 2f, paint);
      }
      box.set(left * w, top * h, right * w, bottom * h);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(Ui.dp(getContext(), 3));
      paint.setColor(detected ? 0xff62c370 : Color.WHITE);
      c.drawRect(box, paint);
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(detected ? 0x3362c370 : 0x22ffffff);
      c.drawRect(box, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
      if (!editable) return super.onTouchEvent(e);
      float nx = Math.max(0f, Math.min(1f, e.getX() / Math.max(1f, getWidth())));
      float ny = Math.max(0f, Math.min(1f, e.getY() / Math.max(1f, getHeight())));
      if (e.getAction() == MotionEvent.ACTION_DOWN) {
        startX = nx;
        startY = ny;
        return true;
      }
      if (e.getAction() == MotionEvent.ACTION_MOVE || e.getAction() == MotionEvent.ACTION_UP) {
        left = Math.min(startX, nx);
        top = Math.min(startY, ny);
        right = Math.max(left + 0.2f, Math.max(startX, nx));
        bottom = Math.max(top + 0.2f, Math.max(startY, ny));
        if (right > 1f) right = 1f;
        if (bottom > 1f) bottom = 1f;
        invalidate();
        if (e.getAction() == MotionEvent.ACTION_UP) performClick();
        return true;
      }
      return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() {
      super.performClick();
      return true;
    }
  }
}
