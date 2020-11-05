import Foundation
import ExposureNotification

@available(iOS 13.7, *)
class RiskCalculationV2 {
    
    public static func calculateRisk(_ summary: ENExposureDetectionSummary) -> ExposureProcessor.ExposureInfo?
    {
        guard summary.daySummaries.count > 0 else {
          os_log("No daily summaries, no exposures detected", log: OSLog.checkExposure, type: .debug)
          return nil
        }
        var mostRecentDay: ENExposureDaySummary?
        
        for day in summary.daySummaries {
            if (day.daySummary.weightedDurationSum / 60.0) > 30 {
                if mostRecentDay == nil {
                    mostRecentDay = day
                } else if mostRecentDay!.date < day.date {
                    mostRecentDay = day
                }
            }
        }
        
        guard let mostRecent = mostRecentDay else {
            os_log("No daily summary with meeting duration detected", log: OSLog.checkExposure, type: .debug)
            return nil
        }
        /*if #available(iOS 13.7, *) {
            ExposureManager.shared.manager.getExposureWindows(summary: summaryData) { exposureWindows, error in
          
                if let error = error {
                    return self.finishProcessing(.failure(self.wrapError("Failure in getExposureWindows", error)))
                }
          
                guard let windows = exposureWindows else {
                    return self.finishProcessing(.success((nil, lastIndex, thresholds)))
                }
          
                let data1: [String] = windows.map { window in
                 os_log("Some settings value, confidence: %d, date: %d, reportType: %d, infectiousness: %d", log: OSLog.checkExposure, type: .debug, window.calibrationConfidence.rawValue, window.date as CVarArg,
                     window.diagnosisReportType.rawValue, window.infectiousness.rawValue)
                    window.scanInstances.map { scan in
                     os_log("Some scan data, minAttenuation: %d, secondsSinceLastScan: %d, typicalAttenuation: %d", log: OSLog.checkExposure, type: .debug, scan.minimumAttenuation, scan.secondsSinceLastScan,
                            scan.typicalAttenuation)
                  
                    }
                    return "test"
                }
                return self.finishProcessing(.success((info, lastIndex, thresholds)))
            }
        } else {
             return self.finishProcessing(.success((info, lastIndex, thresholds)))
        }*/

        return constructSummaryInfo(mostRecent)
    }

    private static func constructSummaryInfo(_ day: ENExposureDaySummary) -> ExposureProcessor.ExposureInfo {
        let calendar = Calendar.current
        let components = calendar.dateComponents([.day], from: day.date, to: Date())
        
        var info = ExposureProcessor.ExposureInfo(daysSinceLastExposure: components.day!, attenuationDurations: [], matchedKeyCount: 1,  maxRiskScore: Int(day.daySummary.maximumScore), exposureDate: Date())
        
        info.customAttenuationDurations = []
        info.riskScoreSumFullRange = Int(day.daySummary.scoreSum)

        os_log("Exposure detected", log: OSLog.checkExposure, type: .debug)
        
        return info
    }
}
