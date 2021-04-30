package ie.gov.tracing.nearby;

import android.content.Context;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.exposurenotification.DailySummariesConfig;
import com.google.android.gms.nearby.exposurenotification.DailySummary;
import com.google.android.gms.nearby.exposurenotification.DiagnosisKeyFileProvider;
import com.google.android.gms.nearby.exposurenotification.DiagnosisKeysDataMapping;
import com.google.android.gms.nearby.exposurenotification.ExposureConfiguration;
import com.google.android.gms.nearby.exposurenotification.ExposureNotificationClient;
import com.google.android.gms.nearby.exposurenotification.ExposureSummary;
import com.google.android.gms.nearby.exposurenotification.ExposureWindow;
import com.google.android.gms.nearby.exposurenotification.Infectiousness;
import com.google.android.gms.nearby.exposurenotification.ReportType;
import com.google.android.gms.nearby.exposurenotification.TemporaryExposureKey;
import com.google.android.gms.tasks.Tasks;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.threeten.bp.Duration;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ie.gov.tracing.Tracing;
import ie.gov.tracing.common.AppExecutors;
import ie.gov.tracing.common.Config;
import ie.gov.tracing.common.Events;
import ie.gov.tracing.common.ExposureConfig;
import ie.gov.tracing.common.ExposureClientWrapper;
import ie.gov.tracing.common.NfTask;
import ie.gov.tracing.common.TaskToFutureAdapter;
import ie.gov.tracing.storage.SharedPrefs;

public class ExposureNotificationClientWrapper extends ExposureClientWrapper {

  private static ExposureNotificationClientWrapper INSTANCE;

  private final Context context;
  private final ExposureNotificationClient exposureNotificationClient;

  public static ExposureNotificationClientWrapper get(Context context) {
    if (INSTANCE == null) {
      INSTANCE = new ExposureNotificationClientWrapper(context);
    }
    return INSTANCE;
  }

  private ExposureNotificationClientWrapper(Context context) {
    this.context = context.getApplicationContext();
    exposureNotificationClient = Nearby.getExposureNotificationClient(this.context);
  }

  public boolean checkAvailability() throws Exception {
    int result =  GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this.context);

