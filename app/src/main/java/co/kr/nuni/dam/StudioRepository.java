package co.kr.nuni.dam;

import android.content.Context;
import android.os.StatFs;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/**
 * Repository for NuniDam Studio (Albums, TextItems, TextCollections, Scenes, ScheduleRules, Draft &
 * Published Configurations, and .nunidam Package Backup/Restore).
 */
public final class StudioRepository {
  private final Context c;
  private final NuniDb db;
  private final PhotoStore store;
  private final Prefs prefs;
  private final Object lock = new Object();

  public StudioRepository(Context context, NuniDb database, PhotoStore photoStore, Prefs p) {
    c = context;
    db = database;
    store = photoStore;
    prefs = p;
  }

  public StudioDao dao() {
    return db.studio();
  }

  /** Seeds default Albums, TextItems, TextCollections, Scenes, and ScheduleRules if empty. */
  public void ensureSeeded() {
    synchronized (lock) {
      long now = System.currentTimeMillis();
      StudioDao sdao = dao();

      // 1. Seed default albums if empty
      if (sdao.albums().isEmpty()) {
        Album def = new Album();
        def.id = Album.ALL_PHOTOS_ID;
        def.name = Album.ALL_PHOTOS_NAME;
        def.description = "누니담에 보관된 모든 사진을 자동으로 모아 보여줍니다.";
        def.sortOrder = "CUSTOM";
        def.seconds = 30;
        def.shuffle = true;
        def.isDefault = true;
        def.createdAt = now;
        def.updatedAt = now;
        sdao.saveAlbum(def);

        Album morning = new Album();
        morning.id = "album_morning";
        morning.name = "아침 풍경";
        morning.description = "상쾌한 하루를 여는 밝은 풍경 사진";
        morning.seconds = 20;
        morning.shuffle = true;
        morning.createdAt = now + 1;
        morning.updatedAt = now + 1;
        sdao.saveAlbum(morning);

        Album family = new Album();
        family.id = "album_family";
        family.name = "가족의 시간";
        family.description = "사랑하는 가족과 함께한 소중한 순간들";
        family.seconds = 25;
        family.shuffle = true;
        family.createdAt = now + 2;
        family.updatedAt = now + 2;
        sdao.saveAlbum(family);

        Album nature = new Album();
        nature.id = "album_nature";
        nature.name = "자연과 여행";
        nature.description = "마음이 편안해지는 계절과 여행의 기록";
        nature.seconds = 30;
        nature.shuffle = true;
        nature.createdAt = now + 3;
        nature.updatedAt = now + 3;
        sdao.saveAlbum(nature);

      }

      // Keep the virtual all-photos album present and immutable across upgrades.
      Album allPhotos = sdao.album(Album.ALL_PHOTOS_ID);
      if (allPhotos == null) {
        allPhotos = new Album();
        allPhotos.id = Album.ALL_PHOTOS_ID;
        allPhotos.createdAt = now;
      }
      if (!Album.ALL_PHOTOS_NAME.equals(allPhotos.name)
          || !allPhotos.isDefault
          || !"누니담에 보관된 모든 사진을 자동으로 모아 보여줍니다."
              .equals(allPhotos.description)) {
        allPhotos.name = Album.ALL_PHOTOS_NAME;
        allPhotos.description = "누니담에 보관된 모든 사진을 자동으로 모아 보여줍니다.";
        allPhotos.isDefault = true;
        allPhotos.updatedAt = now;
        sdao.saveAlbum(allPhotos);
      }

      // 2. Seed initial default texts from nunidam_sample.csv on first run
      if (!prefs.raw.getBoolean("seedSampleCsvV1", false)) {
        for (int i = 1; i <= 9; i++) {
          sdao.deleteTextItem("text_" + i);
        }
        for (int i = 1; i <= 5; i++) {
          sdao.deleteTextItem("text_s" + i);
        }
        prefs.setTextCategories(Arrays.asList(Prefs.DEFAULT_TEXT_CATEGORIES));
        seedSampleTextsFromCsv(false);
        prefs.raw.edit().putBoolean("seedSimpleV3", true).putBoolean("seedSampleCsvV1", true).apply();
      }

      // 3. Seed TextCollections if empty
      if (sdao.textCollections().isEmpty()) {
        TextCollection allCol = new TextCollection();
        allCol.id = "col_all";
        allCol.name = "전체 글귀 모음";
        allCol.description = "등록된 모든 글귀를 고르게 표시합니다.";
        allCol.categoryFilter = "";
        allCol.kindFilter = "ALL";
        allCol.shuffle = true;
        allCol.avoidRepeat = true;
        allCol.frequency = 1;
        allCol.createdAt = now;
        allCol.updatedAt = now;
        sdao.saveTextCollection(allCol);
      }

      // 4. Seed default Scene ("기본 장면") & sample scenes, and sparse sample weekly timetable
      boolean migrateDefaultSceneV2 = !prefs.raw.getBoolean("seedDefaultSceneV2", false);
      if (sdao.scenes().isEmpty() || sdao.scene("scene_default") == null || migrateDefaultSceneV2) {
        sdao.clearDefaultScenes();

        Scene sDefault = sdao.scene("scene_default");
        if (sDefault == null) {
          sDefault = new Scene();
          sDefault.id = "scene_default";
          sDefault.name = "기본 장면";
          sDefault.description = "시간표가 설정되지 않은 모든 시간대에 상시 재생되는 기본 장면입니다.";
          sDefault.layout = "MAGAZINE";
          sDefault.transitionEffect = "ZOOM";
          sDefault.seconds = 20;
          sDefault.textDisplayMode = "OVERLAY";
          sDefault.quoteCycle = "PHOTO";
          sDefault.textEveryN = 1;
          sDefault.showClock = true;
          sDefault.showWeather = true;
          sDefault.showCaptureDate = true;
          sDefault.theme = "MILK";
          sDefault.brightness = 75;
          sDefault.fontScale = 100;
          sDefault.isDefault = true;
          sDefault.createdAt = now - 10;
          sDefault.updatedAt = now;
          sdao.saveScene(sDefault);
          linkSceneAlbum(sDefault.id, "album_default");
          linkSceneText(sDefault.id, "인생문장");
          linkSceneText(sDefault.id, "시 한 구절");
        } else {
          sDefault.isDefault = true;
          sDefault.updatedAt = now;
          sdao.saveScene(sDefault);
        }

        if (sdao.scene("scene_morning") == null || migrateDefaultSceneV2) {
          Scene sMorning = sdao.scene("scene_morning");
          if (sMorning == null) sMorning = new Scene();
          sMorning.id = "scene_morning";
          sMorning.name = "좋은 아침";
          sMorning.description = "아침 출근·등교 시간대에 활기찬 사진과 명언으로 하루를 시작합니다.";
          sMorning.layout = "PHOTO";
          sMorning.transitionEffect = "ZOOM";
          sMorning.seconds = 15;
          sMorning.textDisplayMode = "OVERLAY";
          sMorning.quoteCycle = "PHOTO";
          sMorning.textEveryN = 1;
          sMorning.showClock = true;
          sMorning.showWeather = true;
          sMorning.showCaptureDate = true;
          sMorning.theme = "MILK";
          sMorning.brightness = 80;
          sMorning.fontScale = 100;
          sMorning.isDefault = false;
          sMorning.createdAt = now;
          sMorning.updatedAt = now;
          sdao.saveScene(sMorning);
          if (sdao.sceneAlbums(sMorning.id).isEmpty()) {
            linkSceneAlbum(sMorning.id, "album_default");
            linkSceneAlbum(sMorning.id, "album_morning");
          }
          if (sdao.sceneTextCollections(sMorning.id).isEmpty()) {
            linkSceneText(sMorning.id, "인생문장");
            linkSceneText(sMorning.id, "성경 말씀");
          }
        }

        if (sdao.scene("scene_evening") == null || migrateDefaultSceneV2) {
          Scene sEvening = sdao.scene("scene_evening");
          if (sEvening == null) sEvening = new Scene();
          sEvening.id = "scene_evening";
          sEvening.name = "저녁의 쉼";
          sEvening.description = "저녁 휴식 시간대에 자연 풍경과 시를 감성형 매거진 레이아웃으로 재생합니다.";
          sEvening.layout = "MAGAZINE";
          sEvening.transitionEffect = "FADE";
          sEvening.seconds = 30;
          sEvening.textDisplayMode = "SPLIT";
          sEvening.quoteCycle = "PHOTO";
          sEvening.showClock = true;
          sEvening.showCaptureDate = true;
          sEvening.theme = "SAGE";
          sEvening.brightness = 45;
          sEvening.isDefault = false;
          sEvening.createdAt = now + 2;
          sEvening.updatedAt = now + 2;
          sdao.saveScene(sEvening);
          if (sdao.sceneAlbums(sEvening.id).isEmpty()) {
            linkSceneAlbum(sEvening.id, "album_default");
            linkSceneAlbum(sEvening.id, "album_nature");
          }
          if (sdao.sceneTextCollections(sEvening.id).isEmpty()) {
            linkSceneText(sEvening.id, "시 한 구절");
          }
        }

        if (sdao.scene("scene_night") == null || migrateDefaultSceneV2) {
          Scene sNight = sdao.scene("scene_night");
          if (sNight == null) sNight = new Scene();
          sNight.id = "scene_night";
          sNight.name = "야간 절전";
          sNight.description = "심야 취침 시간 동안 화면을 어둡게 낮추어 편안한 수면을 돕습니다.";
          sNight.layout = "BOARD";
          sNight.transitionEffect = "NONE";
          sNight.seconds = 120;
          sNight.textDisplayMode = "DEDICATED";
          sNight.quoteCycle = "HOUR";
          sNight.showClock = true;
          sNight.showCaptureDate = false;
          sNight.theme = "INK";
          sNight.brightness = 10;
          sNight.nightDim = true;
          sNight.isDefault = false;
          sNight.createdAt = now + 3;
          sNight.updatedAt = now + 3;
          sdao.saveScene(sNight);
          if (sdao.sceneAlbums(sNight.id).isEmpty()) {
            linkSceneAlbum(sNight.id, "album_default");
          }
          if (sdao.sceneTextCollections(sNight.id).isEmpty()) {
            linkSceneText(sNight.id, "시 한 구절");
          }
        }
      }

      // 5. Seed sparse sample ScheduleRules ONCE on initial setup (if user deletes all rules later, keep empty so Default Scene plays 24/7)
      if (migrateDefaultSceneV2) {
        sdao.deleteScheduleRule("rule_weekday_day");
        sdao.deleteScheduleRule("rule_weekday_evening");
        sdao.deleteScheduleRule("rule_weekend_day");
        // 평일 아침 07:00~09:00 (2시간)만 '좋은 아침' 장면 재생
        saveRule("rule_weekday_morning", "평일 아침 (출근·등교)", "WEEKLY", "", 62, 420, 540, 3, "scene_morning", now);
        // 매일 저녁 19:00~21:00 (2시간)만 '저녁의 쉼' 장면 재생
        saveRule("rule_evening_rest", "저녁 휴식 시간", "WEEKLY", "", 127, 1140, 1260, 3, "scene_evening", now + 1);
        // 심야 23:00~06:00에는 '야간 절전' 장면 재생 (그 외 낮 09:00~19:00, 주말 오전 등 모든 미지정 시간대는 '기본 장면' 재생)
        saveRule("rule_night_sleep", "심야 취침 절전", "WEEKLY", "", 127, 1380, 360, 3, "scene_night", now + 2);
        prefs.raw.edit().putBoolean("seedDefaultSceneV2", true).apply();
      }

      // 6. Ensure ACTIVE & DRAFT PublishedConfiguration exist
      if (sdao.config("ACTIVE") == null) {
        try {
          JSONObject snap = exportFullStateJson();
          PublishedConfiguration activeCfg = new PublishedConfiguration();
          activeCfg.id = "ACTIVE";
          activeCfg.version = 1;
          activeCfg.payloadJson = snap.toString();
          activeCfg.note = "초기 기본 설정";
          activeCfg.publishedAt = now;
          activeCfg.updatedAt = now;
          sdao.saveConfig(activeCfg);

          PublishedConfiguration draftCfg = new PublishedConfiguration();
          draftCfg.id = "DRAFT";
          draftCfg.version = 1;
          draftCfg.payloadJson = snap.toString();
          draftCfg.note = "초안";
          draftCfg.publishedAt = now;
          draftCfg.updatedAt = now;
          sdao.saveConfig(draftCfg);
        } catch (Exception ignored) {
        }
      }
    }
  }

