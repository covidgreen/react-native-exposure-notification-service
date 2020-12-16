package ie.gov.tracing.nearby;

import android.content.Context;
import android.os.Build;


import androidx.annotation.RequiresApi;

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
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.Gson;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import ie.gov.tracing.Tracing;
import ie.gov.tracing.common.Events;
import ie.gov.tracing.common.ExposureConfig;
import ie.gov.tracing.network.Fetcher;
import ie.gov.tracing.storage.SharedPrefs;

public class ExposureNotificationClientWrapper {

  private static ExposureNotificationClientWrapper INSTANCE;

  private final Context appContext;
  private final ExposureNotificationClient exposureNotificationClient;

  public static ExposureNotificationClientWrapper get(Context context) {
    if (INSTANCE == null) {
      INSTANCE = new ExposureNotificationClientWrapper(context);
    }
    return INSTANCE;
  }

  private ExposureNotificationClientWrapper(Context context) {
    this.appContext = context.getApplicationContext();
    exposureNotificationClient = Nearby.getExposureNotificationClient(appContext);
  }

  Task<Void> start() {
    return exposureNotificationClient.start();
  }

  Task<Void> stop() {
    return exposureNotificationClient.stop();
  }

  public Task<Boolean> isEnabled() {
    return exposureNotificationClient.isEnabled();
  }

  public Task<List<TemporaryExposureKey>> getTemporaryExposureKeyHistory() {
    // will only return inactive keys i.e. not today's
    return exposureNotificationClient.getTemporaryExposureKeyHistory();
  }

  Task<Void> provideDiagnosisKeys(List<File> files, String token, ExposureConfig config) {

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
    SharedPrefs.setString("thresholdWeightings", Arrays.toString(config.getThresholdWeightings()), appContext);
    SharedPrefs.setLong("timeThreshold", config.getTimeThreshold(), appContext);

    Events.raiseEvent(Events.INFO, "processing diagnosis keys with: " + exposureConfiguration);

    return exposureNotificationClient
            .provideDiagnosisKeys(files, exposureConfiguration, token);
  }

  public ListenableFuture<ExposureConfig> fetchExposureConfig(Context context) {
    String version = Tracing.version(context).getString("display");
    String settings = Fetcher.fetch("/settings/exposures?os=android&version=" + version, context);
    Gson gson = new Gson();
    Map map = gson.fromJson(settings, Map.class);
    String exposureConfig = (String) map.get("exposureConfig");
    ExposureConfig config = gson.fromJson(exposureConfig, ExposureConfig.class);

    SharedPrefs.setString("exposureConfig", exposureConfig, context);
    return Futures.immediateFuture(config);
  }

  Task<Void> provideDiagnosisKeys(List<File> files) {

    Events.raiseEvent(Events.INFO, "processing diagnosis keys with v1.6");

    DiagnosisKeyFileProvider provider = new DiagnosisKeyFileProvider(files);
    return exposureNotificationClient
            .provideDiagnosisKeys(provider);
  }

  @Deprecated
  public Task<ExposureSummary> getExposureSummary(String token) {
    return exposureNotificationClient.getExposureSummary(token);
  }

  @RequiresApi(api = Build.VERSION_CODES.N)
  public Task<List<DailySummary>> getDailySummaries(ExposureConfig config) {
    DailySummariesConfig.DailySummariesConfigBuilder builder = new DailySummariesConfig.DailySummariesConfigBuilder();
    List<Double> attenuationWeightings = Arrays.asList(config.getImmediateDurationWeight() / 100.0, config.getNearDurationWeight() / 100.0, config.getMediumDurationWeight() / 100.0, config.getOtherDurationWeight() / 100.0);
    List<Integer> attenuations = Arrays.stream(config.getAttenuationDurationThresholds()).boxed().collect(Collectors.toList());
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
    return exposureNotificationClient.getDailySummaries(dailySummaryConfig);
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

  public Task<List<ExposureWindow>> getExposureWindows() {
    return exposureNotificationClient.getExposureWindows();
  }
  
  public Task<Long> getDeviceENSVersion() {
    return exposureNotificationClient.getVersion();
  }
    

}
