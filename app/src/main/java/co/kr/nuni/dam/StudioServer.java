package co.kr.nuni.dam;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.StatFs;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.json.*;

/**
 * Embedded HTTP Server for NuniDam Studio (Sections 2.3, 3, 4, 5, 6, 7, 10, 11). Runs only when
 * Remote Management is enabled by the user. Restricts access to local private networks, enforces
 * one-time pairing codes + PIN + session tokens + CSRF tokens + rate limiting, and streams photo
 * uploads and .nunidam package backups.
 */
public final class StudioServer extends NanoHTTPD {
  private final Context ctx;
  private final NuniApp a;
  private final int port;
  private final String studioHtml;
  private final Semaphore jobs = new Semaphore(6);
  private final Map<String, FailRecord> failures = new ConcurrentHashMap<>();
  private volatile String pairingCode;
  private volatile long pairingExpiresAt;

  private static final class FailRecord {
    int count;
    long lockedUntil;
  }

  public StudioServer(Context c, int listenPort) throws IOException {
    super("0.0.0.0", listenPort);
    ctx = c.getApplicationContext();
    a = NuniApp.of(ctx);
    port = listenPort;
    rotatePairingCode();
    try (InputStream in = ctx.getAssets().open("studio.html")) {
      studioHtml = new String(Core.read(in, 1024 * 1024), StandardCharsets.UTF_8);
    }
  }

  public synchronized String rotatePairingCode() {
    int code = 100000 + new SecureRandom().nextInt(900000);
    pairingCode = String.valueOf(code);
    pairingExpiresAt = System.currentTimeMillis() + 30L * 60_000L; // 30 minutes validity
    return pairingCode;
  }

  public String pairingCode() {
    if (System.currentTimeMillis() > pairingExpiresAt) {
      rotatePairingCode();
    }
    return pairingCode;
  }

  public int port() {
    return getListeningPort() > 0 ? getListeningPort() : port;
  }

  public String address() {
    String ip = resolveLocalIp(ctx);
    if (ip == null) return "Wi-Fi 연결 필요";
    return "http://" + ip + ":" + port();
  }

  public String qrUrl() {
    String ip = resolveLocalIp(ctx);
    if (ip == null) return "";
    return "http://" + ip + ":" + port() + "/#pair=" + pairingCode();
  }