  /**
   * Loads and seeds default text items from assets/nunidam_sample.csv.
   *
   * @param replaceAll if true, deletes all existing text items before seeding; if false, upserts
   *     the default sample rows while keeping any custom user-added items.
   * @return number of seeded text items
   */
  public int seedSampleTextsFromCsv(boolean replaceAll) {
    synchronized (lock) {
      StudioDao sdao = dao();
      try (InputStream in = c.getAssets().open("nunidam_sample.csv")) {
        String csvText = new String(Core.read(in, 2 * 1024 * 1024), StandardCharsets.UTF_8);
        final List<String[]> rows = Core.parseCsvRecords(csvText);
        if (rows.isEmpty()) return 0;
        final long now = System.currentTimeMillis();
        final int[] count = new int[1];
        db.runInTransaction(
            () -> {
              if (replaceAll) {
                for (TextItem old : sdao.textItems()) {
                  sdao.deleteTextItem(old.id);
                }
              }
              int order = 0;
              for (int i = 0; i < rows.size(); i++) {
                String[] cols = rows.get(i);
                if (cols.length < 2) continue;
                String col0 = cols[0] == null ? "" : cols[0].trim();
                String col1 = cols[1] == null ? "" : cols[1].trim();
                // Skip header row
                if (i == 0
                    && ("카테고리".equalsIgnoreCase(col0)
                        || "category".equalsIgnoreCase(col0)
                        || "본문".equalsIgnoreCase(col1))) {
                  continue;
                }
                if (col1.isEmpty()) continue;
                order++;
                String category = col0.isEmpty() ? "기타" : col0;
                prefs.ensureTextCategory(category);
                String author = cols.length > 2 && cols[2] != null ? cols[2].trim() : "";
                String title = cols.length > 3 && cols[3] != null ? cols[3].trim() : "";
                String source = cols.length > 4 && cols[4] != null ? cols[4].trim() : "";
                int sec = 20;
                if (cols.length > 5 && cols[5] != null) {
                  try {
                    sec = Math.max(5, Math.min(600, Integer.parseInt(cols[5].trim())));
                  } catch (NumberFormatException ignored) {
                  }
                }
                String kind =
                    ("시 한 구절".equals(category)
                            || category.contains("시")
                            || col1.indexOf('\n') >= 0)
                        ? "POEM"
                        : "QUOTE";
                TextItem t = new TextItem();
                t.id = "text_sample_" + order;
                t.category = category;
                t.body = col1;
                t.author = author;
                t.title = title;
                t.source = source;
                t.kind = kind;
                t.tags = category;
                t.licenseNote = source;
                t.displaySeconds = sec;
                t.sortOrder = order;
                t.enabled = true;
                t.createdAt = now + order;
                t.updatedAt = now + order;
                sdao.saveTextItem(t);
                count[0]++;
              }
            });
        return count[0];
      } catch (Exception e) {
        return 0;
      }
    }
  }

