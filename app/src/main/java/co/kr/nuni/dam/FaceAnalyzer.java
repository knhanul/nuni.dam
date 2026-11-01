package co.kr.nuni.dam;

import android.graphics.*;
import com.google.android.gms.tasks.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class FaceAnalyzer {
  private static final Semaphore pending = new Semaphore(1);

  public static String analyze(Bitmap source) {
    if (!pending.tryAcquire()) return "[]";
    Bitmap image = null;
    FaceDetector detector = null;
    Task<List<Face>> task = null;
    try {
      float scale = Math.min(1, 960f / Math.max(source.getWidth(), source.getHeight()));
      Bitmap scaled =
          Bitmap.createScaledBitmap(
              source,
              Math.max(1, Math.round(source.getWidth() * scale)),
              Math.max(1, Math.round(source.getHeight() * scale)),
              true);
      // ML Kit may outlive the import timeout. Give it a bitmap owned by this task.
      image = scaled == source ? source.copy(Bitmap.Config.ARGB_8888, false) : scaled;
      if (image == null) return "[]";
      detector =
          FaceDetection.getClient(
              new FaceDetectorOptions.Builder()
                  .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                  .setMinFaceSize(.06f)
                  .build());
      task = detector.process(InputImage.fromBitmap(image, 0));
      List<Face> result = Tasks.await(task, 12, TimeUnit.SECONDS);
      JSONArray a = new JSONArray();
      for (Face face : result) {
        Rect r = face.getBoundingBox();
        a.put(
            new JSONArray()
                .put(r.left / (double) image.getWidth())
                .put(r.top / (double) image.getHeight())
                .put(r.right / (double) image.getWidth())
                .put(r.bottom / (double) image.getHeight()));
      }
      return a.toString();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "[]";
    } catch (Exception | OutOfMemoryError e) {
      return "[]";
    } finally {
      final Bitmap owned = image;
      final FaceDetector client = detector;
      if (task != null && !task.isComplete()) {
        task.addOnCompleteListener(done -> release(owned, client));
      } else release(owned, client);
    }
  }

  private static void release(Bitmap image, FaceDetector detector) {
    try {
      if (image != null) image.recycle();
      if (detector != null) detector.close();
    } finally {
      pending.release();
    }
  }

  public static double[] boxes(String text) {
    try {
      JSONArray a = new JSONArray(text);
      double[] d = new double[a.length() * 4];
      for (int i = 0; i < a.length(); i++)
        for (int j = 0; j < 4; j++) d[i * 4 + j] = a.getJSONArray(i).getDouble(j);
      return d;
    } catch (Exception e) {
      return new double[0];
    }
  }
}