  public static String resolveLocalIp(Context c) {
    try {
      ConnectivityManager m = c.getSystemService(ConnectivityManager.class);
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
          if (addr.isSiteLocalAddress()) return host;
          if (fallback == null) fallback = host;
        }
      }
      return fallback;
    } catch (Exception e) {
      return null;
    }
  }

  private static boolean isPrivateLan(String ip) {
    if (ip == null) return false;
    return ip.startsWith("10.")
        || ip.startsWith("192.168.")
        || ip.equals("127.0.0.1")
        || ip.equals("::1")
        || ip.matches("172\\.(1[6-9]|2\\d|3[01])\\..*")
        || ip.startsWith("fe80:")
        || ip.startsWith("fc")
        || ip.startsWith("fd");
  }

  @Override
  public Response serve(IHTTPSession s) {
    String remoteIp = s.getRemoteIpAddress();
    if (!isPrivateLan(remoteIp)) {
      return error(Response.Status.FORBIDDEN, "동일한 내부 Wi-Fi(로컬 네트워크)에서만 접근할 수 있습니다.");
    }
    String origin = s.getHeaders().get("origin");
    String localIp = resolveLocalIp(ctx);
    if (origin != null
        && !origin.isEmpty()
        && localIp != null
        && !origin.contains(localIp)
        && !origin.contains("127.0.0.1")
        && !origin.contains("localhost")) {
      return error(Response.Status.FORBIDDEN, "허용되지 않은 요청 출처(Origin)입니다.");
    }

    String uri = s.getUri();
    Method method = s.getMethod();

    // Serve Studio SPA
    if ((uri.equals("/") || uri.equals("/index.html") || uri.equals("/studio"))
        && method == Method.GET) {
      Response r =
          newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", studioHtml);
      return secureHeaders(r, false);
    }

    // Public auth handshake check
    if (uri.equals("/api/auth/info") && method == Method.GET) {
      try {
        return json(
            new JSONObject()
                .put("requiresPin", !a.prefs.adminPinHash().isEmpty())
                .put("appName", "누니담 스튜디오 (NuniDam Studio)")
                .put("version", "0.2.0"));
      } catch (Exception e) {
        return error(Response.Status.INTERNAL_ERROR, "인증 상태 확인 오류");
      }
    }

    if (uri.equals("/api/auth/pair") && method == Method.POST) {
      return handlePairRequest(s, remoteIp);
    }

    // All other /api/ endpoints require a valid authenticated AdminSession
    AdminSession session = authenticate(s);
    if (session == null) {
      return error(
          Response.Status.UNAUTHORIZED,
          "관리자 인증이 필요하거나 세션이 만료되었습니다. 페어링 코드로 다시 연결해 주세요.");
    }

    // CSRF defense on state-mutating requests
    if (method == Method.POST || method == Method.PUT || method == Method.DELETE) {
      String csrf = s.getHeaders().get("x-csrf-token");
      if (csrf == null || !csrf.equals(session.csrfToken)) {
        return error(Response.Status.FORBIDDEN, "CSRF 보안 토큰이 일치하지 않습니다. 페이지를 새로고침하세요.");
      }
    }

    long now = System.currentTimeMillis();
    a.db.studio().touchSession(session.sessionId, now);
    a.prefs.recordRemoteAccess(now);

    if (!jobs.tryAcquire()) {
      return error(Response.Status.SERVICE_UNAVAILABLE, "동시 처리 한도를 초과했습니다. 잠시 후 다시 시도하세요.");
    }
    try {
      return dispatchApi(s, uri, method, session);
    } catch (SecurityException se) {
      return error(Response.Status.FORBIDDEN, se.getMessage());
    } catch (Exception e) {
      return error(
          Response.Status.BAD_REQUEST, e.getMessage() == null ? "요청 처리 실패" : e.getMessage());
    } finally {
      jobs.release();
    }
  }

  private Response handlePairRequest(IHTTPSession s, String remoteIp) {
    long now = System.currentTimeMillis();
    String keyIp = remoteIp == null ? "unknown" : remoteIp;
    FailRecord rec = failures.get(keyIp);
    if (rec == null) {
      rec = new FailRecord();
      failures.put(keyIp, rec);
    }
    synchronized (rec) {
      if (rec.lockedUntil > now) {
        long waitSec = (rec.lockedUntil - now) / 1000L + 1;
        return error(
            Response.Status.FORBIDDEN,
            "인증 실패 횟수 초과로 잠시 잠겼습니다. " + waitSec + "초 후 다시 시도하세요.");
      }
    }
    try {
      JSONObject body = readJsonBody(s, 4096);
      String code = body.optString("pairingCode", "").trim();
      String pin = body.optString("pin", "").trim();
      String label = body.optString("deviceLabel", "웹 브라우저").trim();
      if (label.length() > 80) label = label.substring(0, 80);

      boolean codeOk = !code.isEmpty() && code.equals(pairingCode());
      boolean pinOk = a.prefs.verifyAdminPin(pin);
      if (!codeOk || !pinOk) {
        synchronized (rec) {
          rec.count++;
          if (rec.count >= 5) {
            rec.lockedUntil = now + 60_000L; // 60s lockout after 5 failures
            rec.count = 0;
          }
        }
        return error(
            Response.Status.UNAUTHORIZED,
            !codeOk ? "일회용 페어링 코드가 일치하지 않습니다. 태블릿의 원격 관리 화면을 확인하세요." : "관리자 비밀번호(PIN)가 올바르지 않습니다.");
      }
      synchronized (rec) {
        rec.count = 0;
        rec.lockedUntil = 0;
      }
      // Rotate one-time pairing code after successful pairing
      rotatePairingCode();

      String rawToken = new Core.Token(now, 86400_000L * 7).value;
      String csrf = new Core.Token(now, 86400_000L * 7).value.substring(0, 32);
      String sessionId = "sess_" + new Core.Token(now, 1000).value.substring(0, 16);
      AdminSession as = new AdminSession();
      as.sessionId = sessionId;
      as.tokenHash = Core.hashSecret(rawToken, "nunidam-session");
      as.csrfToken = csrf;
      as.clientIp = remoteIp;
      String ua = s.getHeaders().get("user-agent");
      as.userAgent = ua == null ? "" : ua.substring(0, Math.min(ua.length(), 160));
      as.label = label.isEmpty() ? ("관리자 (" + remoteIp + ")") : label;
      as.createdAt = now;
      as.lastAccessAt = now;
      as.expiresAt = now + 86400_000L * 7; // 7 days
      as.revoked = false;
      a.db.studio().saveSession(as);
      a.prefs.recordRemoteAccess(now);

      return json(
          new JSONObject()
              .put("ok", true)
              .put("sessionId", sessionId)
              .put("token", rawToken)
              .put("csrfToken", csrf)
              .put("expiresAt", as.expiresAt));
    } catch (Exception e) {
      return error(Response.Status.BAD_REQUEST, "인증 요청 형식이 잘못되었습니다.");
    }
  }

  private AdminSession authenticate(IHTTPSession s) {
    String auth = s.getHeaders().get("authorization");
    String rawToken = null;
    if (auth != null && auth.startsWith("Bearer ")) {
      rawToken = auth.substring(7).trim();
    } else if (s.getParms() != null && s.getParms().get("token") != null) {
      // Allow token param on GET image / backup download streams
      if (s.getMethod() == Method.GET) {
        rawToken = s.getParms().get("token").trim();
      }
    }
    if (rawToken == null || rawToken.isEmpty()) return null;
    String hash = Core.hashSecret(rawToken, "nunidam-session");
    return a.db.studio().sessionByTokenHash(hash, System.currentTimeMillis());
  }

  private Response dispatchApi(
      IHTTPSession s, String uri, Method method, AdminSession currentSession) throws Exception {
    StudioDao sdao = a.studio.dao();
    a.studio.ensureSeeded();

    // 1. Dashboard & Full State
    if (uri.equals("/api/dashboard") && method == Method.GET) {
      long now = System.currentTimeMillis();
      Core.ScheduleEvaluation eval = a.studio.evaluateCurrentSchedule(now);
      Scene curScene = sdao.scene(eval.activeSceneId);
      Scene nextScene = sdao.scene(eval.nextSceneId);
      StatFs stat = new StatFs(ctx.getFilesDir().getPath());
      long freeBytes = stat.getAvailableBytes();
      long usedBytes = a.db.photos().used();
      long quotaBytes = a.prefs.quota() * 1024L * 1024L;
      PublishedConfiguration pub = sdao.config("ACTIVE");

      JSONObject dash =
          new JSONObject()
              .put("activeSceneId", eval.activeSceneId)
              .put("activeSceneName", curScene != null ? curScene.name : "기본 장면")
              .put("activeRuleName", eval.activeRuleName)
              .put("activeRuleType", eval.activeRuleType)
              .put("nextSceneId", eval.nextSceneId)
              .put("nextSceneName", nextScene != null ? nextScene.name : "기본 장면")
              .put("nextRuleName", eval.nextRuleName)
              .put("minutesUntilNext", eval.minutesUntilNext)
              .put("albumCount", sdao.albums().size())
              .put("photoCount", a.db.photos().count())
              .put("trashCount", a.db.photos().trash().size())
              .put("textCount", sdao.textItems().size())
              .put("sceneCount", sdao.scenes().size())
              .put("scheduleCount", sdao.scheduleRules().size())
              .put("usedBytes", usedBytes)
              .put("quotaBytes", quotaBytes)
              .put("freeBytes", freeBytes)
              .put("publishedVersion", pub != null ? pub.version : 1)
              .put("publishedAt", pub != null ? pub.publishedAt : 0L)
              .put("publishedNote", pub != null ? pub.note : "")
              .put("lastPhotoId", a.prefs.raw.getString("lastPhoto", ""))
              .put("deviceTime", now)
              .put("serverAddress", address());
      return json(dash);
    }

    if (uri.equals("/api/state") && method == Method.GET) {
      JSONObject state = a.studio.exportFullStateJson();
      JSONArray photosArr = new JSONArray();
      for (Photo p : a.db.photos().active()) {
        photosArr.put(photoToJson(p));
      }
      state.put("photos", photosArr);
      JSONArray trashArr = new JSONArray();
      for (Photo p : a.db.photos().trash()) {
        trashArr.put(photoToJson(p));
      }
      state.put("trash", trashArr);
      state.put("settings", a.prefs.exportJson());
      state.put("keepOriginals", a.prefs.keepOriginals());
      state.put("useOptimizedImages", a.prefs.useOptimizedImages());
      return json(state);
    }

    // 2. Photos & Thumbnails & Streaming Uploads
    if (uri.equals("/api/image") && method == Method.GET) {
      String id = s.getParms().get("id");
      String size = s.getParms().get("size");
      Photo p = a.db.photos().get(id);
      if (p == null) return error(Response.Status.NOT_FOUND, "없는 사진입니다.");
      if ("full".equals(size)) {
        File f = a.store.file(p.id);
        if (!f.isFile()) return error(Response.Status.NOT_FOUND, "사진 파일이 없습니다.");
        FileInputStream fis = new FileInputStream(f);
        Response r = newFixedLengthResponse(Response.Status.OK, "image/jpeg", fis, f.length());
        return secureHeaders(r, true);
      }
      byte[] data = a.store.loadThumbBytes(p);
      if (data == null) return error(Response.Status.NOT_FOUND, "썸네일을 생성할 수 없습니다.");
      Response r =
          newFixedLengthResponse(
              Response.Status.OK, "image/jpeg", new ByteArrayInputStream(data), data.length);
      return secureHeaders(r, true);
    }

    if (uri.equals("/api/upload") && method == Method.POST) {
      long len = requireLength(s, Core.MAX_INPUT);
      String type = s.getHeaders().get("content-type");
      if (type != null
          && !type.isEmpty()
          && !type.startsWith("image/")
          && !type.equals("application/octet-stream")
          && !type.equals("application/x-www-form-urlencoded")) {
        return error(Response.Status.UNSUPPORTED_MEDIA_TYPE, "지원하는 이미지 형식(JPEG/PNG/WebP)만 업로드할 수 있습니다.");
      }
      String name = s.getParms().get("name");
      String albumId = s.getParms().get("albumId");
      boolean existedBefore = false;
      int countBefore = a.db.photos().count();
      Photo p =
          a.store.importStream(new Core.ExactStream(s.getInputStream(), len), name, true);
      int countAfter = a.db.photos().count();
      existedBefore = (countAfter == countBefore);

      // Always add to default album if not present, and also to target albumId if specified
      long now = System.currentTimeMillis();
      addPhotoToAlbumIfAbsent(sdao, "album_default", p.id, now);
      if (albumId != null && !albumId.isEmpty() && !albumId.equals("album_default")) {
        addPhotoToAlbumIfAbsent(sdao, albumId, p.id, now);
      }
      return json(
          new JSONObject()
              .put("ok", true)
              .put("id", p.id)
              .put("duplicate", existedBefore)
              .put("photo", photoToJson(p)));
    }

    if (uri.equals("/api/photo/action") && method == Method.POST) {
      JSONObject b = readJsonBody(s, 32768);
      String action = b.getString("action");
      JSONArray idsArr = b.optJSONArray("ids");
      List<String> ids = new ArrayList<>();
      if (idsArr != null) {
        for (int i = 0; i < idsArr.length(); i++) ids.add(idsArr.getString(i));
      } else if (b.has("id")) {
        ids.add(b.getString("id"));
      }
      for (String id : ids) {
        Photo p = a.db.photos().get(id);
        if (p == null) continue;
        switch (action) {
          case "trash":
            a.store.trash(id);
            break;
          case "restore":
            a.store.restore(id);
            break;
          case "purge":
            a.store.purge(id);
            break;
          case "favourite":
            a.db.photos().favourite(id, b.optBoolean("value", !p.favourite));
            break;
          case "note":
            String note = b.optString("note", "");
            if (note.length() > 400) note = note.substring(0, 400);
            a.db.photos().note(id, note);
            break;
          case "focus":
            a.db.photos()
                .focus(
                    id,
                    (float) b.optDouble("focusX", -1),
                    (float) b.optDouble("focusY", -1));
            break;
        }
      }
      a.store.changed();
      return json(new JSONObject().put("ok", true));
    }

    // 3. Albums CRUD & Photo Organization
    if (uri.equals("/api/albums/save") && method == Method.POST) {
      JSONObject b = readJsonBody(s, 65536);
      long now = System.currentTimeMillis();
      String id = b.optString("id", "").trim();
      Album existing = id.isEmpty() ? null : sdao.album(id);
      Album al = existing != null ? existing : new Album();
      if (al.id.isEmpty()) al.id = "album_" + now + "_" + new SecureRandom().nextInt(1000);
      al.name = b.optString("name", "새 앨범").trim();
      if (al.name.isEmpty()) al.name = "새 앨범";
      al.description = b.optString("description", "").trim();
      al.coverPhotoId = b.optString("coverPhotoId", al.coverPhotoId);
      al.sortOrder = b.optString("sortOrder", "CUSTOM");
      al.seconds = Math.max(5, Math.min(3600, b.optInt("seconds", 30)));
      al.shuffle = b.optBoolean("shuffle", true);
      if (existing == null) al.createdAt = now;
      al.updatedAt = now;
      sdao.saveAlbum(al);

      if (b.has("photoIds")) {
        JSONArray pids = b.getJSONArray("photoIds");
        sdao.clearAlbumItems(al.id);
        for (int i = 0; i < pids.length(); i++) {
          String pid = pids.getString(i);
          if (a.db.photos().get(pid) != null) {
            AlbumItem item = new AlbumItem();
            item.albumId = al.id;
            item.photoId = pid;
            item.position = i;
            item.addedAt = now;
            sdao.saveAlbumItem(item);
          }
        }
      }
      return json(new JSONObject().put("ok", true).put("id", al.id));
    }

    if (uri.equals("/api/albums/clone") && method == Method.POST) {
      JSONObject b = readJsonBody(s, 8192);
      String srcId = b.getString("id");
      Album src = sdao.album(srcId);
      if (src == null) return error(Response.Status.NOT_FOUND, "복제할 앨범을 찾을 수 없습니다.");
      long now = System.currentTimeMillis();
      Album copy = new Album();
      copy.id = "album_" + now + "_" + new SecureRandom().nextInt(1000);
      copy.name = b.optString("name", src.name + " (복사본)");
      copy.description = src.description;
      copy.coverPhotoId = src.coverPhotoId;
      copy.sortOrder = src.sortOrder;
      copy.seconds = src.seconds;
      copy.shuffle = src.shuffle;
      copy.isDefault = false;
      copy.createdAt = now;
      copy.updatedAt = now;
      sdao.saveAlbum(copy);
      for (AlbumItem it : sdao.albumItems(src.id)) {
        AlbumItem cit = new AlbumItem();
        cit.albumId = copy.id;
        cit.photoId = it.photoId;
        cit.position = it.position;
        cit.addedAt = now;
        sdao.saveAlbumItem(cit);
      }
      return json(new JSONObject().put("ok", true).put("id", copy.id));
    }

    if (uri.equals("/api/albums/delete") && method == Method.POST) {
      JSONObject b = readJsonBody(s, 8192);
      String id = b.getString("id");
      Album al = sdao.album(id);
      if (al == null) return error(Response.Status.NOT_FOUND, "없는 앨범입니다.");
      if (al.isDefault || "album_default".equals(al.id)) {
        return error(Response.Status.BAD_REQUEST, "기본 앨범은 삭제할 수 없습니다.");
      }
      sdao.deleteAlbum(id);
      return json(new JSONObject().put("ok", true));
    }

    if (uri.equals("/api/albums/items") && method == Method.POST) {
      JSONObject b = readJsonBody(s, 65536);
      String action = b.getString("action"); // add, remove, move, reorder
      String albumId = b.getString("albumId");
      String targetAlbumId = b.optString("targetAlbumId", "");
      JSONArray ids = b.optJSONArray("photoIds");
      long now = System.currentTimeMillis();
      if ("reorder".equals(action) && ids != null) {
        sdao.clearAlbumItems(albumId);
        for (int i = 0; i < ids.length(); i++) {
          AlbumItem item = new AlbumItem();
          item.albumId = albumId;
          item.photoId = ids.getString(i);
          item.position = i;
          item.addedAt = now;
          sdao.saveAlbumItem(item);
        }
      } else if (ids != null) {
        for (int i = 0; i < ids.length(); i++) {
          String pid = ids.getString(i);
          if ("add".equals(action) || "copy".equals(action)) {
            addPhotoToAlbumIfAbsent(sdao, albumId, pid, now);
          } else if ("remove".equals(action)) {
            sdao.removeAlbumItem(albumId, pid);
          } else if ("move".equals(action) && !targetAlbumId.isEmpty()) {
            addPhotoToAlbumIfAbsent(sdao, targetAlbumId, pid, now);
            sdao.removeAlbumItem(albumId, pid);
          }
        }
      }
      return json(new JSONObject().put("ok", true));
    }

    // 4. Texts & TextCollections CRUD
    if (uri.equals("/api/texts/save") && method == Method.POST) {
      JSONObject b = readJsonBody(s, 32768);
      long now = System.currentTimeMillis();
      String id = b.optString("id", "").trim();
      TextItem existing = id.isEmpty() ? null : sdao.textItem(id);
      TextItem t = existing != null ? existing : new TextItem();
      if (t.id.isEmpty()) t.id = "text_" + now + "_" + new SecureRandom().nextInt(1000);
      t.title = b.optString("title", "").trim();
      t.body = b.optString("body", "").trim();
      if (t.body.isEmpty()) throw new IllegalArgumentException("글귀 본문을 입력해 주세요.");
      t.author = b.optString("author", "").trim();
      t.source = b.optString("source", "").trim();
      t.kind = b.optString("kind", "QUOTE");
      t.category = b.optString("category", "아침의 명언").trim();
      t.tags = b.optString("tags", "").trim();
      t.licenseNote = b.optString("licenseNote", "").trim();
      t.displaySeconds = Math.max(5, Math.min(600, b.optInt("displaySeconds", 15)));
      t.sortOrder = b.optInt("sortOrder", 0);
      t.enabled = b.optBoolean("enabled", true);
      if (existing == null) t.createdAt = now;
      t.updatedAt = now;
      sdao.saveTextItem(t);
      return json(new JSONObject().put("ok", true).put("id", t.id));
    }

    if (uri.equals("/api/texts/delete") && method == Method.POST) {
      JSONObject b = readJsonBody(s, 8192);
      sdao.deleteTextItem(b.getString("id"));
      return json(new JSONObject().put("ok", true));
    }

    if (uri.equals("/api/text-collections/save") && method == Method.POST) {
      JSONObject b = readJsonBody(s, 32768);
      long now = System.currentTimeMillis();
      String id = b.optString("id", "").trim();
      TextCollection existing = id.isEmpty() ? null : sdao.textCollection(id);
      TextCollection tc = existing != null ? existing : new TextCollection();
      if (tc.id.isEmpty()) tc.id = "col_" + now + "_" + new SecureRandom().nextInt(1000);
      tc.name = b.optString("name", "새 글귀 모음").trim();
      tc.description = b.optString("description", "").trim();
      tc.categoryFilter = b.optString("categoryFilter", "").trim();
      tc.kindFilter = b.optString("kindFilter", "ALL");
      tc.itemIdsJson = b.optString("itemIdsJson", "[]");
      tc.shuffle = b.optBoolean("shuffle", true);
      tc.avoidRepeat = b.optBoolean("avoidRepeat", true);
      tc.frequency = Math.max(1, Math.min(50, b.optInt("frequency", 1)));
      if (existing == null) tc.createdAt = now;
      tc.updatedAt = now;
      sdao.saveTextCollection(tc);
      return json(new JSONObject().put("ok", true).put("id", tc.id));
    }

    if (uri.equals("/api/text-collections/delete") && method == Method.POST) {
      JSONObject b = readJsonBody(s, 8192);
      sdao.deleteTextCollection(b.getString("id"));
      return json(new JSONObject().put("ok", true));
    }

    // 5. Scenes CRUD
    if (uri.equals("/api/scenes/save") && method == Method.POST) {
      JSONObject b = readJsonBody(s, 32768);
      long now = System.currentTimeMillis();
      String id = b.optString("id", "").trim();
      Scene existing = id.isEmpty() ? null : sdao.scene(id);
      Scene sc = existing != null ? existing : new Scene();
      if (sc.id.isEmpty()) sc.id = "scene_" + now + "_" + new SecureRandom().nextInt(1000);
      sc.name = b.optString("name", "새 장면").trim();
      sc.description = b.optString("description", "").trim();
      sc.layout = b.optString("layout", "MAGAZINE");
      sc.transitionEffect = b.optString("transitionEffect", "ZOOM");
      sc.textDisplayMode = b.optString("textDisplayMode", "OVERLAY");
      sc.quoteCycle = b.optString("quoteCycle", "PHOTO");
      sc.theme = b.optString("theme", "MILK");
      sc.textColor = b.optString("textColor", "");
      sc.textAlign = b.optString("textAlign", "LEFT");
      sc.seconds = Math.max(5, Math.min(3600, b.optInt("seconds", 30)));
      sc.textEveryN = Math.max(1, Math.min(50, b.optInt("textEveryN", 1)));
      sc.brightness = Math.max(5, Math.min(100, b.optInt("brightness", 70)));
      sc.fontScale = Math.max(80, Math.min(160, b.optInt("fontScale", 100)));
      sc.textPadding = Math.max(8, Math.min(80, b.optInt("textPadding", 24)));
      sc.scrimOpacity = Math.max(0, Math.min(100, b.optInt("scrimOpacity", 70)));
      sc.showClock = b.optBoolean("showClock", true);
      sc.showWeather = b.optBoolean("showWeather", false);
      sc.showCaptureDate = b.optBoolean("showCaptureDate", true);
      sc.shufflePhotos = b.optBoolean("shufflePhotos", true);
      sc.nightDim = b.optBoolean("nightDim", false);
      boolean makeDefault = b.optBoolean("isDefault", sc.isDefault);
      if (makeDefault) {
        sdao.clearDefaultScenes();
        sc.isDefault = true;
      }
      if (existing == null) sc.createdAt = now;
      sc.updatedAt = now;
      sdao.saveScene(sc);

      if (b.has("albumIds")) {
        sdao.clearSceneAlbums(sc.id);
        JSONArray arr = b.getJSONArray("albumIds");
        for (int i = 0; i < arr.length(); i++) {
          SceneAlbum sa = new SceneAlbum();
          sa.sceneId = sc.id;
          sa.albumId = arr.getString(i);
          sa.weight = 1;
          if (sdao.album(sa.albumId) != null) sdao.saveSceneAlbum(sa);
        }
      }
      if (b.has("textCollectionIds")) {
        sdao.clearSceneTextCollections(sc.id);
        JSONArray arr = b.getJSONArray("textCollectionIds");
        for (int i = 0; i < arr.length(); i++) {
          SceneTextCollection stc = new SceneTextCollection();
          stc.sceneId = sc.id;
          stc.collectionId = arr.getString(i);
          if (sdao.textCollection(stc.collectionId) != null) sdao.saveSceneTextCollection(stc);
        }
      }
      return json(new JSONObject().put("ok", true).put("id", sc.id));
    }

    if (uri.equals("/api/scenes/delete") && method == Method.POST) {
      JSONObject b = readJsonBody(s, 8192);
      String id = b.getString("id");
      if (sdao.scenes().size() <= 1) {
        return error(Response.Status.BAD_REQUEST, "최소 1개의 장면은 유지되어야 합니다.");
      }
      sdao.deleteScene(id);
      return json(new JSONObject().put("ok", true));
    }

    // 6. Schedule Rules CRUD & Temporary Override
    if (uri.equals("/api/schedules/save") && method == Method.POST) {
      JSONObject b = readJsonBody(s, 32768);
      long now = System.currentTimeMillis();
      String id = b.optString("id", "").trim();
      ScheduleRule existing = id.isEmpty() ? null : sdao.scheduleRule(id);
      ScheduleRule r = existing != null ? existing : new ScheduleRule();
      if (r.id.isEmpty()) r.id = "rule_" + now + "_" + new SecureRandom().nextInt(1000);
      r.name = b.optString("name", "재생 일정").trim();
      r.ruleType = b.optString("ruleType", "WEEKLY");
      r.specificDate = b.optString("specificDate", "").trim();
      r.sceneId = b.optString("sceneId", "").trim();
      r.daysMask = b.optInt("daysMask", 127);
      r.startMin = Math.max(0, Math.min(1439, b.optInt("startMin", 360)));
      r.endMin = Math.max(0, Math.min(1439, b.optInt("endMin", 540)));
      r.priority =
          "TEMP".equals(r.ruleType)
              ? 1
              : ("DATE".equals(r.ruleType) || "ANNIVERSARY".equals(r.ruleType))
                  ? 2
                  : "WEEKLY".equals(r.ruleType) ? 3 : 4;
      r.enabled = b.optBoolean("enabled", true);
      r.expiresAt = b.optLong("expiresAt", 0L);
      if (existing == null) r.createdAt = now;
      r.updatedAt = now;
      sdao.saveScheduleRule(r);

      // Check conflicts to inform the user immediately
      List<ScheduleRule> rules = sdao.enabledScheduleRules();
      List<Core.ScheduleSlot> slots = new ArrayList<>();
      for (ScheduleRule sr : rules) slots.add(Core.ScheduleSlot.from(sr));
      List<String> conflicts = Core.detectScheduleConflicts(slots);
      JSONArray confArr = new JSONArray();
      for (String c : conflicts) confArr.put(c);

      return json(new JSONObject().put("ok", true).put("id", r.id).put("conflicts", confArr));
    }

    if (uri.equals("/api/schedules/delete") && method == Method.POST) {
      JSONObject b = readJsonBody(s, 8192);
      sdao.deleteScheduleRule(b.getString("id"));
      return json(new JSONObject().put("ok", true));
    }

    if (uri.equals("/api/schedules/temp") && method == Method.POST) {
      JSONObject b = readJsonBody(s, 8192);
      sdao.clearTempScheduleRules();
      String sceneId = b.optString("sceneId", "");
      int durationMinutes = b.optInt("durationMinutes", 60);
      if (!sceneId.isEmpty() && durationMinutes > 0) {
        long now = System.currentTimeMillis();
        ScheduleRule temp = new ScheduleRule();
        temp.id = "rule_temp_override";
        temp.name = "임시 지정 재생";
        temp.ruleType = "TEMP";
        temp.sceneId = sceneId;
        temp.priority = 1;
        temp.enabled = true;
        temp.expiresAt = now + durationMinutes * 60_000L;
        temp.createdAt = now;
        temp.updatedAt = now;
        sdao.saveScheduleRule(temp);
      }
      a.store.changed();
      return json(new JSONObject().put("ok", true));
    }

    // 7. Apply / Publish to Tablet (Section 8)
    if (uri.equals("/api/publish") && method == Method.POST) {
      JSONObject b = readJsonBody(s, 8192);
      String note = b.optString("note", "누니담 스튜디오에서 태블릿에 적용");
      return json(a.studio.publishToTablet(note));
    }

    // 8. Device Settings & Sessions Management
    if (uri.equals("/api/device/settings") && method == Method.POST) {
      JSONObject b = readJsonBody(s, 16384);
      if (b.has("quota")) {
        int q = Math.max(128, Math.min(16384, b.getInt("quota")));
        a.prefs.number("quota", q);
      }
      if (b.has("keepOriginals")) {
        a.prefs.flag("keepOriginals", b.getBoolean("keepOriginals"));
      }
      if (b.has("useOptimizedImages")) {
        a.prefs.flag("useOptimizedImages", b.getBoolean("useOptimizedImages"));
      }
      if (b.has("low")) {
        a.prefs.flag("low", b.getBoolean("low"));
      }
      if (b.has("newPin")) {
        a.prefs.setAdminPin(b.getString("newPin"));
      }
      return json(new JSONObject().put("ok", true));
    }

    if (uri.equals("/api/sessions") && method == Method.GET) {
      JSONArray arr = new JSONArray();
      for (AdminSession as : sdao.activeSessions(System.currentTimeMillis())) {
        arr.put(
            new JSONObject()
                .put("sessionId", as.sessionId)
                .put("clientIp", as.clientIp)
                .put("label", as.label)
                .put("createdAt", as.createdAt)
                .put("lastAccessAt", as.lastAccessAt)
                .put("isCurrent", as.sessionId.equals(currentSession.sessionId)));
      }
      return json(new JSONObject().put("sessions", arr));
    }

    if (uri.equals("/api/sessions/revoke") && method == Method.POST) {
      JSONObject b = readJsonBody(s, 4096);
      String sid = b.optString("sessionId", "");
      if ("ALL".equals(sid)) {
        sdao.revokeAllSessions();
      } else if (!sid.isEmpty()) {
        sdao.revokeSession(sid);
      }
      return json(new JSONObject().put("ok", true));
    }

    // 9. Backup (.nunidam) Export & Import
    if (uri.equals("/api/backup/export") && method == Method.GET) {
      String albumId = s.getParms().get("albumId");
      File tempPkg =
          File.createTempFile("nunidam-export-", ".nunidam", ctx.getCacheDir());
      try (FileOutputStream fos = new FileOutputStream(tempPkg)) {
        a.studio.exportNunidamPackage(fos, albumId);
      }
      FileInputStream fis =
          new FileInputStream(tempPkg) {
            @Override
            public void close() throws IOException {
              super.close();
              tempPkg.delete();
            }
          };
      Response r =
          newFixedLengthResponse(
              Response.Status.OK, "application/octet-stream", fis, tempPkg.length());
      r.addHeader(
          "Content-Disposition",
          "attachment; filename=\"family.nunidam\"");
      return secureHeaders(r, false);
    }

    if (uri.equals("/api/backup/import") && method == Method.POST) {
      long len = requireLength(s, 512L * 1024 * 1024);
      boolean keepExisting = !"false".equalsIgnoreCase(s.getParms().get("keepExisting"));
      JSONObject res =
          a.studio.importNunidamPackage(
              new Core.ExactStream(s.getInputStream(), len), keepExisting);
      return json(res);
    }

    return error(Response.Status.NOT_FOUND, "지원하지 않는 API 경로입니다: " + uri);
  }

  private static void addPhotoToAlbumIfAbsent(
      StudioDao sdao, String albumId, String photoId, long now) {
    List<AlbumItem> items = sdao.albumItems(albumId);
    for (AlbumItem it : items) {
      if (it.photoId.equals(photoId)) return;
    }
    AlbumItem item = new AlbumItem();
    item.albumId = albumId;
    item.photoId = photoId;
    item.position = items.size();
    item.addedAt = now;
    sdao.saveAlbumItem(item);
    Album al = sdao.album(albumId);
    if (al != null && al.coverPhotoId.isEmpty()) {
      al.coverPhotoId = photoId;
      sdao.saveAlbum(al);
    }
  }

  private static JSONObject photoToJson(Photo p) throws JSONException {
    return new JSONObject()
        .put("id", p.id)
        .put("name", p.name)
        .put("note", p.note)
        .put("importedAt", p.importedAt)
        .put("capturedAt", p.capturedAt)
        .put("bytes", p.bytes)
        .put("width", p.width)
        .put("height", p.height)
        .put("favourite", p.favourite)
        .put("focusX", p.focusX)
        .put("focusY", p.focusY);
  }

  private static long requireLength(IHTTPSession s, long max) throws IOException {
    if (s.getHeaders().containsKey("transfer-encoding")) {
      throw new IOException("Content-Length가 명시된 요청만 지원합니다.");
    }
    long n;
    try {
      n = Long.parseLong(s.getHeaders().get("content-length"));
    } catch (Exception e) {
      throw new IOException("전송 크기를 확인할 수 없습니다.");
    }
    if (n < 0 || n > max) {
      throw new IOException("허용된 최대 전송 크기를 초과했습니다.");
    }
    return n;
  }

  private static JSONObject readJsonBody(IHTTPSession s, long max) throws Exception {
    long n = requireLength(s, max);
    if (n == 0) return new JSONObject();
    return new JSONObject(
        new String(
            Core.read(new Core.ExactStream(s.getInputStream(), n), max),
            StandardCharsets.UTF_8));
  }

  private static Response error(Response.Status status, String message) {
    JSONObject o = new JSONObject();
    try {
      o.put("error", message);
    } catch (Exception ignored) {
    }
    return secureHeaders(
        newFixedLengthResponse(status, "application/json; charset=utf-8", o.toString()), false);
  }

  private static Response json(JSONObject o) {
    return secureHeaders(
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString()),
        false);
  }

  private static Response secureHeaders(Response r, boolean cacheableImage) {
    r.addHeader(
        "Cache-Control", cacheableImage ? "private, max-age=3600" : "no-store");
    r.addHeader("X-Content-Type-Options", "nosniff");
    r.addHeader("Referrer-Policy", "no-referrer");
    r.addHeader("X-Frame-Options", "DENY");
    r.addHeader("Connection", "close");
    r.addHeader(
        "Content-Security-Policy",
        "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline';"
            + " script-src 'self' 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'");
    return r;
  }
}