  public void ensureTextCollectionExists(String colId) {
    if (colId == null || colId.trim().isEmpty()) return;
    StudioDao sdao = dao();
    if (sdao.textCollection(colId) == null) {
      long now = System.currentTimeMillis();
      TextCollection tc = new TextCollection();
      tc.id = colId;
      tc.name = colId;
      tc.description = colId + " 카테고리 모음";
      tc.categoryFilter = colId.startsWith("cat:") ? colId.substring(4).trim() : colId;
      tc.kindFilter = "ALL";
      tc.shuffle = true;
      tc.avoidRepeat = true;
      tc.frequency = 1;
      tc.createdAt = now;
      tc.updatedAt = now;
      sdao.saveTextCollection(tc);
    }
  }

  private void linkSceneAlbum(String sceneId, String albumId) {
    try {
      StudioDao sdao = dao();
      if (sdao.scene(sceneId) == null || sdao.album(albumId) == null) return;
      SceneAlbum sa = new SceneAlbum();
      sa.sceneId = sceneId;
      sa.albumId = albumId;
      sa.weight = 1;
      sdao.saveSceneAlbum(sa);
    } catch (Exception ignored) {
    }
  }

  private void linkSceneText(String sceneId, String colId) {
    try {
      ensureTextCollectionExists(colId);
      StudioDao sdao = dao();
      if (sdao.scene(sceneId) == null || sdao.textCollection(colId) == null) return;
      SceneTextCollection stc = new SceneTextCollection();
      stc.sceneId = sceneId;
      stc.collectionId = colId;
      sdao.saveSceneTextCollection(stc);
    } catch (Exception ignored) {
    }
  }

  private void saveRule(
      String id,
      String name,
      String type,
      String date,
      int daysMask,
      int startMin,
      int endMin,
      int priority,
      String sceneId,
      long now) {
    ScheduleRule r = new ScheduleRule();
    r.id = id;
    r.name = name;
    r.ruleType = type;
    r.specificDate = date;
    r.daysMask = daysMask;
    r.startMin = startMin;
    r.endMin = endMin;
    r.priority = priority;
    r.sceneId = sceneId;
    r.enabled = true;
    r.createdAt = now;
    r.updatedAt = now;
    dao().saveScheduleRule(r);
  }

  /** Evaluates the current schedule and returns the active Scene and next scheduled transition. */
  public Core.ScheduleEvaluation evaluateCurrentSchedule(long nowMs) {
    ensureSeeded();
    List<ScheduleRule> rules = dao().enabledScheduleRules();
    List<Core.ScheduleSlot> slots = new ArrayList<>();
    for (ScheduleRule r : rules) slots.add(Core.ScheduleSlot.from(r));

    Calendar cal = Calendar.getInstance();
    cal.setTimeInMillis(nowMs);
    SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
    String todayStr = fmt.format(cal.getTime());
    int dow0 = cal.get(Calendar.DAY_OF_WEEK) - 1; // 0=Sun..6=Sat
    int minOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);

    Calendar prevCal = (Calendar) cal.clone();
    prevCal.add(Calendar.DAY_OF_YEAR, -1);
    String prevStr = fmt.format(prevCal.getTime());

    Core.ScheduleSlot active =
        Core.resolveActiveSlot(slots, nowMs, todayStr, prevStr, dow0, minOfDay);
    Scene defScene = dao().scene("scene_default");
    if (defScene == null) defScene = dao().defaultScene();
    if (defScene == null) {
      List<Scene> allScenes = dao().scenes();
      if (!allScenes.isEmpty()) defScene = allScenes.get(0);
    }
    String defSceneId = defScene != null ? defScene.id : "scene_default";
    String activeSceneId = active != null ? active.sceneId : defSceneId;
    String activeRuleId = active != null ? active.id : "DEFAULT";
    String activeRuleName = active != null ? active.name : "기본 장면 (상시 재생)";
    String activeRuleType = active != null ? active.ruleType : "DEFAULT";

