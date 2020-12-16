import Foundation
import ExposureNotification

@available(iOS 12.5, *)
class RiskCalculationV2 {
    
    public static func calculateRisk(_ summary: ENExposureDetectionSummary, _ configuration: ENExposureConfiguration, _ thresholds: ExposureCheck.Thresholds, _ completion: @escaping  (Result<(ExposureProcessor.ExposureInfo?), Error>) -> Void)
    {
        guard summary.daySummaries.count > 0 else {
            os_log("V2 - No daily summaries returned, no exposures matched", log: OSLog.checkExposure, type: .info)
            return completion(.success(nil))
        }
        
        var mostRecent: ENExposureDaySummary?
        var matchDate: Date?
        
        if (!thresholds.contiguousMode) {
            let aboveThresholdDays = summary.daySummaries.sorted(by: { $0.date > $1.date })
            mostRecent = aboveThresholdDays.first
            matchDate = mostRecent!.date
        }
        
        extractExposureWindowData(summary, configuration, thresholds, matchDate) { result in
            switch result {
               case let .success(windows):
                if thresholds.contiguousMode {
                    let filtered = windows.filter({ $0.scanData.exceedsThreshold }).sorted(by: { $0.date > $1.date })
                    if (filtered.count > 0) {
                        let day = summary.daySummaries.filter({ $0.date == filtered.first!.date})
                        completion(.success(constructSummaryInfo(day.first!, windows)))
                    } else {
                        os_log("V2 - Running in Contiguous mode, no contiguos match", log: OSLog.checkExposure, type: .info)

                        completion(.success(nil))
                    }
                } else {
                    completion(.success(constructSummaryInfo(mostRecent!, windows)))
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
        let dateToday = calendar.startOfDay(for: Date())
        let components = calendar.dateComponents([.day], from: day.date, to: dateToday)
        let filteredWindows = windows.filter{$0.date == day.date}
        
        let summedDurations = sumDurations(filteredWindows)
        var info = ExposureProcessor.ExposureInfo(daysSinceLastExposure: components.day!, attenuationDurations: summedDurations.weightedBuckets, matchedKeyCount: -1,  maxRiskScore: Int(day.daySummary.maximumScore), exposureDate: dateToday, exposureContactDate: day.date)
        
        info.customAttenuationDurations = summedDurations.weightedBuckets
        info.riskScoreSumFullRange = Int(day.daySummary.scoreSum)
        
        info.windows = filteredWindows
        
        os_log("Exposure detected", log: OSLog.checkExposure, type: .debug)
        
        return info
    }
        
    private static func sumDurations(_ windows: [ExposureProcessor.ExposureDetailsWindow]) -> ExposureProcessor.ExposureScanData {
        var data: ExposureProcessor.ExposureScanData = ExposureProcessor.ExposureScanData(buckets: [0, 0, 0, 0], weightedBuckets: [0, 0, 0, 0], exceedsThreshold: false, numScans: windows.count)
        
        for window in windows {
            for (index, element) in window.scanData.buckets.enumerated() {
                data.buckets[index] += element
            }
            for (index, element) in window.scanData.weightedBuckets.enumerated() {
                data.weightedBuckets[index] += element
            }

        }
        return data
    }
    
    private static func extractExposureWindowData(_ summary: ENExposureDetectionSummary, _ configuration: ENExposureConfiguration, _ thresholds: ExposureCheck.Thresholds, _ dayDate: Date?, _ completion: @escaping  (Result<([ExposureProcessor.ExposureDetailsWindow]), Error>) -> Void) {
    
        ExposureManager.shared.manager.getExposureWindows(summary: summary) { exposureWindows, error in
      
            if let error = error {
                return completion(.failure(error))
            }
      
            guard let windows = exposureWindows else {
                return completion(.failure(wrapError("No exposure window data available", nil)))
            }
            
            var matchWindows:[ENExposureWindow] = windows
            if (dayDate != nil) {
                matchWindows = windows.filter({$0.date == dayDate})
            }
            
            let windowList: [ExposureProcessor.ExposureDetailsWindow] = matchWindows.map { window in
                let scan = buildScanData(window.scanInstances, configuration, thresholds)
                
                return ExposureProcessor.ExposureDetailsWindow(date: window.date, calibrationConfidence: window.calibrationConfidence.rawValue, diagnosisReportType: window.diagnosisReportType.rawValue, infectiousness: window.infectiousness.rawValue, scanData: scan)
            }
                        
            return completion(.success(windowList))
        }

    }
    
    private static func buildScanData(_ scanInstances: [ENScanInstance], _ configuration: ENExposureConfiguration, _ thresholds: ExposureCheck.Thresholds) -> ExposureProcessor.ExposureScanData {
        
        var data = ExposureProcessor.ExposureScanData(buckets: [0, 0, 0, 0], weightedBuckets: [0, 0, 0, 0], exceedsThreshold: false, numScans: scanInstances.count)
        let thresholdWeightings = [configuration.immediateDurationWeight, configuration.nearDurationWeight, configuration.mediumDurationWeight, configuration.otherDurationWeight]
        
        for scan in scanInstances {
            var added = false
            for (index, element) in configuration.attenuationDurationThresholds.enumerated() {
                if scan.typicalAttenuation <= Int(truncating: element) {
                    data.weightedBuckets[index] += scan.secondsSinceLastScan / 60 * Int(thresholdWeightings[index] / 100.0)
                    data.buckets[index] += scan.secondsSinceLastScan / 60
                    added = true
                    break
                }
            }
            if (!added) {
                let index = data.weightedBuckets.count - 1
                data.weightedBuckets[index] += scan.secondsSinceLastScan / 60 * Int(thresholdWeightings[index] / 100.0)
                data.buckets[index] += scan.secondsSinceLastScan / 60
            }
        }
        let contactTime = data.weightedBuckets.reduce(0, +)
        if Int(contactTime) >= thresholds.timeThreshold {
            data.exceedsThreshold = true
        }
        return data
    }
    
}
