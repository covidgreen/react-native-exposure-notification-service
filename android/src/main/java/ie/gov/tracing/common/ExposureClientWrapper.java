package ie.gov.tracing.common;

import android.content.Context;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.google.android.gms.nearby.exposurenotification.DailySummary;
import com.google.android.gms.nearby.exposurenotification.ExposureSummary;
import com.google.android.gms.nearby.exposurenotification.ExposureWindow;
import com.google.android.gms.nearby.exposurenotification.TemporaryExposureKey;
import com.google.android.gms.tasks.Task;

import java.io.File;
import java.util.List;
import ie.gov.tracing.common.ExposureConfig;

public abstract class ExposureClientWrapper {
  
  private static ExposureClientWrapper INSTANCE;

  public static ExposureClientWrapper get(Context context) {
    if (INSTANCE == null) {
      INSTANCE = new ExposureClientWrapper(context);
    }
    return INSTANCE;    
  }

  public abstract Task<Void> start();

  public abstract Task<Void> stop();

  public abstract Task<Boolean> isEnabled();

  public abstract Task<List<TemporaryExposureKey>> getTemporaryExposureKeyHistory();

  public abstract Task<Void> provideDiagnosisKeys(List<File> files, String token, ExposureConfig config);

  public abstract Task<Void> provideDiagnosisKeys(List<File> files);

  @Deprecated
  public abstract Task<ExposureSummary> getExposureSummary(String token);

  @RequiresApi(api = Build.VERSION_CODES.N)
  public abstract Task<List<DailySummary>> getDailySummaries(ExposureConfig config);

  public abstract void setDiagnosisKeysDataMapping(ExposureConfig config);

  public abstract boolean deviceSupportsLocationlessScanning();

  public abstract Task<List<ExposureWindow>> getExposureWindows();
  
  public abstract Task<Long> getDeviceENSVersion();

}
