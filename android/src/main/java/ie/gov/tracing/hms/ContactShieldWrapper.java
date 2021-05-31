package ie.gov.tracing.hms;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import com.google.android.gms.nearby.exposurenotification.DailySummary;
import com.google.android.gms.nearby.exposurenotification.ExposureConfiguration;
import com.google.android.gms.nearby.exposurenotification.ExposureSummary;
import com.google.android.gms.nearby.exposurenotification.ExposureWindow;
import com.google.android.gms.nearby.exposurenotification.TemporaryExposureKey;

import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.Gson;
import com.huawei.hmf.tasks.Task;
import com.huawei.hms.api.ConnectionResult;
import com.huawei.hms.api.HuaweiApiAvailability;
import com.huawei.hms.contactshield.ContactDetail;
import com.huawei.hms.contactshield.ContactShield;
import com.huawei.hms.contactshield.ContactShieldEngine;
import com.huawei.hms.contactshield.ContactShieldSetting;
import com.huawei.hms.contactshield.ContactSketch;
import com.huawei.hms.contactshield.DiagnosisConfiguration;
import com.huawei.hms.contactshield.PeriodicKey;

import org.threeten.bp.Duration;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import ie.gov.tracing.Tracing;
import ie.gov.tracing.common.AppExecutors;
import ie.gov.tracing.common.Config;
import ie.gov.tracing.common.Events;
import ie.gov.tracing.common.ExposureConfig;
import ie.gov.tracing.common.ExposureClientWrapper;
import ie.gov.tracing.common.NfTask;
import ie.gov.tracing.common.TaskToFutureAdapter;

public class ContactShieldWrapper  extends ExposureClientWrapper {
    private static final String TAG = "ContactShieldWrapper";

    private static volatile ContactShieldWrapper instance;

    private WeakReference<Context> mContextWeakRef;

    private ContactShieldEngine mContactShieldEngine;
    private Context context;

    private ContactShieldWrapper(Context context) {
        mContactShieldEngine = ContactShield.getContactShieldEngine(context);
        mContextWeakRef = new WeakReference<>(context);
        this.context = context;
    }

    public static ContactShieldWrapper get(Context context) {
        if (instance == null) {
            synchronized (ContactShieldWrapper.class) {
                if (instance == null) {
                    instance = new ContactShieldWrapper(context);
                }
            }
        }
        return instance;
    }

    public boolean checkAvailability() throws Exception {
        int result = HuaweiApiAvailability.getInstance().isHuaweiMobileServicesAvailable(this.context);

        if (result == ConnectionResult.SUCCESS) {
            return true;
        } else if (result == ConnectionResult.SERVICE_INVALID || result == ConnectionResult.SERVICE_DISABLED || result == ConnectionResult.SERVICE_MISSING) {
            Events.raiseEvent(Events.INFO, "HMS Not Available" + result);
            Tracing.base.setApiError(result);
            return false;
        } else {
            throw new Exception("HMS not available " +result);
        }
    }

    public ListenableFuture<Void> start() {
        return FluentFuture.from(isEnabled())
                .transformAsync(enabled -> {
                    if (!enabled) {
                        return Futures.immediateVoidFuture();
                    }
                    Events.raiseEvent(Events.INFO, "stopping exposure tracing...");

                    return FluentFuture.from(TaskToFutureAdapter.getFutureWithTimeout(
                            new NfTask(mContactShieldEngine.startContactShield(ContactShieldSetting.DEFAULT)),
                            Duration.ofSeconds(Config.API_TIMEOUT).toMillis(),
                            TimeUnit.MILLISECONDS,
                            this.context,
                            AppExecutors.getScheduledExecutor()))
                            .transformAsync(stat -> Futures.immediateVoidFuture(), AppExecutors.getScheduledExecutor());
                }, AppExecutors.getScheduledExecutor());   }

    public ListenableFuture<Void> stop() {
        return FluentFuture.from(isEnabled())
                .transformAsync(enabled -> {
                    if (!enabled) {
                        return Futures.immediateVoidFuture();
                    }
                    Events.raiseEvent(Events.INFO, "stopping exposure tracing...");

                    return FluentFuture.from(TaskToFutureAdapter.getFutureWithTimeout(
                            new NfTask(mContactShieldEngine.stopContactShield()),
                            Duration.ofSeconds(Config.API_TIMEOUT).toMillis(),
                            TimeUnit.MILLISECONDS,
                            this.context,
                            AppExecutors.getScheduledExecutor()))
                            .transformAsync(stat -> Futures.immediateVoidFuture(), AppExecutors.getScheduledExecutor());
                }, AppExecutors.getScheduledExecutor());
    }

