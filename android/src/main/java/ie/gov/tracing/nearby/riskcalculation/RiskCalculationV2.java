package ie.gov.tracing.nearby.riskcalculation;

import android.content.Context;
import android.os.Build;
import androidx.annotation.RequiresApi;

import com.google.android.gms.nearby.exposurenotification.CalibrationConfidence;
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

import java.util.ArrayList;
import java.util.Calendar;
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

//        fun calculateRisk(windows: List<ExposureWindow>, dailySummaries: List<DailySummary>, config: ExposureConfig, context: Context): Boolean {
//        // we use these when we receive match broadcasts from exposure API
//        val thresholdsWeightings = config.thresholdWeightings!!.map{it.toInt()}
//        val thresholds =  Thresholds(thresholdsWeightings, config.timeThreshold, config.numFilesAndroid, config.contiguousMode) // FIXME check numFiles config
//
//        if(dailySummaries.isEmpty()) {
//            Events.raiseEvent(Events.INFO, "V2 - No daily summaries returned, no exposures matched")
//            return false
//        }
//
//        val aboveThresholdDays = dailySummaries.filter{
//            (it.summaryData.weightedDurationSum / 60.0).toInt() > thresholds.timeThreshold
//        }.sortedBy { it.daysSinceEpoch }
//
//        if(aboveThresholdDays.isEmpty()) {
//            Events.raiseEvent(Events.INFO, "V2 - No daily summary meeting duration threshold")
//            return false
//        }
//
//        val mostRecent = aboveThresholdDays.first()
//
//        try {
//            val windowsData = extractExposureWindowData(windows, config, thresholds, mostRecent.daysSinceEpoch)
//
//            /*if (thresholds.contiguousMode) {
//                if (windowsData.filter{ it.scanData.exceedsThreshold }.count > 0) {
//                    return constructSummaryInfo(mostRecent, windowsData) // FIXME showNotification ?
//                } else {
//                    Events.raiseEvent(Events.INFO, "V2 - Running in Contiguous mode, no contiguous match");
//                    return false;
//                }
//            }*/
//
//        return constructSummaryInfo(mostRecent, windowsData)
//
//    } catch (error: Exception) {
//        Events.raiseError("Error while extracting window data", error)
//        return false
//    }
//
//}
//
//    private fun buildScanData(scanInstances: List<ScanInstance>, config: ExposureConfig, thresholds: Thresholds): ScanData {
//        val data: ScanData = ScanData(mutableListOf(0, 0, 0, 0), false)
//        val thresholdWeightings = listOf(config.immediateDurationWeight, config.nearDurationWeight, config.mediumDurationWeight, config.otherDurationWeight)
//
//        scanInstances.forEach { scan ->
//        config.attenuationDurationThresholds.forEachIndexed{ index, attenuation ->
//        if( scan.typicalAttenuationDb <= attenuation) {
//        data.buckets[index] += scan.secondsSinceLastScan / 60 * (thresholdWeightings[index] / 100.0).toInt()
//        return@forEach
//                }
//                        }
//
//                        }
//
//                        var contactTime = 0
//                        data.buckets.forEachIndexed { index, bucket ->
//                        contactTime += bucket * thresholds.thresholdWeightings[index]
//                        }
//
//                        if (contactTime >= thresholds.timeThreshold) {
//                        data.exceedsThresholds = true
//                        }
//
//                        return data
//                        }
//
//private fun createWindowDataAndUpdateItems(window: ExposureWindow, items: MutableMap<Long, WindowData>): WindowData {
//        val newItem = createWindowData(window)
//        items[window.dateMillisSinceEpoch] = newItem
//        return newItem
//        }
//
//private fun extractExposureWindowData(windows: List<ExposureWindow>, config: ExposureConfig, thresholds: Thresholds, daysSinceEpoch: Int): List<WindowData> {
//        val items: MutableMap<Long, WindowData> = mutableMapOf()
//
//        val  matchWindows = windows.filter{ Math.floor(it.dateMillisSinceEpoch.toDouble() / 24 * 60 * 60 * 1000).toInt() == daysSinceEpoch}
//
//        matchWindows.forEach{ window ->
//        val scan = buildScanData(window.scanInstances, config, thresholds);
//        val item: WindowData = items[window.dateMillisSinceEpoch] ?: createWindowDataAndUpdateItems(window, items)
//        scan.buckets.forEachIndexed { index, element ->
//        item.cumulativeScans.buckets[index] += element
//        }
//        item.contiguousScans.add(scan)
//        }
//
//        return items.values.toList()
//        }
//
//private fun constructSummaryInfo(summary: DailySummary, windows: List<WindowData>): Boolean {
//
//        // FIXME impl
//        return false // tru => notification , false => nope
////        var info = ExposureProcessor.ExposureInfo(daysSinceLastExposure: summary.daysSinceLastExposure, attenuationDurations: self.convertDurations(summary.attenuationDurations), matchedKeyCount: Int(summary.matchedKeyCount),  maxRiskScore: Int(summary.maximumRiskScore), exposureDate: Date())
//        //        let calendar = Calendar.current
//        //        let components = calendar.dateComponents([.day], from: day.date, to: Date())
//        //
//        //        let summedDurations = sumDurations(windows)
//        //        var info = ExposureProcessor.ExposureInfo(daysSinceLastExposure: components.day!, attenuationDurations: summedDurations.buckets, matchedKeyCount: -1,  maxRiskScore: Int(day.daySummary.maximumScore), exposureDate: Date())
//        //
//        //        info.customAttenuationDurations = summedDurations.buckets
//        //        info.riskScoreSumFullRange = Int(day.daySummary.scoreSum)
//        //        info.windows = windows
//        //
//        //        os_log("Exposure detected", log: OSLog.checkExposure, type: .debug)
//        //
//        //        return info
//        }
//        */
    @RequiresApi(Build.VERSION_CODES.N)
    public ListenableFuture<Boolean>  processKeys(Context context, Boolean simulate, Integer simulateDays) {
        AtomicReference<Boolean> showNotification = new AtomicReference<>(false);

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
                        showNotification.set(true);
                        return Futures.immediateFuture(showNotification.get());
                    }, AppExecutors.getBackgroundExecutor());
                }, AppExecutors.getBackgroundExecutor());
    }

    private static class NoExposureWindows extends Exception {}
    private static class EmptyExposureWindows extends Exception {}
    private static class NoDailySummaries extends Exception {}
}