    if (result == ConnectionResult.SUCCESS) {
      return true;
    } else if (result == ConnectionResult.SERVICE_INVALID || result == ConnectionResult.SERVICE_DISABLED || result == ConnectionResult.SERVICE_MISSING) {
      Events.raiseEvent(Events.INFO, "GMS Not Available" + result);
      Tracing.base.setApiError(result);
      return false;
    } else {
      throw new Exception("GMS Not available, " + result);
    }
  }


  public ListenableFuture<Void> start() {
    return  FluentFuture.from(isEnabled())
            .transformAsync(enabled -> {
              if (enabled) {
                return Futures.immediateVoidFuture();
              }
              Events.raiseEvent(Events.INFO, "starting exposure tracing...");

              return FluentFuture.from(TaskToFutureAdapter.getFutureWithTimeout(
                new NfTask(exposureNotificationClient.start()),
                Duration.ofSeconds(Config.API_TIMEOUT).toMillis(),
                TimeUnit.MILLISECONDS,
                this.context,
                AppExecutors.getScheduledExecutor()))
                  .transformAsync(stat -> Futures.immediateVoidFuture(), AppExecutors.getScheduledExecutor());
            }, AppExecutors.getScheduledExecutor());
  }

  public ListenableFuture<Void> stop() {
    return  FluentFuture.from(isEnabled())
            .transformAsync(enabled -> {
              if (!enabled) {
                return Futures.immediateVoidFuture();
              }
              Events.raiseEvent(Events.INFO, "stopping exposure tracing...");

              return FluentFuture.from(TaskToFutureAdapter.getFutureWithTimeout(
                      new NfTask(exposureNotificationClient.stop()),
                      Duration.ofSeconds(Config.API_TIMEOUT).toMillis(),
                      TimeUnit.MILLISECONDS,
                      this.context,
                      AppExecutors.getScheduledExecutor()))
                      .transformAsync(stat -> Futures.immediateVoidFuture(), AppExecutors.getScheduledExecutor());
            }, AppExecutors.getScheduledExecutor());
  }

  public ListenableFuture<Boolean> isEnabled() {
    return TaskToFutureAdapter.getFutureWithTimeout(
            new NfTask(exposureNotificationClient.isEnabled()),
            Duration.ofSeconds(Config.API_TIMEOUT).toMillis(),
            TimeUnit.MILLISECONDS,
            this.context,
            AppExecutors.getScheduledExecutor());

  }

  public ListenableFuture<List<TemporaryExposureKey>> getTemporaryExposureKeyHistory() {
    return TaskToFutureAdapter.getFutureWithTimeout(
            new NfTask(exposureNotificationClient.getTemporaryExposureKeyHistory()),
            Duration.ofSeconds(Config.API_TIMEOUT).toMillis(),
            TimeUnit.MILLISECONDS,
            this.context,
            AppExecutors.getScheduledExecutor());
  }

  public NfTask<Void> provideDiagnosisKeys(List<File> files, String token, ExposureConfig config) {

    Events.raiseEvent(Events.INFO, "mapping exposure configuration with " + config);
    // error will be thrown here if config is not complete
    ExposureConfiguration exposureConfiguration =
            new ExposureConfiguration.ExposureConfigurationBuilder()
                    .setAttenuationScores(config.getAttenuationLevelValues())
                    .setDaysSinceLastExposureScores(config.getDaysSinceLastExposureLevelValues())
                    .setTransmissionRiskScores(config.getTransmissionRiskLevelValues())
                    .setDurationScores(config.getDurationLevelValues())
                    .setMinimumRiskScore(config.getMinimumRiskScore())
                    .setDurationWeight(config.getDurationWeight())
                    .setAttenuationWeight(config.getAttenuationWeight())
                    .setTransmissionRiskWeight(config.getTransmissionRiskWeight())
                    .setDaysSinceLastExposureWeight(config.getDaysSinceLastExposureWeight())
                    .setDurationAtAttenuationThresholds(config.getDurationAtAttenuationThresholds()).build();

    // we use these when we receive match broadcasts from exposure API
    SharedPrefs.setString("thresholdWeightings", Arrays.toString(config.getThresholdWeightings()), this.context);
    SharedPrefs.setLong("timeThreshold", config.getTimeThreshold(), this.context);

    Events.raiseEvent(Events.INFO, "processing diagnosis keys with: " + exposureConfiguration);

    return new NfTask(exposureNotificationClient
            .provideDiagnosisKeys(files, exposureConfiguration, token));
  }

  public NfTask<Void> provideDiagnosisKeys(List<File> files) {

    Events.raiseEvent(Events.INFO, "processing diagnosis keys with v1.6");

    DiagnosisKeyFileProvider provider = new DiagnosisKeyFileProvider(files);
    return new NfTask(exposureNotificationClient
            .provideDiagnosisKeys(provider));
  }

  @Deprecated
  public ListenableFuture<ExposureSummary> getExposureSummary(String token) {
    return TaskToFutureAdapter.getFutureWithTimeout(
            new NfTask(exposureNotificationClient.getExposureSummary(token)),
            Duration.ofSeconds(Config.API_TIMEOUT).toMillis(),
            TimeUnit.MILLISECONDS,
            this.context,
            AppExecutors.getScheduledExecutor());
  }

  private List<Integer> convertArray(int[] data) {
    List<Integer> intList = new ArrayList<>(data.length);
    for (int i : data)
    {
      intList.add(i);
    }
    return intList;
  }

  public NfTask<List<DailySummary>> getDailySummaries(ExposureConfig config) {
    DailySummariesConfig.DailySummariesConfigBuilder builder = new DailySummariesConfig.DailySummariesConfigBuilder();
    List<Double> attenuationWeightings = Arrays.asList(config.getImmediateDurationWeight() / 100.0, config.getNearDurationWeight() / 100.0, config.getMediumDurationWeight() / 100.0, config.getOtherDurationWeight() / 100.0);
    List<Integer> attenuations = convertArray(config.getAttenuationDurationThresholds());
    DailySummariesConfig dailySummaryConfig = builder
            // A map that stores a weight for each possible value of reportType.
            .setReportTypeWeight(ReportType.CONFIRMED_TEST, config.getReportTypeConfirmedTestWeight() / 100.0)
            .setReportTypeWeight(ReportType.CONFIRMED_CLINICAL_DIAGNOSIS, config.getReportTypeConfirmedClinicalDiagnosisWeight() / 100.0)
            .setReportTypeWeight(ReportType.SELF_REPORT, config.getReportTypeSelfReportedWeight() / 100.0)
            .setReportTypeWeight(ReportType.RECURSIVE, config.getReportTypeRecursiveWeight() / 100.0)
            .setAttenuationBuckets(attenuations, attenuationWeightings)
            // To return all available day summaries, set to 0, which is treated differently.
            .setDaysSinceExposureThreshold(0)
            .setInfectiousnessWeight(Infectiousness.STANDARD, config.getInfectiousnessStandardWeight() / 100.0)
            .setInfectiousnessWeight(Infectiousness.HIGH, config.getInfectiousnessHighWeight() / 100.0)
            .setMinimumWindowScore(config.getMinimumRiskScoreFullRange())
            .build();
    return new NfTask(exposureNotificationClient.getDailySummaries(dailySummaryConfig));
  }

  public void setDiagnosisKeysDataMapping(ExposureConfig config) {
    DiagnosisKeysDataMapping.DiagnosisKeysDataMappingBuilder builder = new DiagnosisKeysDataMapping.DiagnosisKeysDataMappingBuilder();
    Map<Integer, Integer> infectedDays = new HashMap<Integer, Integer>();
    int counter = 0;
    int[] onsetType = config.getInfectiousnessForDaysSinceOnsetOfSymptoms();

    for (Integer i = -14; i <= 14; i++) {
      if (counter < onsetType.length) {
        infectedDays.put(i, onsetType[counter]);
      } else {
        infectedDays.put(i, Infectiousness.NONE);
      }
      counter += 1;
    }
    Events.raiseEvent(Events.INFO, "Setting diagnosis key mapping");
    DiagnosisKeysDataMapping mappings = builder
            .setInfectiousnessWhenDaysSinceOnsetMissing(Infectiousness.STANDARD)
            .setReportTypeWhenMissing(ReportType.CONFIRMED_TEST)
            .setDaysSinceOnsetToInfectiousness(infectedDays)
            .build();

    DiagnosisKeysDataMapping currentMap = null;
    try {
      currentMap = Tasks.await(exposureNotificationClient.getDiagnosisKeysDataMapping());
    } catch (Exception e) {
      // ignore
      Events.raiseError("getDiagnosisKeysDataMapping", e);
    }
    if (!currentMap.equals(mappings)) {
      try {
        Tasks.await(exposureNotificationClient.setDiagnosisKeysDataMapping(mappings));
        Events.raiseEvent(Events.INFO, "Setting DiagnosisKeysDataMapping");
      } catch (Exception e) {
        // ignore
        Events.raiseError("setDiagnosisKeysDataMapping", e);
      }
    }
    return;
  }

  public boolean deviceSupportsLocationlessScanning() {
    return exposureNotificationClient.deviceSupportsLocationlessScanning();
  }

  public NfTask<List<ExposureWindow>> getExposureWindows() {
    return new NfTask(exposureNotificationClient.getExposureWindows());
  }
  
  public ListenableFuture<Long> getDeviceENSVersion() {
    return TaskToFutureAdapter.getFutureWithTimeout(
            new NfTask(exposureNotificationClient.getVersion()),
            Duration.ofSeconds(Config.API_TIMEOUT).toMillis(),
            TimeUnit.MILLISECONDS,
            this.context,
            AppExecutors.getScheduledExecutor());
  }

}
