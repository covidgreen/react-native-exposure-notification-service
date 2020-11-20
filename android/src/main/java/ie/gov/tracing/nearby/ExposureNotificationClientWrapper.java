package ie.gov.tracing.nearby;

import android.content.Context;


import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.exposurenotification.DailySummariesConfig;
import com.google.android.gms.nearby.exposurenotification.DailySummary;
import com.google.android.gms.nearby.exposurenotification.ExposureConfiguration;
import com.google.android.gms.nearby.exposurenotification.ExposureNotificationClient;
import com.google.android.gms.nearby.exposurenotification.ExposureSummary;
import com.google.android.gms.nearby.exposurenotification.ExposureWindow;
import com.google.android.gms.nearby.exposurenotification.TemporaryExposureKey;
import com.google.android.gms.tasks.Task;
import com.google.gson.Gson;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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

  Task<Void> provideDiagnosisKeys(List<File> files, String token) {
    ExposureConfig config = fetchExposureConfig();

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

  public ExposureConfig fetchExposureConfig() {
    String settings = Fetcher.fetch("/settings/exposures", appContext);
    Gson gson = new Gson();
    Map map = gson.fromJson(settings, Map.class);
    String exposureConfig = (String) map.get("exposureConfig");
    return gson.fromJson(exposureConfig, ExposureConfig.class);
  }


  Task<Void> provideDiagnosisKeys(List<File> files) {

    Events.raiseEvent(Events.INFO, "processing diagnosis keys with v1.6");

    return exposureNotificationClient
            .provideDiagnosisKeys(files);
  }

  @Deprecated
  Task<ExposureSummary> getExposureSummary(String token) {
    return exposureNotificationClient.getExposureSummary(token);
  }

  Task<List<DailySummary>> getDailySummaries(ExposureConfig config) {
    DailySummariesConfig.DailySummariesConfigBuilder builder = new DailySummariesConfig.DailySummariesConfigBuilder();
    DailySummariesConfig dailySummaryConfig = builder
            // A map that stores a weight for each possible value of reportType.
//            .setReportTypeWeight() //FIXME what is the good value ?

            // attenuationBucketThresholdDb, attenuationBucketWeights:
            // - attenuationBucketThresholdDb: Thresholds defining the BLE attenuation buckets edges. This list must have 3 elements: the immediate, near, and medium thresholds.
            // - attenuationBucketWeights: Duration weights to associate with ScanInstances depending on the attenuation bucket in which their typicalAttenuation falls.
            //     This list must have four elements, corresponding to the weights for the following buckets:
            //        Immediate bucket: -infinity < attenuation <= immediate threshold
            //        Near bucket: immediate threshold < attenuation <= near threshold
            //        Medium bucket: near threshold < attenuation <= medium threshold
            //        Other bucket: medium threshold < attenuation < +infinity
            //     Each element must be between 0 and 2.5.
//            .setAttenuationBuckets(attenuationBucketThresholdDb, attenuationBucketWeights ) //FIXME what is the good value ?

            // To return all available day summaries, set to 0, which is treated differently.
            .setDaysSinceExposureThreshold(0) //FIXME what is the good value ?

            //  A map that stores a weight for each possible value of infectiousness.
            //    In v1.7 and higher, ExposureWindow objects with infectiousness=NONE do not contribute to the risk value. If a weight is specified for infectiousness=NONE, that weight value is disregarded.
            //    In v1.6, if a weight is specified for infectiousness=NONE, it should be 0 because NONE indicates no risk of exposure.
//            .setInfectiousnessWeight(config.getTransmissionRiskWeight()) //FIXME what is the good value ?
            .build();
    return exposureNotificationClient.getDailySummaries(dailySummaryConfig);
  }

  public boolean deviceSupportsLocationlessScanning() {
    return exposureNotificationClient.deviceSupportsLocationlessScanning();
  }

  public Task<List<ExposureWindow>> getExposureWindows() {
    return exposureNotificationClient.getExposureWindows(ExposureNotificationClient.TOKEN_A);
  }

}
