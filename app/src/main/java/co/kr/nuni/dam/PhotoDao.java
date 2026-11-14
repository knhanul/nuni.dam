package co.kr.nuni.dam;

import androidx.room.*;
import java.util.List;

@Dao
public interface PhotoDao {
  @Query("SELECT * FROM photos WHERE trashedAt=0 ORDER BY importedAt DESC")
  List<Photo> active();

  @Query("SELECT * FROM photos WHERE trashedAt>0 ORDER BY trashedAt DESC")
  List<Photo> trash();

  @Query("SELECT * FROM photos WHERE id=:id")
  Photo get(String id);

  @Query("SELECT COUNT(*) FROM photos WHERE trashedAt=0")
  int count();

  @Query("SELECT COALESCE(SUM(bytes),0) FROM photos")
  long used();

  @Insert
  void insert(Photo p);

  @Update
  void update(Photo p);

  @Query("UPDATE photos SET note=:note WHERE id=:id")
  void note(String id, String note);

  @Query("UPDATE photos SET favourite=:v WHERE id=:id")
  void favourite(String id, boolean v);

  @Query("UPDATE photos SET focusX=:x,focusY=:y WHERE id=:id")
  void focus(String id, float x, float y);

  @Query("UPDATE photos SET trashedAt=:time WHERE id=:id")
  void trash(String id, long time);

  @Query("UPDATE photos SET trashedAt=0,owned=1 WHERE id=:id")
  void restore(String id);

  @Query("DELETE FROM photos WHERE id=:id AND trashedAt>0")
  void purge(String id);

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  void link(SourceLink link);

  @Query("SELECT * FROM sources WHERE root=:root AND document=:doc")
  SourceLink source(String root, String doc);

  @Query(
      "SELECT DISTINCT p.* FROM photos p JOIN sources s ON p.id=s.photoId WHERE s.root=:root AND"
          + " s.scan<>:scan")
  List<Photo> missing(String root, long scan);

  @Query("DELETE FROM sources WHERE root=:root AND scan<>:scan")
  void removeMissing(String root, long scan);

  @Query("SELECT COUNT(*) FROM sources WHERE photoId=:id")
  int references(String id);

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  void quote(Quote q);

  @Query("DELETE FROM quotes WHERE id=:id")
  void deleteQuote(int id);

  @Query("SELECT * FROM photos WHERE hasGps=1 AND placeName='' ")
  List<Photo> needingPlaceName();

  @Query("SELECT * FROM photos WHERE owned=1")
  java.util.List<Photo> owned();

  @Query("UPDATE photos SET publicUri=:uri WHERE id=:id")
  void setPublicUri(String id, String uri);

  @Query("SELECT COALESCE(MAX(id),999) FROM quotes WHERE id>=1000")
  int maxCustomQuoteId();

  @Query("SELECT * FROM quotes ORDER BY id")
  List<Quote> quotes();
}
