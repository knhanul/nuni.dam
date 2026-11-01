package co.kr.nuni.dam;

import androidx.room.*;

@Database(
    entities = {Photo.class, SourceLink.class, Quote.class},
    version = 1,
    exportSchema = true)
public abstract class NuniDb extends RoomDatabase {
  public abstract PhotoDao photos();
}
