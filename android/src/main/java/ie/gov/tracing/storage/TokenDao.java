package ie.gov.tracing.storage;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;

@Dao
public abstract class TokenDao {

  @Query("SELECT * FROM TokenEntity")
  abstract ListenableFuture<List<TokenEntity>> getAllAsync();

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract ListenableFuture<Void> upsertAsync(TokenEntity entity);

  @Query("UPDATE TokenEntity SET responded=1 WHERE token = :token")
  abstract ListenableFuture<Void> markTokenRespondedAsync(String token);

  @Query("DELETE FROM TokenEntity WHERE token = :token")
  abstract ListenableFuture<Void> deleteByTokenAsync(String token);

  @Query("DELETE FROM TokenEntity WHERE token IN (:tokens)")
  abstract ListenableFuture<Void> deleteByTokensAsync(List<String> tokens);

  @Query("DELETE FROM TokenEntity")
  abstract ListenableFuture<Void> deleteAllTokensAsync();

  @Query("DELETE FROM TokenEntity WHERE created_timestamp_ms < :deleteBeforeMs")
  abstract ListenableFuture<Void> deleteBefore(long deleteBeforeMs);
}
