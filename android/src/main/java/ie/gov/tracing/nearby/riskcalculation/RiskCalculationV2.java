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

    @NotNull
    private List<ExposureWindow> getSimulatedExposureWindows() {

        ArrayList<ExposureWindow> exposureWindows = new ArrayList<ExposureWindow>();

        int defaultAttenuationDb = 30;

        int[] infectiousnessTypes = {Infectiousness.STANDARD, Infectiousness.HIGH};

        int[] reportTypes = {ReportType.CONFIRMED_TEST, ReportType.CONFIRMED_CLINICAL_DIAGNOSIS, ReportType.SELF_REPORT};
        int[] calibrationConfidenceTypes = {CalibrationConfidence.LOWEST, CalibrationConfidence.LOW, CalibrationConfidence.MEDIUM, CalibrationConfidence.HIGH};

        int maxMins = 5;
        int varyDb = 4;

        for (int i = 0; i < 5; i++) {

            ExposureWindow.Builder exposureWindowBuilder = new ExposureWindow.Builder();

            ArrayList<ScanInstance> scanInstances = new ArrayList<ScanInstance>();

            for (int k = 0; k < 15; k++) {
                ScanInstance.Builder scanInstanceBuilder = new ScanInstance.Builder();

                int secondsSinceLastScan = Math.max(k % maxMins, 1) * 60;
                int minAttenuationDb = defaultAttenuationDb;
                int typicalAttenuationDb = defaultAttenuationDb + (i % varyDb);

                scanInstanceBuilder.setMinAttenuationDb(minAttenuationDb);
                scanInstanceBuilder.setSecondsSinceLastScan(secondsSinceLastScan);
                scanInstanceBuilder.setTypicalAttenuationDb(typicalAttenuationDb);

                scanInstances.add(scanInstanceBuilder.build());
            }

            exposureWindowBuilder.setScanInstances(scanInstances);

            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DATE, i * -1);
            long msSinceEpoch = cal.getTimeInMillis();
            exposureWindowBuilder.setDateMillisSinceEpoch(msSinceEpoch);

            int calibrationConfidence = calibrationConfidenceTypes[i % calibrationConfidenceTypes.length];
            exposureWindowBuilder.setCalibrationConfidence(calibrationConfidence);

            int infectiousness = infectiousnessTypes[i % infectiousnessTypes.length];
            exposureWindowBuilder.setInfectiousness(infectiousness);

            int reportType = reportTypes[i % reportTypes.length];
            exposureWindowBuilder.setReportType(reportType);

            exposureWindows.add(exposureWindowBuilder.build());

        }

        return exposureWindows;
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private List<WindowData> extractExposureWindows(List<ExposureWindow> windows, Long daysSinceEpoch, ExposureConfig config) {
        List<ExposureWindow> matchWindows = windows;

        if (daysSinceEpoch != -1) {
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
        if (summedDurations.getBuckets().length > 0) {
            attenuationDurations = Integer.toString(summedDurations.getBuckets()[0]);
            for (int i = 1; i < summedDurations.getBuckets().length; i++) {
                attenuationDurations += "," + summedDurations.getBuckets()[i];
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
             for (int i = 0; i < scanData.getBuckets().length; i++) {
                 scanData.getBuckets()[i] += window.getScanData().getBuckets()[i];
             }
        });

        return scanData;
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private ScanData buildScanData(ExposureConfig config, List<ScanInstance> scanData) {
        ScanData scanItem = new ScanData();

        double[] thresholdWeightings = new double[]{config.getImmediateDurationWeight(), config.getNearDurationWeight(), config.getMediumDurationWeight(), config.getOtherDurationWeight()};

        scanData.forEach(scan -> {
            for (int i = 0; i < config.getAttenuationDurationThresholds().length; i++) {
                if (scan.getTypicalAttenuationDb() <= config.getAttenuationDurationThresholds()[i]) {
                    scanItem.getBuckets()[i] += scan.getSecondsSinceLastScan() / 60 * thresholdWeightings[i] / 100.0;
                }
            }
        });

        int totalTime = 0;
        for (int i = 0; i < scanItem.getBuckets().length; i++) {
            totalTime += scanItem.getBuckets()[i];
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

        List<DailySummary> validDays = new ArrayList<>();
        long matchDay = -1;

        if (!config.getContiguousMode()) {
            for (int i = 0; i < dailySummaries.size(); i++) {
                DailySummary summary = dailySummaries.get(i);
                if (summary.getSummaryData().getWeightedDurationSum() / 60.0 >= ensConfig.getTimeThreshold()) {
                    validDays.add(summary);
                }
            };
            if (validDays.size() == 0) {
                Events.raiseEvent(Events.INFO, "exposureWindows - no dailySummaries that meet the threshold criteria");
                return null;
            }

        } else {
            validDays = dailySummaries;
        }
        validDays.sort((o1, o2) -> {
            if (o1.getDaysSinceEpoch() < o2.getDaysSinceEpoch()) {
                return 1;
            } else if (o1.getDaysSinceEpoch() > o2.getDaysSinceEpoch()) {
                return -1;
            }
            return 0;
        });

        if (!config.getContiguousMode()) {
            matchDay = validDays.get(0).getDaysSinceEpoch();
        }

        List<WindowData> windowItems = extractExposureWindows(exposureWindows, matchDay, config);

        if (config.getContiguousMode()) {
            List<WindowData> exceeded = filterForExceededWindows(windowItems);
            if (exceeded.size() > 0) {
                long dayVal = Instant.ofEpochMilli(exceeded.get(0).getDate()).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay();

                DailySummary day = findDay(dailySummaries,dayVal);
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
            return constructSummaryInfo(validDays.get(0), windowItems);
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    public ListenableFuture<ExposureEntity>  processKeys(Context context, Boolean simulate, Integer simulateDays) {

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
                            exposureWindows = getSimulatedExposureWindows();
                        }

                        if (exposureWindows == null) {
                            Events.raiseEvent(Events.INFO, "exposureWindows - no exposure windows.");
                            return Futures.immediateFailedFuture(new NoExposureWindows());
                        }

                        if (exposureWindows.size() == 0) {
                            // No matches so we show no notification and just delete the token.
                            Events.raiseEvent(Events.INFO, "exposureSummary - empty exposure windows.");
                            return Futures.immediateFailedFuture(new EmptyExposureWindows());
                        }

                        if (dailySummaries == null) {
                            Events.raiseEvent(Events.INFO, "exposureWindows - no dailySummaries");
                            return Futures.immediateFailedFuture(new NoDailySummaries());
                        }

                        ExposureEntity exposureEntity = buildExposureEntity(dailySummaries, exposureWindows, ensConfig);

                        return Futures.immediateFuture(exposureEntity);
                    }, AppExecutors.getBackgroundExecutor());
                }, AppExecutors.getBackgroundExecutor());
    }


    private static class NoExposureWindows extends Exception {}
    private static class EmptyExposureWindows extends Exception {}
    private static class NoDailySummaries extends Exception {}
}
