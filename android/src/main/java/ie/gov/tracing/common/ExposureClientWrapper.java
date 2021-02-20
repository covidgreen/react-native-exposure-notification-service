package ie.gov.tracing.common;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.google.android.gms.nearby.exposurenotification.DailySummary;
import com.google.android.gms.nearby.exposurenotification.ExposureSummary;
import com.google.android.gms.nearby.exposurenotification.ExposureWindow;
import com.google.android.gms.nearby.exposurenotification.TemporaryExposureKey;
import com.google.android.gms.tasks.Task;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.List;

public abstract class ExposureClientWrapper {
  
  private static ExposureClientWrapper INSTANCE;

  public abstract ListenableFuture<Void> start();

  public abstract ListenableFuture<Void> stop();

  public abstract ListenableFuture<Boolean> isEnabled();

  public abstract ListenableFuture<List<TemporaryExposureKey>> getTemporaryExposureKeyHistory();

  public abstract NfTask<Void> provideDiagnosisKeys(List<File> files, String token, ExposureConfig config);

  public abstract NfTask<Void> provideDiagnosisKeys(List<File> files);

  @Deprecated
  public abstract  ListenableFuture<ExposureSummary> getExposureSummary(String token);

  @RequiresApi(api = Build.VERSION_CODES.N)
  public abstract NfTask<List<DailySummary>> getDailySummaries(ExposureConfig config);

  public abstract void setDiagnosisKeysDataMapping(ExposureConfig config);

  public abstract boolean deviceSupportsLocationlessScanning();

  public abstract NfTask<List<ExposureWindow>> getExposureWindows();
  
  public abstract ListenableFuture<Long> getDeviceENSVersion();

}
