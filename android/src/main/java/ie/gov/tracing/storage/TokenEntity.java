package ie.gov.tracing.storage;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.google.common.base.Preconditions;

@Entity
public class TokenEntity {

  @PrimaryKey
  @ColumnInfo(name = "token")
  @NonNull
  private String token;

  @ColumnInfo(name = "created_timestamp_ms")
  private long createdTimestampMs;

  @ColumnInfo(name = "responded")
  private boolean responded;

  TokenEntity(@NonNull String token, boolean responded) {
    this.createdTimestampMs = System.currentTimeMillis();
    this.token = token;
    this.responded = responded;
  }

  /**
   * Creates a TokenEntity.
   *
   * @param token The token identifier.
   * @param responded
   */
  public static TokenEntity create(@NonNull String token, boolean responded) {
    return new TokenEntity(Preconditions.checkNotNull(token), responded);
  }

  public long getCreatedTimestampMs() {
    return createdTimestampMs;
  }

  void setCreatedTimestampMs(long ms) {
    this.createdTimestampMs = ms;
  }

  @NonNull
  public String getToken() {
    return token;
  }

  public void setToken(@NonNull String token) {
    this.token = token;
  }

  public boolean isResponded() {
    return responded;
  }

  public void setResponded(boolean responded) {
    this.responded = responded;
  }

}