import Foundation
import ExposureNotification

@available(iOS 13.7, *)
class RiskCalculationV2 {
    
    public struct Thresholds {
      let thresholdWeightings: [Double]
      let timeThreshold: Int
    }
    
    private struct ScanData {
        var buckets: [Int]
        var exceedsThresholds: Bool
    }
    
    private struct WindowData {
        let date: Date
        let calibrationConfidence: ENCalibrationConfidence
        let diagnosisReportType: ENDiagnosisReportType
        let infectiousness: ENInfectiousness
        var cumulativeScans: ScanData
        var contiguousScans: [ScanData]
    }
    
    public static func calculateRisk(_ summary: ENExposureDetectionSummary, _ attenuationRanges: [NSNumber], _ thresholds: ExposureCheck.Thresholds, _ completion: @escaping  (Result<(ExposureProcessor.ExposureInfo), Error>) -> Void)
    {
        guard summary.daySummaries.count > 0 else {
            return completion(.failure(wrapError("V2 - No daily summaries, no exposures detected", nil)))
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
            return completion(.failure(wrapError("V2 - No daily summary meeting duration detected", nil)))
        }
        
        extractExposureWindowData(summary, attenuationRanges, thresholds) { result in
            switch result {
               case let .success(windows):
                  completion(.success(constructSummaryInfo(mostRecent, windows)))
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
    
    private static func constructSummaryInfo(_ day: ENExposureDaySummary, _ windows: [WindowData]) -> ExposureProcessor.ExposureInfo {
        let calendar = Calendar.current
        let components = calendar.dateComponents([.day], from: day.date, to: Date())
        
        var info = ExposureProcessor.ExposureInfo(daysSinceLastExposure: components.day!, attenuationDurations:windows[0].cumulativeScans.buckets, matchedKeyCount: 1,  maxRiskScore: Int(day.daySummary.maximumScore), exposureDate: Date())
        
        info.customAttenuationDurations = windows[0].cumulativeScans.buckets
        info.riskScoreSumFullRange = Int(day.daySummary.scoreSum)

        os_log("Exposure detected", log: OSLog.checkExposure, type: .debug)
        
        
        return info
    }
    
    private static func extractExposureWindowData(_ summary: ENExposureDetectionSummary, _ attenuations: [NSNumber], _ thresholds: ExposureCheck.Thresholds, _ completion: @escaping  (Result<([WindowData]), Error>) -> Void) {
    
        ExposureManager.shared.manager.getExposureWindows(summary: summary) { exposureWindows, error in
      
            if let error = error {
                return completion(.failure(error))
            }
      
            guard let windows = exposureWindows else {
                return completion(.failure(wrapError("No exposure window data available", nil)))
            }
      
            var windowList: [WindowData] = []
                
            for window in windows {
                let scan = buildScanData(window.scanInstances, attenuations, thresholds)
                var item = windowList.first(where: {$0.date == window.date})
                
                if item == nil {
                    item = WindowData(date: window.date, calibrationConfidence: window.calibrationConfidence, diagnosisReportType: window.diagnosisReportType, infectiousness: window.infectiousness, cumulativeScans: ScanData(buckets: [0,0,0,0], exceedsThresholds: false), contiguousScans: [])
                    windowList.append(item!)
                }
                
                for (index, element) in scan.buckets.enumerated() {
                    item!.cumulativeScans.buckets[index] += element
                }
                item!.contiguousScans.append(scan)
            }
            

            return completion(.success(windowList))
        }

    }
    
    private static func buildScanData(_ scanInstances: [ENScanInstance], _ attenuations: [NSNumber], _ thresholds: ExposureCheck.Thresholds) -> ScanData {
        
        var data: ScanData = ScanData(buckets: [0, 0, 0, 0], exceedsThresholds: false)
        
        for scan in scanInstances {
            for (index, element) in attenuations.enumerated() {
                if scan.typicalAttenuation <= Int(truncating: element) {
                    data.buckets[index] += scan.secondsSinceLastScan
                    break
                }
            }
        }
        var contactTime = 0
        for (index, element) in data.buckets.enumerated() {
          contactTime += Int(Double(element) * thresholds.thresholdWeightings[index])
        }
        if contactTime >= thresholds.timeThreshold {
            data.exceedsThresholds = true
        }
        return data
    }
    
}
