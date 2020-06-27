package ie.gov.tracing.storage;

import android.content.Context;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;

/**
 * Abstracts access to the database layers.
 */
public class ExposureNotificationRepository {
  private final ExposureDao exposureDao;
  private final TokenDao tokenDao;

  public ExposureNotificationRepository(Context context) {
    ExposureNotificationDatabase exposureNotificationDatabase =
        ExposureNotificationDatabase.getInstance(context);
    exposureDao = exposureNotificationDatabase.exposureDao();
    tokenDao = exposureNotificationDatabase.tokenDao();
  }

  public ListenableFuture<Void> upsertExposureEntitiesAsync(List<ExposureEntity> entities) {
    return exposureDao.upsertAsync(entities);
  }

  public ListenableFuture<Void> deleteAllExposureEntitiesAsync() {
    return exposureDao.deleteAllAsync();
  }

  public ListenableFuture<List<ExposureEntity>> getAllExposureEntitiesAsync() {
    return exposureDao.getAllAsync();
  }

  public ListenableFuture<Void> upsertTokenEntityAsync(TokenEntity entity) {
    return tokenDao.upsertAsync(entity);
  }

  public ListenableFuture<Void> markTokenEntityRespondedAsync(String token) {
    return tokenDao.markTokenRespondedAsync(token);
  }

  public ListenableFuture<Void> deleteTokenEntityAsync(String token) {
    return tokenDao.deleteByTokenAsync(token);
  }

  public ListenableFuture<Void> deleteAllTokensAsync() {
    return tokenDao.deleteAllTokensAsync();
  }

  public ListenableFuture<Void> deleteTokensBefore(long beforeTimeMs) {
    return tokenDao.deleteBefore(beforeTimeMs);
  }

  public ListenableFuture<Void> deleteExposuresBefore(long beforeTimeMs) {
    return exposureDao.deleteBefore(beforeTimeMs);
  }
}
