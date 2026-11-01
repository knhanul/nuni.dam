package co.kr.nuni.dam;

import android.content.Context;
import android.os.SystemClock;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

public final class TransferServer extends NanoHTTPD {
  public interface Approval {
    void request(String address);
  }

  private final NuniApp a;
  private final Core.Token token;
  private final String host, html;
  private final Approval approval;
  private final AtomicBoolean requested = new AtomicBoolean();
  private final Semaphore jobs = new Semaphore(4); // lower concurrency for stability on weak devices
  private volatile boolean approved, denied;

  public TransferServer(Context c, String ip, Approval callback) throws IOException {
    super("0.0.0.0", 0);
    a = NuniApp.of(c);
    host = ip;
    approval = callback;
    token = new Core.Token(SystemClock.elapsedRealtime(), 1800000); // 30 minutes
    try (InputStream in = c.getAssets().open("transfer.html")) {
      html = new String(Core.read(in, 256 * 1024), StandardCharsets.UTF_8);
    }
  }

  public String address() {
    return "http://" + host + ":" + getListeningPort();
  }

  public String qr() {
    return address() + "/#" + token.value;
  }

  public long remaining() {
    return Math.max(0, (token.expires - SystemClock.elapsedRealtime()) / 1000);
  }

  public void approve(boolean allow) {
    approved = allow;
    denied = !allow;
    if (!allow) requested.set(false); // allow re-request after reject/cancel
  }

  private static boolean lan(String ip) {
    if (ip == null) return false;
    // IPv4 private + loopback. IPv6 link-local / unique-local are less reliable here.
    return ip.startsWith("10.")
        || ip.startsWith("192.168.")
        || ip.equals("127.0.0.1")
        || ip.matches("172\\.(1[6-9]|2\\d|3[01])\\..*")
        || ip.startsWith("fe80:")
        || ip.startsWith("fc")
        || ip.startsWith("fd");
  }

