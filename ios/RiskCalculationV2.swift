import Foundation
import ExposureNotification

@available(iOS 13.7, *)
class RiskCalculationV2 {
    
    public static func calculateRisk(_ summary: ENExposureDetectionSummary, _ configuration: ENExposureConfiguration, _ thresholds: ExposureCheck.Thresholds, _ completion: @escaping  (Result<(ExposureProcessor.ExposureInfo?), Error>) -> Void)
    {
        guard summary.daySummaries.count > 0 else {
            os_log("V2 - No daily summaries returned, no exposures matched", log: OSLog.checkExposure, type: .info)
            return completion(.success(nil))
        }
        
        let aboveThresholdDays = summary.daySummaries.filter({ Int(($0.daySummary.weightedDurationSum / 60.0)) > thresholds.timeThreshold}).sorted(by: { $0.date > $1.date })
        
        guard let mostRecent = aboveThresholdDays.first else {
            os_log("V2 - No daily summary meeting duration threshold", log: OSLog.checkExposure, type: .info)
            return completion(.success(nil))
        }
        
        extractExposureWindowData(summary, configuration, thresholds, mostRecent.date) { result in
            switch result {
               case let .success(windows):
                if thresholds.contiguousMode {
                    if windows.filter({ $0.scanData.exceedsThreshold }).count > 0 {
                        completion(.success(constructSummaryInfo(mostRecent, windows)))
                    } else {
                        os_log("V2 - Running in Contiguous mode, no contiguos match", log: OSLog.checkExposure, type: .info)

                        completion(.success(nil))
                    }
                } else {
                    completion(.success(constructSummaryInfo(mostRecent, windows)))
                }
               case let .failure(error):
                  completion(.failure(error))
            }
        }
    }
    
    private static func wrapError(_ description: String, _ error: Error?) -> Error {
      
      if let err = error {
        let nsErr = err as NSError
        return NSError(domain: nsErr.domain, code: nsErr.code, userInfo: [NSLocalizedDescriptionKey: "\(description), \(nsErr.localizedDescription)"])
      } else {
        return NSError(domain: "v2risk", code: 500, userInfo: [NSLocalizedDescriptionKey: "\(description)"])
      }
    }
    
    private static func constructSummaryInfo(_ day: ENExposureDaySummary, _ windows: [ExposureProcessor.ExposureDetailsWindow]) -> ExposureProcessor.ExposureInfo {
        
        let calendar = Calendar.current
        let components = calendar.dateComponents([.day], from: day.date, to: Date())
        
        let summedDurations = sumDurations(windows)
        var info = ExposureProcessor.ExposureInfo(daysSinceLastExposure: components.day!, attenuationDurations: summedDurations.buckets, matchedKeyCount: -1,  maxRiskScore: Int(day.daySummary.maximumScore), exposureDate: Date())
        
        info.customAttenuationDurations = summedDurations.buckets
        info.riskScoreSumFullRange = Int(day.daySummary.scoreSum)
        info.windows = windows
        
        os_log("Exposure detected", log: OSLog.checkExposure, type: .debug)
        
        return info
    }
        
    private static func sumDurations(_ windows: [ExposureProcessor.ExposureDetailsWindow]) -> ExposureProcessor.ExposureScanData {
        var data: ExposureProcessor.ExposureScanData = ExposureProcessor.ExposureScanData(buckets: [0, 0, 0, 0], exceedsThreshold: false)
        
        for window in windows {
            for (index, element) in window.scanData.buckets.enumerated() {
                data.buckets[index] += element
            }
        }
        return data
    }
    
    private static func extractExposureWindowData(_ summary: ENExposureDetectionSummary, _ configuration: ENExposureConfiguration, _ thresholds: ExposureCheck.Thresholds, _ dayDate: Date, _ completion: @escaping  (Result<([ExposureProcessor.ExposureDetailsWindow]), Error>) -> Void) {
    
        ExposureManager.shared.manager.getExposureWindows(summary: summary) { exposureWindows, error in
      
            if let error = error {
                return completion(.failure(error))
            }
      
            guard let windows = exposureWindows else {
                return completion(.failure(wrapError("No exposure window data available", nil)))
            }
            
            let matchWindows = windows.filter({$0.date == dayDate})
            let windowList: [ExposureProcessor.ExposureDetailsWindow] = matchWindows.map { window in
                let scan = buildScanData(window.scanInstances, configuration, thresholds)
                
                return ExposureProcessor.ExposureDetailsWindow(date: dayDate, calibrationConfidence: window.calibrationConfidence.rawValue, diagnosisReportType: window.diagnosisReportType.rawValue, infectiousness: window.infectiousness.rawValue, scanData: scan)
            }
                        
            return completion(.success(windowList))
        }

    }
    
    private static func buildScanData(_ scanInstances: [ENScanInstance], _ configuration: ENExposureConfiguration, _ thresholds: ExposureCheck.Thresholds) -> ExposureProcessor.ExposureScanData {
        
        var data = ExposureProcessor.ExposureScanData(buckets: [0, 0, 0, 0], exceedsThreshold: false)
        let thresholdWeightings = [configuration.immediateDurationWeight, configuration.nearDurationWeight, configuration.mediumDurationWeight, configuration.otherDurationWeight]
        
        for scan in scanInstances {
            for (index, element) in configuration.attenuationDurationThresholds.enumerated() {
                if scan.typicalAttenuation <= Int(truncating: element) {
                    data.buckets[index] += scan.secondsSinceLastScan / 60 * Int(thresholdWeightings[index] / 100.0)
                    break
                }
            }
        }
        let contactTime = data.buckets.reduce(0, +)
        if Int(contactTime) >= thresholds.timeThreshold {
            data.exceedsThreshold = true
        }
        return data
    }
    
}
