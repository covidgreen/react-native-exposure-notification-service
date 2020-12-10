package ie.gov.tracing.nearby.riskcalculation;

import android.content.Context;

import com.google.android.gms.nearby.exposurenotification.ExposureSummary;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Arrays;
import java.util.Calendar;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import ie.gov.tracing.common.AppExecutors;
import ie.gov.tracing.common.Events;
import ie.gov.tracing.common.TaskToFutureAdapter;
import ie.gov.tracing.nearby.ExposureNotificationClientWrapper;
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

    private ExposureSummary buildSimulateSummary(int numDays) {
        int[] dummyAttenuations = new int[]{30, 30, 30};
        return new ExposureSummary.ExposureSummaryBuilder()
            .setAttenuationDurations(dummyAttenuations)
            .setDaysSinceLastExposure(numDays)
            .setMatchedKeyCount(1)
            .setMaximumRiskScore(10)
            .setSummationRiskScore(10)
            .build();
    }

    public ListenableFuture<ExposureEntity> processKeys(Context context, Boolean simulate, Integer simulateDays) {
        Events.raiseEvent(Events.INFO, "Running v1 risk checks");
        AtomicReference<ExposureEntity> exposureEntity = new AtomicReference<>(null);

        return FluentFuture.from(TaskToFutureAdapter.getFutureWithTimeout(
                ExposureNotificationClientWrapper.get(context).getExposureSummary(ensToken),
                DEFAULT_API_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS,
                AppExecutors.getScheduledExecutor()))
                .transformAsync((exposureSummary) -> {
                    Events.raiseEvent(Events.INFO, "StatusUpdatedWorker - checking results, simulate: " + simulate);
                    if (simulate) {
                        exposureSummary = buildSimulateSummary(simulateDays);
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

                    Calendar today = Calendar.getInstance();
                    today.set(Calendar.HOUR_OF_DAY, 0);
                    today.set(Calendar.MINUTE, 0);
                    today.set(Calendar.SECOND, 0);
                    today.add(Calendar.DATE, 0 - exposureSummary.getDaysSinceLastExposure());

                    exposureEntity.set(ExposureEntity.create(
                            exposureSummary.getDaysSinceLastExposure(),
                            exposureSummary.getMatchedKeyCount(),
                            exposureSummary.getMaximumRiskScore(),
                            exposureSummary.getSummationRiskScore(),
                            attenuationDurations, today.getTimeInMillis()
                    ));

                    // finish by marking token as read if we have positive matchCount for token
                    return repository.markTokenEntityRespondedAsync(ensToken);

                }, AppExecutors.getBackgroundExecutor())
                .transformAsync((v) -> {
                    return Futures.immediateFuture(exposureEntity.get());
                }, AppExecutors.getBackgroundExecutor());

    }

}