    // Look ahead up to 24 hours (in 15-min steps) to find the next scene transition
    String nextSceneId = activeSceneId;
    String nextRuleName = activeRuleName;
    int minutesUntilNext = -1;
    for (int step = 1; step <= 1440; step += 5) {
      long futureMs = nowMs + step * 60_000L;
      Calendar fCal = Calendar.getInstance();
      fCal.setTimeInMillis(futureMs);
      String fToday = fmt.format(fCal.getTime());
      int fDow = fCal.get(Calendar.DAY_OF_WEEK) - 1;
      int fMin = fCal.get(Calendar.HOUR_OF_DAY) * 60 + fCal.get(Calendar.MINUTE);
      Calendar fPrevCal = (Calendar) fCal.clone();
      fPrevCal.add(Calendar.DAY_OF_YEAR, -1);
      String fPrev = fmt.format(fPrevCal.getTime());
      Core.ScheduleSlot fSlot =
          Core.resolveActiveSlot(slots, futureMs, fToday, fPrev, fDow, fMin);
      String fSceneId = fSlot != null ? fSlot.sceneId : defSceneId;
      if (!fSceneId.equals(activeSceneId)) {
        nextSceneId = fSceneId;
        nextRuleName = fSlot != null ? fSlot.name : "기본 장면";
        minutesUntilNext = step;
        break;
      }
    }