    public ListenableFuture<Boolean> isEnabled() {
        return FluentFuture.from(TaskToFutureAdapter.getFutureWithTimeout(
                new NfTask(mContactShieldEngine.isContactShieldRunning()),
                Duration.ofSeconds(Config.API_TIMEOUT).toMillis(),
                TimeUnit.MILLISECONDS,
                this.context,
                AppExecutors.getScheduledExecutor()));
    }

    public NfTask<Void> provideDiagnosisKeys(List<File> files, String token, ExposureConfig config) {
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

        Events.raiseEvent(Events.INFO, "processing diagnosis keys with: " + configuration);
        PendingIntent pendingIntent = PendingIntent.getService(this.context, 0,
                new Intent(this.context, BackgroundContackShieldIntentService.class), PendingIntent.FLAG_UPDATE_CURRENT);
        DiagnosisConfiguration diagnosisConfiguration = new DiagnosisConfiguration.Builder()
                .setMinimumRiskValueThreshold(configuration.getMinimumRiskScore())
                .setAttenuationRiskValues(configuration.getAttenuationScores())
                .setDaysAfterContactedRiskValues(configuration.getDaysSinceLastExposureScores())
                .setDurationRiskValues(configuration.getDurationScores())
                .setInitialRiskLevelRiskValues(configuration.getTransmissionRiskScores())
                .setAttenuationDurationThresholds(configuration.getDurationAtAttenuationThresholds())
                .build();
        return new NfTask(mContactShieldEngine.putSharedKeyFiles(pendingIntent, files, diagnosisConfiguration, token));
    }

    public ListenableFuture<List<TemporaryExposureKey>> getTemporaryExposureKeyHistory() {
        return FluentFuture.from(TaskToFutureAdapter.getFutureWithTimeout(
                new NfTask(mContactShieldEngine.getPeriodicKey()),
                Duration.ofSeconds(Config.API_TIMEOUT).toMillis(),
                TimeUnit.MILLISECONDS,
                this.context,
                AppExecutors.getScheduledExecutor()))
                .transformAsync(keyList -> Futures.immediateFuture(convertTemporaryExposureKeyList((List<PeriodicKey>)keyList)), AppExecutors.getBackgroundExecutor());
    }

    @Override
    public NfTask<Void> provideDiagnosisKeys(List<File> files) {
        return null;
    }

    public ListenableFuture<ExposureSummary> getExposureSummary(String token) {
        return FluentFuture.from(TaskToFutureAdapter.getFutureWithTimeout(
                new NfTask(mContactShieldEngine.getContactSketch(token)),
                Duration.ofSeconds(Config.API_TIMEOUT).toMillis(),
                TimeUnit.MILLISECONDS,
                this.context,
                AppExecutors.getScheduledExecutor()))
                .transformAsync(summary -> Futures.immediateFuture(convertExposureSummary((ContactSketch)summary)), AppExecutors.getBackgroundExecutor());
    }

    @Override
    public NfTask<List<DailySummary>> getDailySummaries(ExposureConfig config) {
        return null;
    }

    @Override
    public void setDiagnosisKeysDataMapping(ExposureConfig config) {

    }

    @Override
    public boolean deviceSupportsLocationlessScanning() {
        return false;
    }

    @Override
    public NfTask<List<ExposureWindow>> getExposureWindows() {
        return null;
    }

    @Override
    public ListenableFuture<Long> getDeviceENSVersion() {
        return TaskToFutureAdapter.getFutureWithTimeout(
                new NfTask(mContactShieldEngine.getContactShieldVersion()),
                Duration.ofSeconds(Config.API_TIMEOUT).toMillis(),
                TimeUnit.MILLISECONDS,
                this.context,
                AppExecutors.getScheduledExecutor());
    }

    private static List<TemporaryExposureKey> convertTemporaryExposureKeyList(List<PeriodicKey> periodicKeyList) {
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

    private static ExposureSummary convertExposureSummary(ContactSketch contactSketch) {
        return new ExposureSummary.ExposureSummaryBuilder()
                .setDaysSinceLastExposure(contactSketch.getDaysSinceLastHit())
                .setMatchedKeyCount(contactSketch.getNumberOfHits())
                .setMaximumRiskScore(contactSketch.getMaxRiskValue())
                .setSummationRiskScore(contactSketch.getSummationRiskValue())
                .setAttenuationDurations(contactSketch.getAttenuationDurations())
                .build();
    }

}