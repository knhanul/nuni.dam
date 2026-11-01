package co.kr.nuni.dam;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.StatFs;
import androidx.exifinterface.media.ExifInterface;
import androidx.palette.graphics.Palette;
import java.io.*;
import java.security.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public final class PhotoStore {
  public interface Listener {
    void changed();
  }

  private final Context c;
  private final NuniDb db;
  private final Prefs prefs;
  private final File dir;
  private final Object lock = new Object();
  private final List<Listener> listeners = new CopyOnWriteArrayList<>();

  public PhotoStore(Context context, NuniDb database, Prefs p) {
    c = context;
    db = database;
    prefs = p;
    dir = new File(c.getFilesDir(), "photos");
    if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("사진 저장소 생성 실패");
  }

  public PhotoDao dao() {
    return db.photos();
  }

  public void listen(Listener l) {
    listeners.add(l);
  }

  public void unlisten(Listener l) {
    listeners.remove(l);
  }

  public void changed() {
    for (Listener l : listeners) l.changed();
  }

  public File file(String id) {
    if (id == null || !id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("잘못된 사진 식별자");
    return new File(dir, id + ".jpg");
  }

  public Photo fromUri(Uri uri, String name, boolean owned) throws Exception {
    try (InputStream in = c.getContentResolver().openInputStream(uri)) {
      if (in == null) throw new IOException("사진을 열 수 없습니다.");
      return importStream(in, name, owned);
    }
  }

  public Photo importStream(InputStream in, String name, boolean owned) throws Exception {
    synchronized (lock) {
      if (new StatFs(dir.getPath()).getAvailableBytes() < Core.RESERVE + Core.MAX_INPUT)
        throw new IOException("기기 저장공간이 부족합니다.");
      File incoming = File.createTempFile("incoming-", ".tmp", c.getCacheDir());
      try {
        try (FileOutputStream out = new FileOutputStream(incoming)) {
          Core.copy(in, out, Core.MAX_INPUT);
        }
        return process(incoming, name, owned);
      } finally {
        incoming.delete();
      }
    }
  }

  private Photo process(File input, String name, boolean owned) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(input)) {
      byte[] b = new byte[16384];
      int n;
      while ((n = in.read(b)) != -1) digest.update(b, 0, n);
    }
    StringBuilder hex = new StringBuilder();
    for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
    String id = hex.toString();
    Photo old = dao().get(id);
    if (old != null && file(id).isFile()) {
      if (owned) {
        old.owned = true;
        old.trashedAt = 0;
        dao().update(old);
        changed();
      }
      return old;
    }
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(input.getPath(), o);
    o.inSampleSize = Core.sample(o.outWidth, o.outHeight, prefs.low() ? 1280 : 2048);
    o.inJustDecodeBounds = false;
    o.inPreferredConfig = Bitmap.Config.RGB_565;
    Bitmap bitmap = null, rotated = null;
    File part = new File(dir, id + ".part");
    try {
      bitmap = BitmapFactory.decodeFile(input.getPath(), o);
      if (bitmap == null) throw new IOException("읽을 수 없는 이미지입니다. JPEG/PNG로 변환해 주세요.");
      long captured = 0;
      int orientation = 1;
      try {
        ExifInterface exif = new ExifInterface(input);
        orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
        String date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
        if (date != null) {
          SimpleDateFormat fmt = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT);
          fmt.setLenient(false);
          Date parsed = fmt.parse(date);
          if (parsed != null) captured = parsed.getTime();
        }
      } catch (Exception ignored) {
      }
      Matrix m = new Matrix();
      switch (orientation) {
        case 2:
          m.setScale(-1, 1);
          break;
        case 3:
          m.setRotate(180);
          break;
        case 4:
          m.setScale(1, -1);
          break;
        case 5:
          m.setRotate(90);
          m.postScale(-1, 1);
          break;
        case 6:
          m.setRotate(90);
          break;
        case 7:
          m.setRotate(-90);
          m.postScale(-1, 1);
          break;
        case 8:
          m.setRotate(-90);
          break;
        default:
          break;
      }
      rotated =
          m.isIdentity()
              ? bitmap
              : Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
      try (FileOutputStream out = new FileOutputStream(part)) {
        if (!rotated.compress(Bitmap.CompressFormat.JPEG, 90, out)) throw new IOException("저장 실패");
        out.getFD().sync();
      }
      if (!Core.fits(
          Math.max(0, dao().used() - (old == null ? 0 : old.bytes)),
          part.length(),
          prefs.quota() * 1024L * 1024,
          new StatFs(dir.getPath()).getAvailableBytes()))
        throw new IOException("보관 용량이 가득 찼습니다. 용량을 늘리거나 휴지통을 비워주세요. 기존 사진은 삭제하지 않았습니다.");
      Photo p = old == null ? new Photo() : old;
      p.id = id;
      p.name =
          name == null || name.isEmpty() ? "사진" : name.substring(0, Math.min(name.length(), 200));
      p.importedAt = old == null ? System.currentTimeMillis() : old.importedAt;
      p.capturedAt = captured;
      p.width = rotated.getWidth();
      p.height = rotated.getHeight();
      p.bytes = part.length();
      p.owned = (old != null && old.owned) || owned;
      p.accent =
          Palette.from(rotated)
              .resizeBitmapArea(6400)
              .maximumColorCount(8)
              .generate()
              .getMutedColor(0xff526951);
      p.faces = prefs.ai() ? FaceAnalyzer.analyze(rotated) : "[]";
      if (!part.renameTo(file(id))) throw new IOException("사진 저장을 확정하지 못했습니다.");
      if (old == null) dao().insert(p);
      else dao().update(p);
      changed();
      return p;
    } catch (OutOfMemoryError error) {
      throw new IOException("메모리가 부족합니다. 저사양 모드를 켜고 다시 가져오세요.", error);
    } finally {
      part.delete();
      if (rotated != null && rotated != bitmap) rotated.recycle();
      if (bitmap != null) bitmap.recycle();
    }
  }

  public Bitmap load(Photo p, int max) {
    try {
      BitmapFactory.Options o = new BitmapFactory.Options();
      o.inSampleSize = Core.sample(p.width, p.height, max);
      o.inPreferredConfig = Bitmap.Config.RGB_565;
      return BitmapFactory.decodeFile(file(p.id).getPath(), o);
    } catch (Exception | OutOfMemoryError e) {
      return null;
    }
  }

  public void trash(String id) {
    dao().trash(id, System.currentTimeMillis());
    changed();
  }

  public void restore(String id) {
    dao().restore(id);
    changed();
  }

  public void purge(String id) {
    synchronized (lock) {
      Photo p = dao().get(id);
      if (p != null && p.trashedAt > 0) {
        if (file(id).exists() && !file(id).delete())
          throw new IllegalStateException("파일을 지우지 못했습니다.");
        dao().purge(id);
        changed();
      }
    }
  }

  public void cleanup() {
    synchronized (lock) {
      long before = System.currentTimeMillis() - 86400000L;
      File[] files = dir.listFiles();
      if (files != null)
        for (File p : files) {
          if (p.lastModified() > before) continue;
          String n = p.getName();
          if (n.endsWith(".part")
              || (n.matches("[a-f0-9]{64}\\.jpg") && dao().get(n.substring(0, 64)) == null))
            p.delete();
        }
      File[] temp = c.getCacheDir().listFiles();
      if (temp != null)
        for (File p : temp)
          if (p.getName().startsWith("incoming-") && p.lastModified() < before) p.delete();
    }
  }
}
