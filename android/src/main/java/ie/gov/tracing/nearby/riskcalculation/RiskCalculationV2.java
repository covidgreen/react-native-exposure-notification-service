package ie.gov.tracing.nearby.riskcalculation;

import android.content.Context;
import android.os.Build;
import android.view.Window;

import androidx.annotation.RequiresApi;

import com.google.android.gms.nearby.exposurenotification.CalibrationConfidence;
import com.google.android.gms.nearby.exposurenotification.DailySummary;
import com.google.android.gms.nearby.exposurenotification.ExposureWindow;
import com.google.android.gms.nearby.exposurenotification.Infectiousness;
import com.google.android.gms.nearby.exposurenotification.ReportType;
import com.google.android.gms.nearby.exposurenotification.ScanInstance;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.jetbrains.annotations.NotNull;

import ie.gov.tracing.common.AppExecutors;
import ie.gov.tracing.common.Events;
import ie.gov.tracing.common.ExposureConfig;
import ie.gov.tracing.common.TaskToFutureAdapter;
import ie.gov.tracing.nearby.ExposureNotificationClientWrapper;
import ie.gov.tracing.storage.ExposureEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static ie.gov.tracing.nearby.ProvideDiagnosisKeysWorker.DEFAULT_API_TIMEOUT;

public class RiskCalculationV2 implements RiskCalculation {

    ExposureConfig ensConfig;

