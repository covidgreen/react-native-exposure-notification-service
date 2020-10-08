package ie.gov.tracing.hms;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import com.google.android.gms.nearby.exposurenotification.ExposureConfiguration;
import com.google.android.gms.nearby.exposurenotification.ExposureInformation;
import com.google.android.gms.nearby.exposurenotification.ExposureSummary;
import com.google.android.gms.nearby.exposurenotification.TemporaryExposureKey;
import com.google.gson.Gson;
import com.huawei.hmf.tasks.Task;
import com.huawei.hms.contactshield.ContactDetail;
import com.huawei.hms.contactshield.ContactShield;
import com.huawei.hms.contactshield.ContactShieldEngine;
import com.huawei.hms.contactshield.ContactShieldSetting;
import com.huawei.hms.contactshield.ContactSketch;
import com.huawei.hms.contactshield.DiagnosisConfiguration;
import com.huawei.hms.contactshield.PeriodicKey;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import ie.gov.tracing.common.Events;
import ie.gov.tracing.common.ExposureConfig;
import ie.gov.tracing.network.Fetcher;
import ie.gov.tracing.storage.SharedPrefs;

public class ContactShieldWrapper {
    private static final String TAG = "ContactShieldWrapper";

    private static volatile ContactShieldWrapper instance;

    private WeakReference<Context> mContextWeakRef;

    private ContactShieldEngine mContactShieldEngine;

    private ContactShieldWrapper(Context context) {
        mContactShieldEngine = ContactShield.getContactShieldEngine(context);
        mContextWeakRef = new WeakReference<>(context);
    }

    public static ContactShieldWrapper getInstance(Context context) {
        if (instance == null) {
            synchronized (ContactShieldWrapper.class) {
                if (instance == null) {
                    instance = new ContactShieldWrapper(context);
                }
            }
        }
        return instance;
    }

    public Task<Void> start() {
        return mContactShieldEngine.startContactShield(ContactShieldSetting.DEFAULT);
    }

    public Task<Void> stop() {
        return mContactShieldEngine.stopContactShield();
    }

    public Task<Boolean> isEnabled() {
        return mContactShieldEngine.isContactShieldRunning();
    }

    public Task<Void> provideDiagnosisKeys(List<File> files, String token) {
        Context context = mContextWeakRef.get();
        String settings = Fetcher.fetch("/settings/exposures", false, context);
        Gson gson = new Gson();
        Map map = gson.fromJson(settings, Map.class);

        String exposureConfig = (String) map.get("exposureConfig");
        ExposureConfig config = gson.fromJson(exposureConfig, ExposureConfig.class);

        Events.raiseEvent(Events.INFO, "mapping exposure configuration with " + config);
        // error will be thrown here if config is not complete
        ExposureConfiguration configuration =
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
        SharedPrefs.setString("thresholdWeightings", Arrays.toString(config.getThresholdWeightings()), context);
        SharedPrefs.setLong("timeThreshold", config.getTimeThreshold(), context);

        Events.raiseEvent(Events.INFO, "processing diagnosis keys with: " + configuration);
        PendingIntent pendingIntent = PendingIntent.getService(context, 0,
                new Intent(context, BackgroundContackShieldIntentService.class), PendingIntent.FLAG_UPDATE_CURRENT);
        DiagnosisConfiguration diagnosisConfiguration = new DiagnosisConfiguration.Builder()
                .setMinimumRiskValueThreshold(configuration.getMinimumRiskScore())
                .setAttenuationRiskValues(configuration.getAttenuationScores())
                .setDaysAfterContactedRiskValues(configuration.getDaysSinceLastExposureScores())
                .setDurationRiskValues(configuration.getDurationScores())
                .setInitialRiskLevelRiskValues(configuration.getTransmissionRiskScores())
                .setAttenuationDurationThresholds(configuration.getDurationAtAttenuationThresholds())
                .build();
        return mContactShieldEngine.putSharedKeyFiles(pendingIntent, files, diagnosisConfiguration, token);
    }

    public Task<List<PeriodicKey>> getTemporaryExposureKeyHistory() {
        return mContactShieldEngine.getPeriodicKey();
    }

    public Task<ContactSketch> getExposureSummary(String token) {
        return mContactShieldEngine.getContactSketch(token);
    }

    public Task<List<ContactDetail>> getExposureInformation(String token) {
        return mContactShieldEngine.getContactDetail(token);
    }

    public static List<TemporaryExposureKey> getTemporaryExposureKeyList(List<PeriodicKey> periodicKeyList) {
        List<TemporaryExposureKey> temporaryExposureKeyList = new ArrayList<>();
        for (PeriodicKey periodicKey : periodicKeyList) {
            TemporaryExposureKey temporaryExposureKey =
                    new TemporaryExposureKey.TemporaryExposureKeyBuilder()
                            .setKeyData(periodicKey.getContent())
                            .setRollingStartIntervalNumber((int) periodicKey.getPeriodicKeyValidTime())
                            .setRollingPeriod((int) periodicKey.getPeriodicKeyLifeTime())
                            .setTransmissionRiskLevel(periodicKey.getInitialRiskLevel())
                            .build();
            temporaryExposureKeyList.add(temporaryExposureKey);
        }
        return temporaryExposureKeyList;
    }

    public static ExposureSummary getExposureSummary(ContactSketch contactSketch) {
        return new ExposureSummary.ExposureSummaryBuilder()
                .setDaysSinceLastExposure(contactSketch.getDaysSinceLastHit())
                .setMatchedKeyCount(contactSketch.getNumberOfHits())
                .setMaximumRiskScore(contactSketch.getMaxRiskValue())
                .setSummationRiskScore(contactSketch.getSummationRiskValue())
                .setAttenuationDurations(contactSketch.getAttenuationDurations())
                .build();
    }

    public static List<ExposureInformation> getExposureInformationList(List<ContactDetail> contactDetailList) {
        List<ExposureInformation> exposureInformationList = new ArrayList<>();
        for (ContactDetail detail : contactDetailList) {
            ExposureInformation exposureInformation =
                    new ExposureInformation.ExposureInformationBuilder()
                            .setDateMillisSinceEpoch(detail.getDayNumber())
                            .setAttenuationValue(detail.getAttenuationRiskValue())
                            .setTransmissionRiskLevel(detail.getInitialRiskLevel())
                            .setDurationMinutes(detail.getDurationMinutes())
                            .setAttenuationDurations(detail.getAttenuationDurations())
                            .setTotalRiskScore(detail.getTotalRiskValue())
                            .build();
            exposureInformationList.add(exposureInformation);
        }
        return exposureInformationList;
    }
}
