package ie.gov.tracing.nearby.riskcalculation;

import android.content.Context;

import com.google.android.gms.nearby.exposurenotification.ExposureSummary;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import ie.gov.tracing.common.AppExecutors;
import ie.gov.tracing.common.Events;
import ie.gov.tracing.common.TaskToFutureAdapter;
import ie.gov.tracing.nearby.ExposureNotificationClientWrapper;
import ie.gov.tracing.network.Fetcher;
import ie.gov.tracing.storage.ExposureEntity;
import ie.gov.tracing.storage.ExposureNotificationRepository;
import ie.gov.tracing.storage.SharedPrefs;
import static ie.gov.tracing.nearby.ProvideDiagnosisKeysWorker.DEFAULT_API_TIMEOUT;

public class RiskCalculationV1 implements RiskCalculation {
    private final ExposureNotificationRepository repository;
    private final String ensToken;

    public RiskCalculationV1(ExposureNotificationRepository reposit, String token) {
        repository = reposit;
        ensToken = token;
    }

    private static double[] doubleArrayFromString(String string) {
        try {
            String[] strings = string.replace("[", "").replace("]", "").split(", ");
            double[] result = new double[strings.length];
            for (int i = 0; i < result.length; i++) {
                result[i] = Double.parseDouble(strings[i]);
            }
            return result;
        } catch (Exception ex) {
            Events.raiseError("Cannot parse double array", ex);
        }
        return null;
    }

    public ListenableFuture<Boolean> processKeys(Context context, Boolean simulate, Integer simulateDays) {
        Events.raiseEvent(Events.INFO, "Running v1 risk checks");
        AtomicReference<Boolean> showNotification = new AtomicReference<>(false);

        return FluentFuture.from(TaskToFutureAdapter.getFutureWithTimeout(
                ExposureNotificationClientWrapper.get(context).getExposureSummary(ensToken),
                DEFAULT_API_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS,
                AppExecutors.getScheduledExecutor()))
                .transformAsync((exposureSummary) -> {
                    Events.raiseEvent(Events.INFO, "StatusUpdatedWorker - checking results" + simulate);
                    if (simulate) {
                        ExposureSummary.ExposureSummaryBuilder builder = new ExposureSummary.ExposureSummaryBuilder();
                        int[] dummyAttenuations = new int[]{30, 30, 30};
                        builder.setAttenuationDurations(dummyAttenuations);
                        builder.setDaysSinceLastExposure(simulateDays);
                        builder.setMatchedKeyCount(1);
                        builder.setMaximumRiskScore(10);
                        builder.setSummationRiskScore(10);
                        exposureSummary = builder.build();
                    }

                    if (exposureSummary == null) {
                        Events.raiseEvent(Events.INFO, "exposureSummary - no exposure summary, deleting token.");
                        return repository.deleteTokenEntityAsync(ensToken);
                    }

                    if (exposureSummary.getMatchedKeyCount() == 0) {
                        // No matches so we show no notification and just delete the token.
                        Events.raiseEvent(Events.INFO, "exposureSummary - no matches, deleting token.");
                        return repository.deleteTokenEntityAsync(ensToken);
                    }

                    if (exposureSummary.getMaximumRiskScore() == 0) {
                        Events.raiseEvent(Events.INFO, "exposureSummary - maximumRiskScore: " +
                                exposureSummary.getMaximumRiskScore() + ", deleting token.");
                        return repository.deleteTokenEntityAsync(ensToken);
                    }

                    Events.raiseEvent(Events.INFO, "exposureSummary - maximumRiskScore: " +
                            exposureSummary.getMaximumRiskScore());

                    int[] ad = exposureSummary.getAttenuationDurationsInMinutes();

                    double[] tw;
                    long timeThreshold;
                    if (simulate) {
                        tw = doubleArrayFromString("[1, 1, 0]");
                        timeThreshold = 15;
                    } else {
                        tw = doubleArrayFromString(SharedPrefs.getString("thresholdWeightings", context));
                        timeThreshold = SharedPrefs.getLong("timeThreshold", context);
                    }

                    if (tw == null || tw.length != 3 || timeThreshold <= 0) {
                        Events.raiseEvent(Events.INFO, "exposureSummary - timeThreshold or " +
                                "weightingThresholds not set or invalid, deleting token and aborting.");
                        return repository.deleteTokenEntityAsync(ensToken);
                    }

                    Events.raiseEvent(Events.INFO, "exposureSummary - Determining if exposure durations: " +
                            Arrays.toString(ad) + ", using " + "thresholdWeightings: " + Arrays.toString(tw) +
                            ", exceeds the timeThreshold: " + timeThreshold);

                    double totalTime = tw[0] * ad[0] + tw[1] * ad[1] + tw[2] * ad[2];

                    if (totalTime < timeThreshold) {
                        Events.raiseEvent(Events.INFO, "exposureSummary - totalTime: " + totalTime +
                                " is less than timeThreshold: " + timeThreshold + ", ignoring and deleting token.");
                        return repository.deleteTokenEntityAsync(ensToken);
                    }
                    showNotification.set(true);
                    Events.raiseEvent(Events.INFO, "exposureSummary - totalTime: " + totalTime +
                            " exceeds timeThreshold: " + timeThreshold + ", recording successful match");

                    // store field as a string (otherwise we'd need a new table)
                    String attenuationDurations = "";
                    if (ad.length > 0) {
                        attenuationDurations = Integer.toString(ad[0]);
                        for (int i = 1; i < ad.length; i++) {
                            attenuationDurations += "," + ad[i];
                        }
                    }

                    List<ExposureEntity> exposureEntities = new ArrayList<>();
                    ExposureEntity exposureEntity = ExposureEntity.create(
                            exposureSummary.getDaysSinceLastExposure(),
                            exposureSummary.getMatchedKeyCount(),
                            exposureSummary.getMaximumRiskScore(),
                            exposureSummary.getSummationRiskScore(),
                            attenuationDurations
                    );
                    exposureEntities.add(exposureEntity);

                    // asynchronously update our summary table while we show notification
                    repository.upsertExposureEntitiesAsync(exposureEntities);

                    Events.raiseEvent(Events.ON_EXPOSURE, "exposureSummary - recording summary matches:"
                            + exposureSummary.getMatchedKeyCount() + ", duration minutes: " + attenuationDurations);

                    HashMap<String, Object> payload = new HashMap<>();

                    payload.put("matchedKeys", exposureSummary.getMatchedKeyCount());
                    payload.put("attenuations", ad);
                    payload.put("maxRiskScore", exposureSummary.getMaximumRiskScore());

                    Fetcher.saveMetric("CONTACT_NOTIFICATION", context, payload);
                    Fetcher.triggerCallback(exposureEntity, context, payload);

                    // finish by marking token as read if we have positive matchCount for token
                    return repository.markTokenEntityRespondedAsync(ensToken);

                }, AppExecutors.getBackgroundExecutor())
                .transformAsync((v) -> {
                    return Futures.immediateFuture(showNotification.get());
                }, AppExecutors.getBackgroundExecutor());
                //.transform((v) -> ListenableWorker.Result.success(), AppExecutors.getLightweightExecutor())
                //.catching(Exception.class, this::processError, AppExecutors.getLightweightExecutor());

    }

}
