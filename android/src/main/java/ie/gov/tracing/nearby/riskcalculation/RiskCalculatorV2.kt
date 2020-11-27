package ie.gov.tracing.nearby.riskcalculation

import android.content.Context
import com.google.android.gms.nearby.exposurenotification.DailySummary
import com.google.android.gms.nearby.exposurenotification.ExposureWindow
import com.google.android.gms.nearby.exposurenotification.ScanInstance
import ie.gov.tracing.common.Events
import ie.gov.tracing.common.ExposureConfig

object RiskCalculatorV2 {
    fun calculateRisk(windows: List<ExposureWindow>, dailySummaries: List<DailySummary>, config: ExposureConfig, context: Context): Boolean {
        // we use these when we receive match broadcasts from exposure API
        val thresholdsWeightings = config.thresholdWeightings!!.map{it.toInt()}
        val thresholds =  Thresholds(thresholdsWeightings, config.timeThreshold, config.numFilesAndroid, config.contiguousMode) // FIXME check numFiles config

        if(dailySummaries.isEmpty()) {
            Events.raiseEvent(Events.INFO, "V2 - No daily summaries returned, no exposures matched")
            return false
        }

        val aboveThresholdDays = dailySummaries.filter{
            (it.summaryData.weightedDurationSum / 60.0).toInt() > thresholds.timeThreshold
        }.sortedBy { it.daysSinceEpoch }

        if(aboveThresholdDays.isEmpty()) {
            Events.raiseEvent(Events.INFO, "V2 - No daily summary meeting duration threshold")
            return false
        }

        val mostRecent = aboveThresholdDays.first()

        try {
            val windowsData = extractExposureWindowData(windows, config, thresholds, mostRecent.daysSinceEpoch)

            /*if (thresholds.contiguousMode) {
                if (windowsData.filter{ it.scanData.exceedsThreshold }.count > 0) {
                    return constructSummaryInfo(mostRecent, windowsData) // FIXME showNotification ?
                } else {
                    Events.raiseEvent(Events.INFO, "V2 - Running in Contiguous mode, no contiguous match");
                    return false;
                }
            }*/

            return constructSummaryInfo(mostRecent, windowsData)

        } catch (error: Exception) {
            Events.raiseError("Error while extracting window data", error)
            return false
        }

    }

    private fun buildScanData(scanInstances: List<ScanInstance>, config: ExposureConfig, thresholds: Thresholds): ScanData {
        val data: ScanData = ScanData(mutableListOf(0, 0, 0, 0), false)
        val thresholdWeightings = listOf(config.immediateDurationWeight, config.nearDurationWeight, config.mediumDurationWeight, config.otherDurationWeight)

        scanInstances.forEach { scan ->
            config.attenuationDurationThresholds.forEachIndexed{ index, attenuation ->
                if( scan.typicalAttenuationDb <= attenuation) {
                    data.buckets[index] += scan.secondsSinceLastScan / 60 * (thresholdWeightings[index] / 100.0).toInt()
                    return@forEach
                }
            }

        }

        var contactTime = 0
        data.buckets.forEachIndexed { index, bucket ->
            contactTime += bucket * thresholds.thresholdWeightings[index]
        }

        if (contactTime >= thresholds.timeThreshold) {
            data.exceedsThresholds = true
        }

        return data
    }

    private fun createWindowDataAndUpdateItems(window: ExposureWindow, items: MutableMap<Long, WindowData>): WindowData {
        val newItem = createWindowData(window)
        items[window.dateMillisSinceEpoch] = newItem
        return newItem
    }

    private fun extractExposureWindowData(windows: List<ExposureWindow>, config: ExposureConfig, thresholds: Thresholds, daysSinceEpoch: Int): List<WindowData> {
        val items: MutableMap<Long, WindowData> = mutableMapOf()

        val  matchWindows = windows.filter{ Math.floor(it.dateMillisSinceEpoch.toDouble() / 24*60*60*1000).toInt() == daysSinceEpoch}

        matchWindows.forEach{ window ->
                val scan = buildScanData(window.scanInstances, config, thresholds);
                val item: WindowData = items[window.dateMillisSinceEpoch] ?: createWindowDataAndUpdateItems(window, items)
                scan.buckets.forEachIndexed { index, element ->
                    item.cumulativeScans.buckets[index] += element
                }
                item.contiguousScans.add(scan)
        }

        return items.values.toList()
    }

    private fun constructSummaryInfo(summary: DailySummary, windows: List<WindowData>): Boolean {

        // FIXME impl
        return false // tru => notification , false => nope
//        var info = ExposureProcessor.ExposureInfo(daysSinceLastExposure: summary.daysSinceLastExposure, attenuationDurations: self.convertDurations(summary.attenuationDurations), matchedKeyCount: Int(summary.matchedKeyCount),  maxRiskScore: Int(summary.maximumRiskScore), exposureDate: Date())
        //        let calendar = Calendar.current
        //        let components = calendar.dateComponents([.day], from: day.date, to: Date())
        //
        //        let summedDurations = sumDurations(windows)
        //        var info = ExposureProcessor.ExposureInfo(daysSinceLastExposure: components.day!, attenuationDurations: summedDurations.buckets, matchedKeyCount: -1,  maxRiskScore: Int(day.daySummary.maximumScore), exposureDate: Date())
        //
        //        info.customAttenuationDurations = summedDurations.buckets
        //        info.riskScoreSumFullRange = Int(day.daySummary.scoreSum)
        //        info.windows = windows
        //
        //        os_log("Exposure detected", log: OSLog.checkExposure, type: .debug)
        //
        //        return info
    }
}