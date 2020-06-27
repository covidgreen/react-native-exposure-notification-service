package ie.gov.tracing.storage;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;

@Dao
abstract class ExposureDao {

  @Query("SELECT * FROM ExposureEntity ORDER BY created_timestamp_ms DESC")
  abstract ListenableFuture<List<ExposureEntity>> getAllAsync();

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract ListenableFuture<Void> upsertAsync(List<ExposureEntity> entities);

  @Query("DELETE FROM ExposureEntity")
  abstract ListenableFuture<Void> deleteAllAsync();

  @Query("DELETE FROM ExposureEntity WHERE created_timestamp_ms < :deleteBeforeMs")
  abstract ListenableFuture<Void> deleteBefore(long deleteBeforeMs);

}
