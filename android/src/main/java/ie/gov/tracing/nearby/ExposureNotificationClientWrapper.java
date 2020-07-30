package ie.gov.tracing.nearby;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.exposurenotification.ExposureConfiguration;
//import com.google.android.gms.nearby.exposurenotification.ExposureInformation;
import com.google.android.gms.nearby.exposurenotification.ExposureNotificationClient;
import com.google.android.gms.nearby.exposurenotification.ExposureSummary;
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
    String settings = Fetcher.fetch("/settings/exposures", false, appContext);
    Gson gson = new Gson();
    Map map = gson.fromJson(settings, Map.class);

    String exposureConfig = (String) map.get("exposureConfig");
    ExposureConfig config = gson.fromJson(exposureConfig, ExposureConfig.class);

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

  Task<ExposureSummary> getExposureSummary(String token) {
    return exposureNotificationClient.getExposureSummary(token);
  }

  /*
  Task<List<ExposureInformation>> getExposureInformation(String token) {
    return exposureNotificationClient.getExposureInformation(token);
  }*/

}
