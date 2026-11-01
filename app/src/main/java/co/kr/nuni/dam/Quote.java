package co.kr.nuni.dam;

import androidx.annotation.NonNull;
import androidx.room.*;

@Entity(tableName = "quotes")
public class Quote {
  @PrimaryKey public int id;
  @NonNull public String text = "";
}