    public RiskCalculationV2(ExposureConfig config) {
            ensConfig = config;
        }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @NotNull
    private ExposureEntity buildSimulatedExposureEntity(int simulateDays) {

        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        long todaySinceEpoch = LocalDate.now().toEpochDay();
        long daysSinceExposure = simulateDays;
        today.add(Calendar.DATE, 0 - new Long(daysSinceExposure).intValue());

        int[] dummyScans = {30, 30, 15, 5};
        ScanData s = new ScanData(dummyScans, dummyScans, true, 1);
        WindowData w = new WindowData(today.getTimeInMillis(), 1, 1, 1, s);
        ExposureEntity entity = new ExposureEntity(new Long(daysSinceExposure).intValue(), -1, 100, 100, "30,30,15,5", today.getTimeInMillis());
        List<WindowData> windows = new ArrayList<>();
        windows.add(w);
        entity.setWindows(windows);

        return entity;
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private List<WindowData> extractExposureWindows(List<ExposureWindow> windows, Long daysSinceEpoch, ExposureConfig config) {
        List<ExposureWindow> matchWindows = windows;

        if (!config.getContiguousMode()) {
            matchWindows = new ArrayList<>();

            for (int i = 0; i < windows.size(); i++) {
                ExposureWindow window = windows.get(i);
                long millis = window.getDateMillisSinceEpoch();
                long days = millis / (1000*60*60*24);
                if (days == daysSinceEpoch) {
                    matchWindows.add(window);
                }
            }
        }

        List<WindowData> windowList = new ArrayList<>();

        for (int i = 0; i < matchWindows.size(); i++) {
            ExposureWindow window = matchWindows.get(i);
            ScanData scan = buildScanData(config, window.getScanInstances());
            WindowData item = new WindowData(window.getDateMillisSinceEpoch(), window.getCalibrationConfidence(), window.getReportType(), window.getInfectiousness(), scan);

            windowList.add(item);
        }

        return windowList;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private ExposureEntity constructSummaryInfo(DailySummary summary, List<WindowData> windows) {
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        long todaySinceEpoch = LocalDate.now().toEpochDay();
        long daysSinceExposure =  todaySinceEpoch - summary.getDaysSinceEpoch();
        today.add(Calendar.DATE, 0 - new Long(daysSinceExposure).intValue());

        List<WindowData> filteredWindows = new ArrayList<>();
        for (int i = 0; i < windows.size(); i++) {
            long dayVal = Instant.ofEpochMilli(windows.get(i).getDate()).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay();
            if (dayVal == summary.getDaysSinceEpoch()) {
                filteredWindows.add(windows.get(i));
            }
        }
        ScanData summedDurations = sumDurations(filteredWindows);

        // store field as a string (otherwise we'd need a new table)
        String attenuationDurations = "";
        if (summedDurations.getWeightedBuckets().length > 0) {
            attenuationDurations = Integer.toString(summedDurations.getWeightedBuckets()[0]);
            for (int i = 1; i < summedDurations.getWeightedBuckets().length; i++) {
                attenuationDurations += "," + summedDurations.getWeightedBuckets()[i];
            }
        }
        ExposureEntity entity = new ExposureEntity(new Long(daysSinceExposure).intValue(), -1, new Double(summary.getSummaryData().getMaximumScore()).intValue(), new Double(summary.getSummaryData().getScoreSum()).intValue(), attenuationDurations, today.getTimeInMillis());
        entity.setWindows(filteredWindows);

        return entity;
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private ScanData sumDurations(List<WindowData> windows) {
        ScanData scanData = new ScanData();

        windows.forEach(window -> {
             for (int i = 0; i < scanData.getWeightedBuckets().length; i++) {
                 scanData.getBuckets()[i] += window.getScanData().getBuckets()[i];
                 scanData.getWeightedBuckets()[i] += window.getScanData().getWeightedBuckets()[i];
             }
        });
        scanData.setNumScans(windows.size());
        return scanData;
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private ScanData buildScanData(ExposureConfig config, List<ScanInstance> scanData) {
        ScanData scanItem = new ScanData();
        scanItem.setNumScans(scanData.size());
        double[] thresholdWeightings = new double[]{config.getImmediateDurationWeight(), config.getNearDurationWeight(), config.getMediumDurationWeight(), config.getOtherDurationWeight()};

        scanData.forEach(scan -> {
            Boolean added = false;
            for (int i = 0; i < config.getAttenuationDurationThresholds().length; i++) {
                if (scan.getTypicalAttenuationDb() <= config.getAttenuationDurationThresholds()[i]) {
                    scanItem.getWeightedBuckets()[i] += scan.getSecondsSinceLastScan() / 60 * thresholdWeightings[i] / 100.0;
                    scanItem.getBuckets()[i] += scan.getSecondsSinceLastScan() / 60;
                    added = true;
                    break;
                }
            }
            if (!added) {
                int lastBucket = scanItem.getBuckets().length - 1;
                scanItem.getWeightedBuckets()[lastBucket] += scan.getSecondsSinceLastScan() / 60 * thresholdWeightings[lastBucket] / 100.0;
                scanItem.getBuckets()[lastBucket] += scan.getSecondsSinceLastScan() / 60;
            }
        });

        int totalTime = 0;
        for (int i = 0; i < scanItem.getWeightedBuckets().length; i++) {
            totalTime += scanItem.getWeightedBuckets()[i];
        }
        if (totalTime >= config.getTimeThreshold()) {
            scanItem.setExceedsThresholds(true);
        }
        return scanItem;
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private List<WindowData> filterForExceededWindows(List<WindowData> windows) {

        List<WindowData> filtered = new ArrayList<>();

        for (int i = 0; i < windows.size(); i++) {
            if (windows.get(i).getScanData().getExceedsThresholds()) {
                filtered.add(windows.get(i));
            }
        }
        filtered.sort((o1, o2) -> {
            if (o1.getDate() < o2.getDate()) {
                return 1;
            } else if (o1.getDate() > o2.getDate()) {
                return -1;
            }
            return 0;
        });

        return filtered;
    }

    private DailySummary findDay(List<DailySummary> summaries, long day) {

        for (int i = 0; i < summaries.size(); i++) {
            if (summaries.get(i).getDaysSinceEpoch() == day) {
                return summaries.get(i);
            }
        }
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private ExposureEntity buildExposureEntity(List<DailySummary> dailySummaries, List<ExposureWindow> exposureWindows, ExposureConfig config) {

        dailySummaries.sort((o1, o2) -> {
            if (o1.getDaysSinceEpoch() < o2.getDaysSinceEpoch()) {
                return 1;
            } else if (o1.getDaysSinceEpoch() > o2.getDaysSinceEpoch()) {
                return -1;
            }
            return 0;
        });

        List<DailySummary> valid = new ArrayList<>();
        for (int i = 0; i < dailySummaries.size(); i++) {
            if (dailySummaries.get(i).getSummaryData().getMaximumScore() >= config.getMinimumRiskScoreFullRange()) {
                valid.add(dailySummaries.get(i));
            }
        }

        if (valid.size() == 0) {
            Events.raiseEvent(Events.INFO, "V2 - No valid daily summaries");
            return null;
        }

        long matchDay = valid.get(0).getDaysSinceEpoch();

        List<WindowData> windowItems = extractExposureWindows(exposureWindows, matchDay, config);

        if (config.getContiguousMode()) {
            List<WindowData> exceeded = filterForExceededWindows(windowItems);
            if (exceeded.size() > 0) {
                long dayVal = Instant.ofEpochMilli(exceeded.get(0).getDate()).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay();

                DailySummary day = findDay(valid, dayVal);
                if (day != null) {
                    return constructSummaryInfo(day, windowItems);
                } else {
                    Events.raiseEvent(Events.INFO, "V2 - Unable to find day to match window");
                    return null;
                }
            } else {
                Events.raiseEvent(Events.INFO, "V2 - Running in Contiguous mode, no contiguos match");
                return null;
            }
        } else {
            return constructSummaryInfo(valid.get(0), windowItems);
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    public ListenableFuture<ExposureEntity> processKeys(Context context, Boolean simulate, Integer simulateDays) {

        return FluentFuture.from(TaskToFutureAdapter.getFutureWithTimeout(
                    ExposureNotificationClientWrapper.get(context).getDailySummaries(this.ensConfig),
                    DEFAULT_API_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS,
                    AppExecutors.getScheduledExecutor()))
                .transformAsync(dailySummaries -> {
                    return FluentFuture.from(TaskToFutureAdapter.getFutureWithTimeout(
                        ExposureNotificationClientWrapper.get(context).getExposureWindows(),
                        DEFAULT_API_TIMEOUT.toMillis(),
                        TimeUnit.MILLISECONDS,
                        AppExecutors.getScheduledExecutor()))
                    .transformAsync(exposureWindows -> {
                        if (simulate) {
                            ExposureEntity exposureEntity = buildSimulatedExposureEntity(simulateDays);
                            return Futures.immediateFuture(exposureEntity);
                        }

                        if (exposureWindows == null) {
                            Events.raiseEvent(Events.INFO, "exposureWindows - no exposure windows.");
                            return Futures.immediateFuture(null);
                        }

                        if (exposureWindows.size() == 0) {
                            // No matches so we show no notification and just delete the token.
                            Events.raiseEvent(Events.INFO, "exposureSummary - empty exposure windows.");
                            return Futures.immediateFuture(null);
                        }

                        if (dailySummaries == null) {
                            Events.raiseEvent(Events.INFO, "exposureWindows - no dailySummaries");
                            return Futures.immediateFuture(null);
                        }
                        if (dailySummaries.size() == 0) {
                            Events.raiseEvent(Events.INFO, "exposureWindows - empty dailySummaries");
                            return Futures.immediateFuture(null);
                        }

                        ExposureEntity exposureEntity = buildExposureEntity(dailySummaries, exposureWindows, ensConfig);

                        return Futures.immediateFuture(exposureEntity);
                    }, AppExecutors.getBackgroundExecutor());
                }, AppExecutors.getBackgroundExecutor());
    }
}