  public Response serve(IHTTPSession s) {
    if (!lan(s.getRemoteIpAddress()))
      return error(Response.Status.FORBIDDEN, "같은 로컬 네트워크에서 연결해 주세요.");
    // Relaxed Origin check: only reject if present and clearly different host
    String origin = s.getHeaders().get("origin");
    if (origin != null && !origin.isEmpty() && !origin.contains(host))
      return error(Response.Status.FORBIDDEN, "허용되지 않은 요청 출처입니다.");
    String path = s.getUri();
    if (path.equals("/") && s.getMethod() == Method.GET)
      return secure(newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html));
    String auth = s.getHeaders().get("authorization");
    if (auth == null
        || !auth.startsWith("Bearer ")
        || !token.accepts(auth.substring(7), SystemClock.elapsedRealtime()))
      return error(Response.Status.UNAUTHORIZED, "연결이 만료되었습니다. QR 코드를 다시 스캔하세요.");
    if (denied) return error(Response.Status.FORBIDDEN, "태블릿에서 연결을 허용하지 않았습니다. 새 QR 코드를 만들어 주세요.");
    if (path.equals("/api/status") && s.getMethod() == Method.GET) {
      String remote = s.getRemoteIpAddress();
      if (!approved && requested.compareAndSet(false, true))
        a.main.post(() -> approval.request(remote));
      try {
        return json(new JSONObject().put("approved", approved).put("remaining", remaining()));
      } catch (JSONException e) {
        return error(Response.Status.INTERNAL_ERROR, "연결 상태 오류");
      }
    }
    if (!approved) return error(Response.Status.FORBIDDEN, "태블릿에서 먼저 연결을 승인해 주세요.");
    if (!jobs.tryAcquire())
      return error(Response.Status.SERVICE_UNAVAILABLE, "다른 작업을 처리 중입니다. 잠시 후 다시 시도하세요.");
    try {
      if (path.equals("/api/photos") && s.getMethod() == Method.GET) {
        JSONArray list = new JSONArray();
        for (Photo p : a.db.photos().active())
          list.put(
              new JSONObject()
                  .put("id", p.id)
                  .put("name", p.name)
                  .put("note", p.note)
                  .put("favourite", p.favourite));
        return json(
            new JSONObject()
                .put("photos", list)
                .put("used", a.db.photos().used())
                .put("quota", a.prefs.quota() * 1024L * 1024));
      }
      if (path.equals("/api/image") && s.getMethod() == Method.GET) {
        Photo p = a.db.photos().get(s.getParms().get("id"));
        if (p == null || p.trashedAt != 0) return error(Response.Status.NOT_FOUND, "없는 사진입니다.");
        android.graphics.Bitmap b = a.store.load(p, 320);
        if (b == null) return error(Response.Status.NOT_FOUND, "사진을 읽지 못했습니다.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out);
        b.recycle();
        byte[] data = out.toByteArray();
        return secure(
            newFixedLengthResponse(
                Response.Status.OK, "image/jpeg", new ByteArrayInputStream(data), data.length));
      }
      if (path.equals("/api/upload") && s.getMethod() == Method.POST) {
        long len = length(s, Core.MAX_INPUT);
        String type = s.getHeaders().get("content-type");
        // More lenient: accept image/*, octet-stream, or missing/empty
        if (type != null
            && !type.isEmpty()
            && !type.startsWith("image/")
            && !type.equals("application/octet-stream")
            && !type.equals("application/x-www-form-urlencoded"))
          return error(Response.Status.UNSUPPORTED_MEDIA_TYPE, "이미지 파일만 전송할 수 있습니다.");
        Photo p =
            a.store.importStream(
                new Core.ExactStream(s.getInputStream(), len), s.getParms().get("name"), true);
        return json(new JSONObject().put("id", p.id));
      }
      if (path.equals("/api/photo") && s.getMethod() == Method.POST) {
        JSONObject o = body(s);
        String id = o.getString("id");
        Photo p = a.db.photos().get(id);
        if (p == null || p.trashedAt != 0) return error(Response.Status.NOT_FOUND, "없는 사진입니다.");
        String action = o.getString("action");
        if (action.equals("trash")) a.store.trash(id);
        else if (action.equals("note")) {
          String note = o.getString("note");
          if (note.length() > 400) throw new IllegalArgumentException("기록은 400자 이내입니다.");
          a.db.photos().note(id, note);
          a.store.changed();
        } else if (action.equals("favourite")) {
          a.db.photos().favourite(id, o.getBoolean("value"));
          a.store.changed();
        } else return error(Response.Status.BAD_REQUEST, "지원하지 않는 작업입니다.");
        return json(new JSONObject().put("ok", true));
      }
      if (path.equals("/api/settings") && s.getMethod() == Method.GET)
        return json(a.prefs.exportJson());
      if (path.equals("/api/settings") && s.getMethod() == Method.POST) {
        JSONObject o = body(s), current = a.prefs.exportJson();
        for (String key :
            new String[] {
              "mode", "theme", "seconds", "brightness", "phrase", "quoteKind", "quoteCycle", "quoteOnPhoto"
            })
          if (o.has(key)) current.put(key, o.get(key));
        a.prefs.restore(current);
        return json(new JSONObject().put("ok", true));
      }
      return error(Response.Status.NOT_FOUND, "없는 주소 또는 지원하지 않는 요청 방식입니다.");
    } catch (Exception e) {
      return error(Response.Status.BAD_REQUEST, e.getMessage() == null ? "작업 실패" : e.getMessage());
    } finally {
      jobs.release();
    }
  }

  private static long length(IHTTPSession s, long max) throws IOException {
    if (s.getHeaders().containsKey("transfer-encoding"))
      throw new IOException("파일 크기가 확인되는 전송만 지원합니다.");
    long n;
    try {
      n = Long.parseLong(s.getHeaders().get("content-length"));
    } catch (Exception e) {
      throw new IOException("파일 크기를 확인할 수 없습니다.");
    }
    if (n < 0 || n > max) throw new IOException("전송 크기 제한을 초과했습니다.");
    return n;
  }

  private static JSONObject body(IHTTPSession s) throws Exception {
    long n = length(s, 8192);
    return new JSONObject(
        new String(
            Core.read(new Core.ExactStream(s.getInputStream(), n), 8192), StandardCharsets.UTF_8));
  }

  private static Response error(Response.Status s, String message) {
    JSONObject o = new JSONObject();
    try {
      o.put("error", message);
    } catch (Exception ignored) {
    }
    return secure(newFixedLengthResponse(s, "application/json; charset=utf-8", o.toString()));
  }

  private static Response json(JSONObject o) {
    return secure(
        newFixedLengthResponse(
            Response.Status.OK, "application/json; charset=utf-8", o.toString()));
  }

  private static Response secure(Response r) {
    r.addHeader("Cache-Control", "no-store");
    r.addHeader("X-Content-Type-Options", "nosniff");
    r.addHeader("Referrer-Policy", "no-referrer");
    r.addHeader("X-Frame-Options", "DENY");
    r.addHeader("Connection", "close");
    r.addHeader(
        "Content-Security-Policy",
        "default-src 'self'; img-src 'self' blob:; style-src 'self' 'unsafe-inline'; script-src"
            + " 'self' 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'");
    return r;
  }
}