    return new Core.ScheduleEvaluation(
        activeSceneId,
        activeRuleId,
        activeRuleName,
        activeRuleType,
        nextSceneId,
        nextRuleName,
        minutesUntilNext);
  }

  /** Resolves the list of Photos for a Scene (merging assigned albums, fallback to active photos). */
  public List<Photo> resolvePhotosForScene(String sceneId) {
    ensureSeeded();
    StudioDao sdao = dao();
    Scene sc = sdao.scene(sceneId);
    List<SceneAlbum> links = sdao.sceneAlbums(sceneId);
    LinkedHashMap<String, Photo> map = new LinkedHashMap<>();
    boolean allPhotosSelected = false;
    boolean namedAlbumSelected = false;
    for (SceneAlbum sa : links) {
      if (Album.isAllPhotos(sa.albumId)) {
        allPhotosSelected = true;
      } else {
        namedAlbumSelected = true;
      }
    }
    // For legacy scenes that stored both, retain named selections; the UI now makes the
    // all-photos choice exclusive with named albums.
    if (namedAlbumSelected) allPhotosSelected = false;
    if (allPhotosSelected || links.isEmpty()) {
      for (Photo p : db.photos().active()) map.put(p.id, p);
    } else {
      for (SceneAlbum sa : links) {
        if (Album.isAllPhotos(sa.albumId)) continue;
        Album alb = sdao.album(sa.albumId);
        List<Photo> photos = sdao.albumPhotos(sa.albumId);
        if (alb != null) {
          if ("CAPTURED_DESC".equals(alb.sortOrder)) {
            Collections.sort(photos, (x, y) -> Long.compare(y.capturedAt, x.capturedAt));
          } else if ("CAPTURED_ASC".equals(alb.sortOrder)) {
            Collections.sort(photos, (x, y) -> Long.compare(x.capturedAt, y.capturedAt));
          } else if ("NAME_ASC".equals(alb.sortOrder)) {
            Collections.sort(photos, (x, y) -> x.name.compareToIgnoreCase(y.name));
          }
        }
        for (Photo p : photos) {
          if (p.trashedAt == 0) map.put(p.id, p);
        }
      }
    }
    List<Photo> result = new ArrayList<>(map.values());
    if (sc == null || sc.shufflePhotos) {
      Collections.shuffle(result);
    }
    return result;
  }

  /** Resolves the list of TextItems for a Scene. */
  public List<TextItem> resolveTextsForScene(String sceneId) {
    ensureSeeded();
    StudioDao sdao = dao();
    Scene sc = sdao.scene(sceneId);
    if (sc != null && "NONE".equals(sc.textDisplayMode)) {
      return Collections.emptyList();
    }
    List<SceneTextCollection> links = sdao.sceneTextCollections(sceneId);
    List<TextItem> allEnabled = sdao.enabledTextItems();
    if (links.isEmpty()) {
      return allEnabled;
    }
    LinkedHashMap<String, TextItem> matched = new LinkedHashMap<>();
    for (SceneTextCollection stc : links) {
      if ("ALL".equals(stc.collectionId) || "col_all".equals(stc.collectionId)) {
        for (TextItem item : allEnabled) matched.put(item.id, item);
        continue;
      }
      TextCollection col = sdao.textCollection(stc.collectionId);
      if (col == null) {
        String catName =
            stc.collectionId.startsWith("cat:")
                ? stc.collectionId.substring(4).trim()
                : stc.collectionId.trim();
        for (TextItem item : allEnabled) {
          if (catName.equals(item.category)) {
            matched.put(item.id, item);
          }
        }
        continue;
      }
      Set<String> explicitIds = new HashSet<>();
      try {
        JSONArray arr = new JSONArray(col.itemIdsJson);
        for (int i = 0; i < arr.length(); i++) explicitIds.add(arr.getString(i));
      } catch (Exception ignored) {
      }
      Set<String> cats = new HashSet<>();
      if (!col.categoryFilter.trim().isEmpty()) {
        for (String c : col.categoryFilter.split(",")) {
          if (!c.trim().isEmpty()) cats.add(c.trim());
        }
      }
      for (TextItem item : allEnabled) {
        if (!explicitIds.isEmpty() && explicitIds.contains(item.id)) {
          matched.put(item.id, item);
          continue;
        }
        boolean kindOk =
            "ALL".equals(col.kindFilter)
                || col.kindFilter.isEmpty()
                || col.kindFilter.equals(item.kind);
        boolean catOk = cats.isEmpty() || cats.contains(item.category);
        if (explicitIds.isEmpty() && kindOk && catOk) {
          matched.put(item.id, item);
        }
      }
    }
    List<TextItem> out = new ArrayList<>(matched.values());
    if (out.isEmpty()) out = allEnabled;
    return out;
  }

  /** Exports the full Studio state as a JSONObject. */
  public JSONObject exportFullStateJson() throws JSONException {
    StudioDao sdao = dao();
    JSONObject root = new JSONObject();
    root.put("schemaVersion", 2);
    root.put("exportedAt", System.currentTimeMillis());

    // Albums
    JSONArray albumsArr = new JSONArray();
    for (Album a : sdao.albums()) {
      JSONObject o =
          new JSONObject()
              .put("id", a.id)
              .put("name", a.name)
              .put("description", a.description)
              .put("coverPhotoId", a.coverPhotoId)
              .put("sortOrder", a.sortOrder)
              .put("seconds", a.seconds)
              .put("shuffle", a.shuffle)
              .put("isDefault", a.isDefault)
              .put("createdAt", a.createdAt)
              .put("updatedAt", a.updatedAt);
      JSONArray itemsArr = new JSONArray();
      List<Photo> albumPhotos = sdao.albumPhotos(a.id);
      for (int i = 0; i < albumPhotos.size(); i++) {
        Photo photo = albumPhotos.get(i);
        itemsArr.put(
            new JSONObject()
                .put("photoId", photo.id)
                .put("position", i)
                .put("addedAt", photo.importedAt));
      }
      o.put("items", itemsArr);
      o.put("photoCount", sdao.albumPhotoCount(a.id));
      albumsArr.put(o);
    }
    root.put("albums", albumsArr);

    // Texts
    JSONArray textsArr = new JSONArray();
    for (TextItem t : sdao.textItems()) {
      textsArr.put(
          new JSONObject()
              .put("id", t.id)
              .put("title", t.title)
              .put("body", t.body)
              .put("author", t.author)
              .put("source", t.source)
              .put("kind", t.kind)
              .put("category", t.category)
              .put("tags", t.tags)
              .put("licenseNote", t.licenseNote)
              .put("displaySeconds", t.displaySeconds)
              .put("sortOrder", t.sortOrder)
              .put("enabled", t.enabled)
              .put("createdAt", t.createdAt)
              .put("updatedAt", t.updatedAt));
    }
    root.put("texts", textsArr);
    JSONArray catArr = new JSONArray();
    for (String cat : prefs.textCategories()) catArr.put(cat);
    root.put("textCategories", catArr);

    // TextCollections
    JSONArray colsArr = new JSONArray();
    for (TextCollection tc : sdao.textCollections()) {
      colsArr.put(
          new JSONObject()
              .put("id", tc.id)
              .put("name", tc.name)
              .put("description", tc.description)
              .put("categoryFilter", tc.categoryFilter)
              .put("kindFilter", tc.kindFilter)
              .put("itemIdsJson", tc.itemIdsJson)
              .put("shuffle", tc.shuffle)
              .put("avoidRepeat", tc.avoidRepeat)
              .put("frequency", tc.frequency)
              .put("createdAt", tc.createdAt)
              .put("updatedAt", tc.updatedAt));
    }
    root.put("textCollections", colsArr);

    // Scenes
    JSONArray scenesArr = new JSONArray();
    for (Scene sc : sdao.scenes()) {
      JSONObject o =
          new JSONObject()
              .put("id", sc.id)
              .put("name", sc.name)
              .put("description", sc.description)
              .put("layout", sc.layout)
              .put("transitionEffect", sc.transitionEffect)
              .put("textDisplayMode", sc.textDisplayMode)
              .put("quoteCycle", sc.quoteCycle)
              .put("theme", sc.theme)
              .put("textColor", sc.textColor)
              .put("textAlign", sc.textAlign)
              .put("seconds", sc.seconds)
              .put("textEveryN", sc.textEveryN)
              .put("brightness", sc.brightness)
              .put("fontScale", sc.fontScale)
              .put("textPadding", sc.textPadding)
              .put("scrimOpacity", sc.scrimOpacity)
              .put("showClock", sc.showClock)
              .put("showWeather", sc.showWeather)
              .put("showCaptureDate", sc.showCaptureDate)
              .put("shufflePhotos", sc.shufflePhotos)
              .put("isDefault", sc.isDefault)
              .put("nightDim", sc.nightDim)
              .put("createdAt", sc.createdAt)
              .put("updatedAt", sc.updatedAt);
      JSONArray albumIds = new JSONArray();
      for (SceneAlbum sa : sdao.sceneAlbums(sc.id)) albumIds.put(sa.albumId);
      o.put("albumIds", albumIds);
      JSONArray colIds = new JSONArray();
      for (SceneTextCollection stc : sdao.sceneTextCollections(sc.id))
        colIds.put(stc.collectionId);
      o.put("textCollectionIds", colIds);
      scenesArr.put(o);
    }
    root.put("scenes", scenesArr);

    // Schedules
    JSONArray rulesArr = new JSONArray();
    for (ScheduleRule r : sdao.scheduleRules()) {
      rulesArr.put(
          new JSONObject()
              .put("id", r.id)
              .put("name", r.name)
              .put("ruleType", r.ruleType)
              .put("specificDate", r.specificDate)
              .put("sceneId", r.sceneId)
              .put("daysMask", r.daysMask)
              .put("startMin", r.startMin)
              .put("endMin", r.endMin)
              .put("priority", r.priority)
              .put("enabled", r.enabled)
              .put("expiresAt", r.expiresAt)
              .put("createdAt", r.createdAt)
              .put("updatedAt", r.updatedAt));
    }
    root.put("schedules", rulesArr);
    return root;
  }

  /**
   * Publishes current Studio state to the tablet player (Section 8 & Section 9 transactional
   * apply). Validates schedule conflicts and syncs default Scene with Prefs for backward
   * compatibility.
   */
  public JSONObject publishToTablet(String note) throws Exception {
    synchronized (lock) {
      ensureSeeded();
      StudioDao sdao = dao();
      List<ScheduleRule> rules = sdao.enabledScheduleRules();
      List<Core.ScheduleSlot> slots = new ArrayList<>();
      for (ScheduleRule r : rules) slots.add(Core.ScheduleSlot.from(r));
      List<String> conflicts = Core.detectScheduleConflicts(slots);

      long now = System.currentTimeMillis();
      JSONObject snap = exportFullStateJson();
      PublishedConfiguration prev = sdao.config("ACTIVE");
      int nextVer = (prev == null ? 0 : prev.version) + 1;

      db.runInTransaction(
          () -> {
            PublishedConfiguration active = new PublishedConfiguration();
            active.id = "ACTIVE";
            active.version = nextVer;
            active.payloadJson = snap.toString();
            active.note = note == null || note.isEmpty() ? "스튜디오 게시 v" + nextVer : note;
            active.publishedAt = now;
            active.updatedAt = now;
            sdao.saveConfig(active);

            PublishedConfiguration draft = new PublishedConfiguration();
            draft.id = "DRAFT";
            draft.version = nextVer;
            draft.payloadJson = snap.toString();
            draft.note = active.note;
            draft.publishedAt = now;
            draft.updatedAt = now;
            sdao.saveConfig(draft);

            // Sync current scheduled or default Scene settings to Prefs so legacy UI stays in sync
            Core.ScheduleEvaluation eval = evaluateCurrentSchedule(now);
            Scene currentScene = sdao.scene(eval.activeSceneId);
            if (currentScene == null) currentScene = sdao.defaultScene();
            if (currentScene != null) {
              String legacyMode =
                  "PHOTO".equals(currentScene.layout)
                      ? "PHOTO"
                      : "BOARD".equals(currentScene.layout) ? "BOARD" : "MAGAZINE";
              prefs.raw
                  .edit()
                  .putString("mode", legacyMode)
                  .putString("theme", currentScene.theme)
                  .putInt("seconds", Math.max(10, Math.min(3600, currentScene.seconds)))
                  .putInt("brightness", Math.max(10, Math.min(100, currentScene.brightness)))
                  .putInt("fontScale", Math.max(85, Math.min(160, currentScene.fontScale)))
                  .putBoolean("showClock", currentScene.showClock)
                  .putBoolean("showCaptureDate", currentScene.showCaptureDate)
                  .putBoolean("quoteOnPhoto", !"NONE".equals(currentScene.textDisplayMode))
                  .putBoolean("animate", !"NONE".equals(currentScene.transitionEffect))
                  .apply();
            }
          });

      store.changed();
      JSONArray warnArr = new JSONArray();
      for (String c : conflicts) warnArr.put(c);
      return new JSONObject()
          .put("ok", true)
          .put("version", nextVer)
          .put("publishedAt", now)
          .put("warnings", warnArr);
    }
  }

  /**
   * Exports a `.nunidam` ZIP package (Section 10) containing manifest.json, albums.json,
   * texts.json, scenes.json, schedules.json, photos/, and checksums.json.
   */
  public void exportNunidamPackage(OutputStream out, String selectedAlbumId) throws Exception {
    ensureSeeded();
    StudioDao sdao = dao();
    JSONObject state = exportFullStateJson();

    List<Photo> photosToExport;
    if (selectedAlbumId != null && !selectedAlbumId.isEmpty()) {
      photosToExport = sdao.albumPhotos(selectedAlbumId);
    } else {
      photosToExport = db.photos().active();
    }

    JSONArray photosMeta = new JSONArray();
    for (Photo p : photosToExport) {
      photosMeta.put(
          new JSONObject()
              .put("id", p.id)
              .put("name", p.name)
              .put("note", p.note)
              .put("faces", p.faces)
              .put("importedAt", p.importedAt)
              .put("capturedAt", p.capturedAt)
              .put("bytes", p.bytes)
              .put("width", p.width)
              .put("height", p.height)
              .put("accent", p.accent)
              .put("favourite", p.favourite)
              .put("focusX", p.focusX)
              .put("focusY", p.focusY));
    }

    JSONObject manifest =
        new JSONObject()
            .put("packageFormat", "nunidam-package")
            .put("version", 1)
            .put("appVersion", "0.2.0")
            .put("exportedAt", System.currentTimeMillis())
            .put("scope", selectedAlbumId == null || selectedAlbumId.isEmpty() ? "FULL" : "ALBUM")
            .put("selectedAlbumId", selectedAlbumId == null ? "" : selectedAlbumId)
            .put("photoCount", photosToExport.size())
            .put("photos", photosMeta)
            .put("settings", prefs.exportJson());

    JSONObject checksums = new JSONObject();

    try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(out))) {
      writeZipJson(zos, checksums, "manifest.json", manifest);
      writeZipJson(zos, checksums, "albums.json", new JSONObject().put("albums", state.getJSONArray("albums")));
      writeZipJson(
          zos,
          checksums,
          "texts.json",
          new JSONObject()
              .put("texts", state.getJSONArray("texts"))
              .put("textCollections", state.getJSONArray("textCollections")));
      writeZipJson(zos, checksums, "scenes.json", new JSONObject().put("scenes", state.getJSONArray("scenes")));
      writeZipJson(
          zos,
          checksums,
          "schedules.json",
          new JSONObject().put("schedules", state.getJSONArray("schedules")));

      byte[] buf = new byte[16384];
      for (Photo p : photosToExport) {
        File pf = store.file(p.id);
        if (!pf.isFile()) continue;
        String entryName = "photos/" + p.id + ".jpg";
        zos.putNextEntry(new ZipEntry(entryName));
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream pin = new FileInputStream(pf)) {
          int n;
          while ((n = pin.read(buf)) != -1) {
            zos.write(buf, 0, n);
            md.update(buf, 0, n);
          }
        }
        zos.closeEntry();
        checksums.put(entryName, hex(md.digest()));
      }

      byte[] chkBytes = checksums.toString(2).getBytes(StandardCharsets.UTF_8);
      zos.putNextEntry(new ZipEntry("checksums.json"));
      zos.write(chkBytes);
      zos.closeEntry();
      zos.finish();
    }
  }

  private static void writeZipJson(
      ZipOutputStream zos, JSONObject checksums, String name, JSONObject obj) throws Exception {
    byte[] bytes = obj.toString(2).getBytes(StandardCharsets.UTF_8);
    zos.putNextEntry(new ZipEntry(name));
    zos.write(bytes);
    zos.closeEntry();
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    checksums.put(name, hex(md.digest(bytes)));
  }

  /**
   * Restores or merges a `.nunidam` ZIP package after full validation against path traversal, ZIP
   * bombs, checksum mismatches, and version incompatibility (Section 10).
   */
  public JSONObject importNunidamPackage(InputStream in, boolean keepExisting) throws Exception {
    synchronized (lock) {
      if (new StatFs(c.getFilesDir().getPath()).getAvailableBytes() < Core.RESERVE) {
        throw new IOException("기기 저장공간이 부족하여 패키지를 가져올 수 없습니다.");
      }
      File stagingDir = new File(c.getCacheDir(), "pkg-stage-" + System.currentTimeMillis());
      if (!stagingDir.mkdirs()) throw new IOException("임시 검증 폴더를 만들 수 없습니다.");
      try {
        Map<String, String> actualHashes = new HashMap<>();
        long totalExtracted = 0;
        long maxTotalBytes = 512L * 1024 * 1024; // 512 MB cap against zip bomb
        int entryCount = 0;
        String canonicalRoot = stagingDir.getCanonicalPath() + File.separator;

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(in))) {
          ZipEntry entry;
          byte[] buf = new byte[16384];
          while ((entry = zis.getNextEntry()) != null) {
            entryCount++;
            if (entryCount > 2000) throw new IOException("패키지 내부 파일 수가 허용 상한을 초과했습니다.");
            String name = entry.getName();
            if (name == null
                || name.contains("..")
                || name.startsWith("/")
                || name.startsWith("\\")) {
              throw new SecurityException("잘못된 패키지 파일 경로가 감지되었습니다: " + name);
            }
            if (entry.isDirectory()) continue;
            File target = new File(stagingDir, name);
            if (!target.getCanonicalPath().startsWith(canonicalRoot)) {
              throw new SecurityException("경로 조작 시도가 차단되었습니다: " + name);
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long fileBytes = 0;
            try (FileOutputStream fos = new FileOutputStream(target)) {
              int n;
              while ((n = zis.read(buf)) != -1) {
                fileBytes += n;
                totalExtracted += n;
                if (fileBytes > Core.MAX_INPUT || totalExtracted > maxTotalBytes) {
                  throw new IOException("패키지 압축 해제 크기가 안전 상한을 초과했습니다.");
                }
                fos.write(buf, 0, n);
                md.update(buf, 0, n);
              }
            }
            actualHashes.put(name, hex(md.digest()));
            zis.closeEntry();
          }
        }

        // Validate manifest.json and checksums.json before touching live data
        File manifestFile = new File(stagingDir, "manifest.json");
        File checksumsFile = new File(stagingDir, "checksums.json");
        if (!manifestFile.isFile() || !checksumsFile.isFile()) {
          throw new IllegalArgumentException("유효한 .nunidam 패키지 파일이 아닙니다 (매니페스트 누락).");
        }
        JSONObject manifest = readJsonFile(manifestFile);
        if (!"nunidam-package".equals(manifest.optString("packageFormat"))
            || manifest.optInt("version", 0) != 1) {
          throw new IllegalArgumentException("지원하지 않는 백업 패키지 버전입니다.");
        }
        JSONObject checksums = readJsonFile(checksumsFile);
        Iterator<String> keys = checksums.keys();
        while (keys.hasNext()) {
          String k = keys.next();
          String expected = checksums.getString(k);
          String actual = actualHashes.get(k);
          if (actual == null || !expected.equalsIgnoreCase(actual)) {
            throw new SecurityException("패키지 무결성 검증에 실패했습니다 (" + k + ").");
          }
        }

        JSONObject albumsJson =
            new File(stagingDir, "albums.json").isFile()
                ? readJsonFile(new File(stagingDir, "albums.json"))
                : new JSONObject();
        JSONObject textsJson =
            new File(stagingDir, "texts.json").isFile()
                ? readJsonFile(new File(stagingDir, "texts.json"))
                : new JSONObject();
        JSONObject scenesJson =
            new File(stagingDir, "scenes.json").isFile()
                ? readJsonFile(new File(stagingDir, "scenes.json"))
                : new JSONObject();
        JSONObject schedulesJson =
            new File(stagingDir, "schedules.json").isFile()
                ? readJsonFile(new File(stagingDir, "schedules.json"))
                : new JSONObject();

        // Import photos first (deduplicated by SHA-256 ID)
        int importedPhotos = 0;
        JSONArray photosMeta = manifest.optJSONArray("photos");
        if (photosMeta != null) {
          for (int i = 0; i < photosMeta.length(); i++) {
            JSONObject pm = photosMeta.getJSONObject(i);
            String pid = pm.getString("id");
            if (!pid.matches("[a-f0-9]{64}")) continue;
            File stagedPhoto = new File(stagingDir, "photos/" + pid + ".jpg");
            File destPhoto = store.file(pid);
            if (stagedPhoto.isFile() && !destPhoto.isFile()) {
              try (InputStream pin = new FileInputStream(stagedPhoto);
                  FileOutputStream pout = new FileOutputStream(destPhoto)) {
                Core.copy(pin, pout, Core.MAX_INPUT);
              }
            }
            if (destPhoto.isFile()) {
              Photo existing = db.photos().get(pid);
              Photo p = existing != null ? existing : new Photo();
              p.id = pid;
              p.name = pm.optString("name", "사진");
              p.note = pm.optString("note", "");
              p.faces = pm.optString("faces", "[]");
              p.importedAt = pm.optLong("importedAt", System.currentTimeMillis());
              p.capturedAt = pm.optLong("capturedAt", 0L);
              p.bytes = destPhoto.length();
              p.trashedAt = 0;
              p.width = pm.optInt("width", 1280);
              p.height = pm.optInt("height", 800);
              p.accent = pm.optInt("accent", 0xff526951);
              p.owned = true;
              p.favourite = pm.optBoolean("favourite", false);
              p.focusX = (float) pm.optDouble("focusX", -1);
              p.focusY = (float) pm.optDouble("focusY", -1);
              if (existing == null) db.photos().insert(p);
              else db.photos().update(p);
              importedPhotos++;
            }
          }
        }

        // Apply database tables inside a single Room transaction
        StudioDao sdao = dao();
        db.runInTransaction(
            () -> {
              try {
                if (!keepExisting) {
                  for (ScheduleRule r : sdao.scheduleRules()) sdao.deleteScheduleRule(r.id);
                  for (Scene sc : sdao.scenes()) sdao.deleteScene(sc.id);
                  for (TextCollection tc : sdao.textCollections()) sdao.deleteTextCollection(tc.id);
                  for (TextItem ti : sdao.textItems()) sdao.deleteTextItem(ti.id);
                  for (Album al : sdao.albums()) sdao.deleteAlbum(al.id);
                }

                JSONArray albumsArr = albumsJson.optJSONArray("albums");
                if (albumsArr != null) {
                  for (int i = 0; i < albumsArr.length(); i++) {
                    JSONObject o = albumsArr.getJSONObject(i);
                    Album al = new Album();
                    al.id = o.getString("id");
                    al.name = o.optString("name", "앨범");
                    al.description = o.optString("description", "");
                    al.coverPhotoId = o.optString("coverPhotoId", "");
                    al.sortOrder = o.optString("sortOrder", "CUSTOM");
                    al.seconds = o.optInt("seconds", 30);
                    al.shuffle = o.optBoolean("shuffle", true);
                    al.isDefault = o.optBoolean("isDefault", false);
                    al.createdAt = o.optLong("createdAt", System.currentTimeMillis());
                    al.updatedAt = o.optLong("updatedAt", System.currentTimeMillis());
                    sdao.saveAlbum(al);
                    JSONArray items = o.optJSONArray("items");
                    if (items != null) {
                      for (int j = 0; j < items.length(); j++) {
                        JSONObject it = items.getJSONObject(j);
                        String pid = it.getString("photoId");
                        if (db.photos().get(pid) != null) {
                          AlbumItem ai = new AlbumItem();
                          ai.albumId = al.id;
                          ai.photoId = pid;
                          ai.position = it.optInt("position", j);
                          ai.addedAt = it.optLong("addedAt", System.currentTimeMillis());
                          sdao.saveAlbumItem(ai);
                        }
                      }
                    }
                  }
                }

                JSONArray textsArr = textsJson.optJSONArray("texts");
                if (textsArr != null) {
                  for (int i = 0; i < textsArr.length(); i++) {
                    JSONObject o = textsArr.getJSONObject(i);
                    TextItem t = new TextItem();
                    t.id = o.getString("id");
                    t.title = o.optString("title", "");
                    t.body = o.optString("body", "");
                    t.author = o.optString("author", "");
                    t.source = o.optString("source", "");
                    t.kind = o.optString("kind", "QUOTE");
                    t.category = o.optString("category", "아침의 명언");
                    t.tags = o.optString("tags", "");
                    t.licenseNote = o.optString("licenseNote", "");
                    t.displaySeconds = o.optInt("displaySeconds", 15);
                    t.sortOrder = o.optInt("sortOrder", i);
                    t.enabled = o.optBoolean("enabled", true);
                    t.createdAt = o.optLong("createdAt", System.currentTimeMillis());
                    t.updatedAt = o.optLong("updatedAt", System.currentTimeMillis());
                    sdao.saveTextItem(t);
                  }
                }

                JSONArray colsArr = textsJson.optJSONArray("textCollections");
                if (colsArr != null) {
                  for (int i = 0; i < colsArr.length(); i++) {
                    JSONObject o = colsArr.getJSONObject(i);
                    TextCollection tc = new TextCollection();
                    tc.id = o.getString("id");
                    tc.name = o.optString("name", "글귀 모음");
                    tc.description = o.optString("description", "");
                    tc.categoryFilter = o.optString("categoryFilter", "");
                    tc.kindFilter = o.optString("kindFilter", "ALL");
                    tc.itemIdsJson = o.optString("itemIdsJson", "[]");
                    tc.shuffle = o.optBoolean("shuffle", true);
                    tc.avoidRepeat = o.optBoolean("avoidRepeat", true);
                    tc.frequency = o.optInt("frequency", 1);
                    tc.createdAt = o.optLong("createdAt", System.currentTimeMillis());
                    tc.updatedAt = o.optLong("updatedAt", System.currentTimeMillis());
                    sdao.saveTextCollection(tc);
                  }
                }

                JSONArray scenesArr = scenesJson.optJSONArray("scenes");
                if (scenesArr != null) {
                  for (int i = 0; i < scenesArr.length(); i++) {
                    JSONObject o = scenesArr.getJSONObject(i);
                    Scene sc = new Scene();
                    sc.id = o.getString("id");
                    sc.name = o.optString("name", "장면");
                    sc.description = o.optString("description", "");
                    sc.layout = o.optString("layout", "MAGAZINE");
                    sc.transitionEffect = o.optString("transitionEffect", "ZOOM");
                    sc.textDisplayMode = o.optString("textDisplayMode", "OVERLAY");
                    sc.quoteCycle = o.optString("quoteCycle", "PHOTO");
                    sc.theme = o.optString("theme", "MILK");
                    sc.textColor = o.optString("textColor", "");
                    sc.textAlign = o.optString("textAlign", "LEFT");
                    sc.seconds = o.optInt("seconds", 30);
                    sc.textEveryN = o.optInt("textEveryN", 1);
                    sc.brightness = o.optInt("brightness", 70);
                    sc.fontScale = o.optInt("fontScale", 100);
                    sc.textPadding = o.optInt("textPadding", 24);
                    sc.scrimOpacity = o.optInt("scrimOpacity", 70);
                    sc.showClock = o.optBoolean("showClock", true);
                    sc.showWeather = o.optBoolean("showWeather", false);
                    sc.showCaptureDate = o.optBoolean("showCaptureDate", true);
                    sc.shufflePhotos = o.optBoolean("shufflePhotos", true);
                    sc.isDefault = o.optBoolean("isDefault", false);
                    sc.nightDim = o.optBoolean("nightDim", false);
                    sc.createdAt = o.optLong("createdAt", System.currentTimeMillis());
                    sc.updatedAt = o.optLong("updatedAt", System.currentTimeMillis());
                    sdao.saveScene(sc);
                    sdao.clearSceneAlbums(sc.id);
                    JSONArray alIds = o.optJSONArray("albumIds");
                    if (alIds != null) {
                      for (int j = 0; j < alIds.length(); j++) {
                        String aid = alIds.getString(j);
                        if (sdao.album(aid) != null) linkSceneAlbum(sc.id, aid);
                      }
                    }
                    sdao.clearSceneTextCollections(sc.id);
                    JSONArray cIds = o.optJSONArray("textCollectionIds");
                    if (cIds != null) {
                      for (int j = 0; j < cIds.length(); j++) {
                        String cid = cIds.getString(j);
                        if (sdao.textCollection(cid) != null) linkSceneText(sc.id, cid);
                      }
                    }
                  }
                }

                JSONArray rulesArr = schedulesJson.optJSONArray("schedules");
                if (rulesArr != null) {
                  for (int i = 0; i < rulesArr.length(); i++) {
                    JSONObject o = rulesArr.getJSONObject(i);
                    ScheduleRule r = new ScheduleRule();
                    r.id = o.getString("id");
                    r.name = o.optString("name", "일정");
                    r.ruleType = o.optString("ruleType", "WEEKLY");
                    r.specificDate = o.optString("specificDate", "");
                    r.sceneId = o.optString("sceneId", "");
                    r.daysMask = o.optInt("daysMask", 127);
                    r.startMin = o.optInt("startMin", 360);
                    r.endMin = o.optInt("endMin", 540);
                    r.priority = o.optInt("priority", 3);
                    r.enabled = o.optBoolean("enabled", true);
                    r.expiresAt = o.optLong("expiresAt", 0L);
                    r.createdAt = o.optLong("createdAt", System.currentTimeMillis());
                    r.updatedAt = o.optLong("updatedAt", System.currentTimeMillis());
                    sdao.saveScheduleRule(r);
                  }
                }
              } catch (JSONException je) {
                throw new RuntimeException(je);
              }
            });

        if (manifest.has("settings") && !keepExisting) {
          try {
            prefs.restore(manifest.getJSONObject("settings"));
          } catch (Exception ignored) {
          }
        }
        ensureSeeded();
        publishToTablet("패키지 복원 적용");
        return new JSONObject()
            .put("ok", true)
            .put("importedPhotos", importedPhotos);
      } finally {
        deleteRecursive(stagingDir);
      }
    }
  }

  private static JSONObject readJsonFile(File f) throws Exception {
    try (InputStream in = new FileInputStream(f)) {
      return new JSONObject(new String(Core.read(in, 8 * 1024 * 1024), StandardCharsets.UTF_8));
    }
  }

  private static void deleteRecursive(File f) {
    if (f == null || !f.exists()) return;
    if (f.isDirectory()) {
      File[] children = f.listFiles();
      if (children != null) for (File c : children) deleteRecursive(c);
    }
    f.delete();
  }

  private static String hex(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b & 255));
    return sb.toString();
  }
}
