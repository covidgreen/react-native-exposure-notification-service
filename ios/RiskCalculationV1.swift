import Foundation
import ExposureNotification

@available(iOS 13.5, *)
class RiskCalculationV1 {

    public struct Thresholds {
      let thresholdWeightings: [Double]
      let timeThreshold: Int
    }
    
    public static func calculateRisk(_ summary: ENExposureDetectionSummary, _ thresholds: Thresholds) -> ExposureProcessor.ExposureInfo?
    {
        guard summary.matchedKeyCount > 0 else {
          os_log("No keys matched, no exposures detected", log: OSLog.checkExposure, type: .debug)
          return nil
        }
        var contactTime = 0
        let info = constructSummaryInfo(summary)
        let durations:[Int] = info.customAttenuationDurations ?? info.attenuationDurations
        
        guard thresholds.thresholdWeightings.count >= durations.count else {
          return nil
        }
        for (index, element) in durations.enumerated() {
          contactTime += Int(Double(element) * thresholds.thresholdWeightings[index])
        }
        
        os_log("Calculated contact time, %@, %d, %d", log: OSLog.checkExposure, type: .debug, durations.map { String($0) }, contactTime, thresholds.timeThreshold)
        
        if contactTime >= thresholds.timeThreshold && summary.maximumRiskScoreFullRange > 0 {
            return info
        } else {
            return nil
        }
    }

    private static func constructSummaryInfo(_ summary: ENExposureDetectionSummary) -> ExposureProcessor.ExposureInfo {
        
        var info = ExposureProcessor.ExposureInfo(daysSinceLastExposure: summary.daysSinceLastExposure, attenuationDurations: self.convertDurations(summary.attenuationDurations), matchedKeyCount: Int(summary.matchedKeyCount),  maxRiskScore: Int(summary.maximumRiskScore), exposureDate: Date())
     
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
