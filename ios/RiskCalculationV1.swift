import Foundation
import ExposureNotification

@available(iOS 13.5, *)
class RiskCalculationV1 {
    
    public static func calculateRisk(_ summary: ENExposureDetectionSummary, _ thresholds: ExposureCheck.Thresholds, _ completion: @escaping  (Result<(ExposureProcessor.ExposureInfo?), Error>) -> Void) {
        
        guard summary.matchedKeyCount > 0 else {
            os_log("V1 - No keys matched, no exposures detected", log: OSLog.checkExposure, type: .info)
            return completion(.success(nil))
        }
        var contactTime = 0
        let info = constructSummaryInfo(summary)
        let durations:[Int] = info.customAttenuationDurations ?? info.attenuationDurations
        
        guard thresholds.thresholdWeightings.count >= durations.count else {
            return completion(.failure(wrapError("V1 - Threshold keys not correctly defined", nil)))
        }
        
        for (index, element) in durations.enumerated() {
          contactTime += Int(Double(element) * thresholds.thresholdWeightings[index])
        }
        
        os_log("Calculated contact time, %@, %d, %d", log: OSLog.checkExposure, type: .debug, durations.map { String($0) }, contactTime, thresholds.timeThreshold)
        
        if contactTime >= thresholds.timeThreshold && summary.maximumRiskScoreFullRange > 0 {
            return completion(.success(info))
        } else {
            os_log("V1 - Duration did not exceed threshold or risk score was not met", log: OSLog.checkExposure, type: .info)
            return completion(.success(nil))
        }
    }

    private static func wrapError(_ description: String, _ error: Error?) -> Error {
      
      if let err = error {
        let nsErr = err as NSError
        return NSError(domain: nsErr.domain, code: nsErr.code, userInfo: [NSLocalizedDescriptionKey: "\(description), \(nsErr.localizedDescription)"])
      } else {
        return NSError(domain: "v1risk", code: 500, userInfo: [NSLocalizedDescriptionKey: "\(description)"])
      }
    }
    
    private static func constructSummaryInfo(_ summary: ENExposureDetectionSummary) -> ExposureProcessor.ExposureInfo {
        let calendar = Calendar.current
        let dateToday = calendar.startOfDay(for: Date())
        let contactDate = calendar.date(byAdding: .day, value: (0 - summary.daysSinceLastExposure), to: dateToday)
        
        var info = ExposureProcessor.ExposureInfo(daysSinceLastExposure: summary.daysSinceLastExposure, attenuationDurations: self.convertDurations(summary.attenuationDurations), matchedKeyCount: Int(summary.matchedKeyCount),  maxRiskScore: Int(summary.maximumRiskScore), exposureDate: dateToday, exposureContactDate: contactDate!)
     
        if let meta = summary.metadata {
            info.maximumRiskScoreFullRange = meta["maximumRiskScoreFullRange"] as? Int
            info.riskScoreSumFullRange = meta["riskScoreSumFullRange"] as? Int
            info.customAttenuationDurations = self.convertDurations(meta["attenuationDurations"] as? [NSNumber])
        }
        
        os_log("Exposure detected", log: OSLog.checkExposure, type: .debug)
        
        return info
    }
    
    private static func convertDurations(_ durations: [NSNumber]?) -> [Int] {
      let empty: [NSNumber] = []
      
      return (durations ?? empty).compactMap { item in
        Int(item.doubleValue / 60.0)
      }
    }
}
