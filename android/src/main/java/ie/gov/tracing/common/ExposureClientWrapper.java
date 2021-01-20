package ie.gov.tracing.common;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.google.android.gms.nearby.exposurenotification.DailySummary;
import com.google.android.gms.nearby.exposurenotification.ExposureSummary;
import com.google.android.gms.nearby.exposurenotification.ExposureWindow;
import com.google.android.gms.nearby.exposurenotification.TemporaryExposureKey;
import com.google.android.gms.tasks.Task;

import java.io.File;
import java.util.List;

public abstract class ExposureClientWrapper {
  
  private static ExposureClientWrapper INSTANCE;

  public abstract NfTask<Void> start();

  public abstract NfTask<Void> stop();

  public abstract NfTask<Boolean> isEnabled();

  public abstract NfTask<List<TemporaryExposureKey>> getTemporaryExposureKeyHistory();

  public abstract NfTask<Void> provideDiagnosisKeys(List<File> files, String token, ExposureConfig config);

  public abstract NfTask<Void> provideDiagnosisKeys(List<File> files);

  @Deprecated
  public abstract NfTask<ExposureSummary> getExposureSummary(String token);

  @RequiresApi(api = Build.VERSION_CODES.N)
  public abstract NfTask<List<DailySummary>> getDailySummaries(ExposureConfig config);

  public abstract void setDiagnosisKeysDataMapping(ExposureConfig config);

  public abstract boolean deviceSupportsLocationlessScanning();

  public abstract NfTask<List<ExposureWindow>> getExposureWindows();
  
  public abstract NfTask<Long> getDeviceENSVersion();

}
