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
        val thresholds =  Thresholds(thresholdsWeightings.toIntArray(), config.timeThreshold, config.numFilesAndroid) // FIXME check numFiles config

        // FIXME numFiles should be used to retrieve the diagnosis file
        var mostRecentDay: DailySummary? = null

        for (day in dailySummaries) {
            if ((day.summaryData.weightedDurationSum / 60.0) <= 30 ) { continue }

            if (mostRecentDay == null || mostRecentDay.daysSinceEpoch < day.daysSinceEpoch) {
                mostRecentDay = day
            }
        }

        if(mostRecentDay == null) {
            val err = Exception("V2 - No daily summary meeting duration detected")
            Events.raiseError("V2 - No daily summary meeting duration detected", err)
            throw err
        }

        val windowDataList = extractExposureWindowData(windows, config.attenuationLevelValues.toList(), thresholds)

//     FIXME, should do something like  constructSummaryInfo(mostRecentDay, windowDataList)

//        extractExposureWindowData(summary, attenuationRanges, thresholds) { result in
//                switch result {
//            case let .success(windows):
//            completion(.success(constructSummaryInfo(mostRecent, windows)))
//            case let .failure(error):
//            completion(.failure(error))
//        }
//        }

        return false //FIXME real impl
    }

    private fun buildScanData(scanInstances: List<ScanInstance>, attenuations: List<Int>, thresholds: Thresholds): ScanData {
        val data: ScanData = ScanData(mutableListOf(0, 0, 0, 0), false)

        scanInstances.forEach { scan ->
            attenuations.forEachIndexed{ index, attenuation ->
                if( scan.typicalAttenuationDb <= attenuation) {
                    data.buckets[index] += scan.secondsSinceLastScan
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

    private fun extractExposureWindowData(windows: List<ExposureWindow>, attenuations: List<Int>, thresholds: Thresholds): List<WindowData> {
        val items: MutableMap<Long, WindowData> = mutableMapOf()

        windows.forEach{ window ->
                val scan = buildScanData(window.scanInstances, attenuations, thresholds);
                val item: WindowData = items[window.dateMillisSinceEpoch] ?: createWindowDataAndUpdateItems(window, items)
                scan.buckets.forEachIndexed { index, element ->
                    item.cumulativeScans.buckets[index] += element
                }
                item.contiguousScans.add(scan)
        }

        return items.values.toList()
    }

    private fun constructSummaryInfo(day: DailySummary.ExposureSummaryData, windows: ExposureWindow) {
//        private static func constructSummaryInfo(_ summary: ENExposureDetectionSummary) -> ExposureProcessor.ExposureInfo {
//
//            var info = ExposureProcessor.ExposureInfo(daysSinceLastExposure: summary.daysSinceLastExposure, attenuationDurations: self.convertDurations(summary.attenuationDurations), matchedKeyCount: Int(summary.matchedKeyCount),  maxRiskScore: Int(summary.maximumRiskScore), exposureDate: Date())
//
//            if let meta = summary.metadata {
//                info.maximumRiskScoreFullRange = meta["maximumRiskScoreFullRange"] as? Int
//                info.riskScoreSumFullRange = meta["riskScoreSumFullRange"] as? Int
//                info.customAttenuationDurations = self.convertDurations(meta["attenuationDurations"] as? [NSNumber])
//            }
//
//            os_log("Exposure detected", log: OSLog.checkExposure, type: .debug)
//
//            return info
//        }
    }
}