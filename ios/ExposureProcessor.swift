import Foundation
import ExposureNotification
import os.log
import BackgroundTasks

@available(iOS 13.5, *)
public class ExposureProcessor {  
    public struct ExposureInfo: Codable {
        let daysSinceLastExposure: Int
        let attenuationDurations: [Int]
        let matchedKeyCount: Int
        let maxRiskScore: Int
        let exposureDate: Date
        var maximumRiskScoreFullRange: Int!
        var riskScoreSumFullRange: Int!
        var customAttenuationDurations: [Int]!
        var details: [ExposureDetails]!
    }
    public struct ExposureDetails: Codable {
        let date: Date
        let duration: TimeInterval
        let totalRiskScore: ENRiskScore
        let transmissionRiskLevel: ENRiskLevel
        let attenuationDurations: [Int]
        let attenuationValue: ENAttenuation
        //metadata
    }
  
    private let backgroundName = Bundle.main.bundleIdentifier! + ".exposure-notification"
    
    static public let shared = ExposureProcessor()
    var keyValueObservers = [NSKeyValueObservation]()
    
    init() {
        
    }
    
    deinit {
        self.keyValueObservers.removeAll()
    }
    
  
    public func authoriseExposure(_ resolve: @escaping RCTPromiseResolveBlock,
                                  rejecter reject: @escaping RCTPromiseRejectBlock) {
        ExposureManager.shared.manager.setExposureNotificationEnabled(true) { error in
            if let error = error as? ENError {
                os_log("Error enabling notification services, %@", log: OSLog.exposure, type: .error, error.localizedDescription)
                return reject("AUTH", "Unable to authorise exposure", error)
            }
            os_log("Exposure authorised: %d", log: OSLog.exposure, type: .debug, ENManager.authorizationStatus.rawValue)
            // return if authorised or not
            resolve(ENManager.authorizationStatus == .authorized)
        }
    }
    
    public func exposureEnabled(_ resolve: RCTPromiseResolveBlock,
                                      rejecter reject: RCTPromiseRejectBlock) {
        os_log("Exposure enabled: %d", log: OSLog.exposure, type: .debug, ExposureManager.shared.manager.exposureNotificationEnabled)
        resolve(ExposureManager.shared.manager.exposureNotificationEnabled)
    }
    
    public func isAuthorised(_ resolve: RCTPromiseResolveBlock,
                                      rejecter reject: RCTPromiseRejectBlock) {
        os_log("Is Authorised: %d", log: OSLog.exposure, type: .debug, ENManager.authorizationStatus.rawValue)
        switch ENManager.authorizationStatus {
          case .authorized:
              resolve("granted")
          case .notAuthorized:
              resolve("blocked")
          case .restricted:
              resolve("blocked")
          default:
              resolve("unknown")
        }
    }
    
    public func status(_ resolve: @escaping RCTPromiseResolveBlock,
                    rejecter reject: RCTPromiseRejectBlock) {
      var result: [String: Any] = [:]
      switch ExposureManager.shared.manager.exposureNotificationStatus {
        case .active:
            result["state"] = "active"
        case .unknown:
            result["state"] = "unknown"
        case .disabled:
            result["state"] = "disabled"
            result["type"] = ["exposure"]
        case .bluetoothOff:
            result["state"] = "disabled"
            result["type"] = ["bluetooth"]
        case .restricted:
            result["state"] = "restricted"
        default:
            result["state"] = "unavailable"
      }
      os_log("Status is %d", log: OSLog.checkExposure, type: .debug, ExposureManager.shared.manager.exposureNotificationStatus.rawValue)
      
      self.keyValueObservers.append(ExposureManager.shared.manager.observe(\.exposureNotificationStatus) { manager, change in
          NotificationCenter.default.post(name: .onStatusChanged, object: ExposureManager.shared.manager.exposureNotificationStatus)
      })
      resolve(result)
    }
  
    public func start(_ resolve: @escaping RCTPromiseResolveBlock,
                        rejecter reject: @escaping RCTPromiseRejectBlock) {
        guard ENManager.authorizationStatus == .authorized else {
            os_log("Not authorised so can't start", log: OSLog.exposure, type: .info)
            return reject("NOTAUTH", "Not authorised to start", nil)
        }
        
        ExposureManager.shared.manager.setExposureNotificationEnabled(true) { error in
            if let error = error as? ENError {
                os_log("Error starting notification services, %@", log: OSLog.exposure, type: .error, error.localizedDescription)
                return reject("START", "Error starting notification services", error)
            } else {
                os_log("Service started", log: OSLog.exposure, type: .debug)
                resolve(true)
            }
        }
        
        scheduleCheckExposure()
    }
    
    public func stop(_ resolve: @escaping RCTPromiseResolveBlock,
                    rejecter reject: @escaping RCTPromiseRejectBlock) {
        guard ENManager.authorizationStatus == .authorized else {
            os_log("Not authorised so can't stop", log: OSLog.exposure, type: .info)
            return reject("NOTAUTH", "Not authorised to stop", nil)
        }
        ExposureManager.shared.manager.setExposureNotificationEnabled(false) { error in
            if let error = error as? ENError {
              os_log("Error stopping notification services, %@", log: OSLog.setup, type: .error, error.localizedDescription)
              return reject("STOP", "Error stopping notification services", error)
            } else {
               os_log("Service stopped", log: OSLog.setup, type: .debug)
              resolve(true)
            }
        }
    }
    
    public func getDiagnosisKeys(_ resolve: @escaping RCTPromiseResolveBlock,
                                           rejecter reject: @escaping RCTPromiseRejectBlock) {
        
      guard ENManager.authorizationStatus == .authorized else {
          os_log("Not authorised so can't get keys", log: OSLog.exposure, type: .info)
          return reject("Auth", "User has not authorised ENS", nil)
      }
      // getTestDiagnosisKeys
      ExposureManager.shared.manager.getDiagnosisKeys { temporaryExposureKeys, error in
          if let error = error as? ENError, error.code == .notAuthorized {
              os_log("User did authorise the extraction of keys, %@", log: OSLog.exposure, type: .error, error.localizedDescription)
              return reject("Auth", "User did not authorize key extraction", error)
          } else if let error = error {
              os_log("Unexpected error occurred getting the keys, %@", log: OSLog.exposure, type: .error, error.localizedDescription)
              return reject("getKeys", "Error extracting keys", error)
          } else {
              guard temporaryExposureKeys != nil else {
                 return resolve([])
              }

              let codableDiagnosisKeys = temporaryExposureKeys!.compactMap { diagnosisKey -> [String: Any]? in
                return [
                  "keyData": diagnosisKey.keyData.base64EncodedString(),
                  "rollingPeriod": diagnosisKey.rollingPeriod,
                  "rollingStartNumber": diagnosisKey.rollingStartNumber,
                  "transmissionRiskLevel": diagnosisKey.transmissionRiskLevel
                ]
              }

              resolve(codableDiagnosisKeys)
          }
      }
    }
  
    public func getLogData(_ resolve: @escaping RCTPromiseResolveBlock,
                           rejecter reject: @escaping RCTPromiseRejectBlock) {
      let context = Storage.PersistentContainer.shared.newBackgroundContext()
      guard let config = Storage.shared.readSettings(context) else {
        return resolve([])
      }
      var data:[String: Any] = [:]
      data["lastRun"] = config.datesLastRan
      data["lastError"] = config.lastError ?? ""
      data["lastIndex"] = config.lastExposureIndex ?? 0
      resolve(data)
      
    }
  
    public func getCloseContacts(_ resolve: @escaping RCTPromiseResolveBlock,
                                  rejecter reject: @escaping RCTPromiseRejectBlock) {
       
       let context = Storage.PersistentContainer.shared.newBackgroundContext()
       guard let config = Storage.shared.readSettings(context) else {
         return resolve([])
       }
       let info = Storage.shared.getExposures(config.storeExposuresFor).reversed()
       
       let exposures = info.compactMap { exposure -> [String: Any]? in
         var item: [String: Any] = [
           "daysSinceLastExposure": exposure.daysSinceLastExposure,
           "matchedKeyCount": exposure.matchedKeyCount,
           "maxRiskScore": exposure.maxRiskScore,
           "attenuationDurations": exposure.attenuationDurations,
           "exposureAlertDate": Int64(exposure.exposureDate.timeIntervalSince1970 * 1000.0),
           "maximumRiskScoreFullRange": exposure.maximumRiskScoreFullRange ?? 0,
           "riskScoreSumFullRange": exposure.riskScoreSumFullRange ?? 0,
           "customAttenuationDurations": exposure.customAttenuationDurations ?? []
         ]
         if let details = exposure.details {
           let extraInfo = details.compactMap { detail -> [String: Any]? in
            return ["date": Int64(detail.date.timeIntervalSince1970 * 1000.0),
                     "duration": detail.duration,
                     "totalRiskScore": detail.totalRiskScore,
                     "attenuationValue": detail.attenuationValue,
                     "attenuationDurations": detail.attenuationDurations,
                     "transmissionRiskLevel": detail.transmissionRiskLevel
             ]
           }
           item["details"] = extraInfo
         }
         return item
       }
       resolve(exposures)
    }
  
  
    public func deleteAllData(_ resolve: @escaping RCTPromiseResolveBlock,
                              rejecter reject: @escaping RCTPromiseRejectBlock) {
      Storage.shared.deleteData(false)
      resolve(true)
    }

    public func deleteExposureData(_ resolve: @escaping RCTPromiseResolveBlock,
                              rejecter reject: @escaping RCTPromiseRejectBlock) {
      Storage.shared.deleteData(true)
      resolve(true)
    }
  
    public func registerBackgroundProcessing() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: self.backgroundName,
            using: .main) { task in
                ExposureProcessor.shared.checkExposureBackground(task as! BGProcessingTask)
        }
        os_log("Registering background task", log: OSLog.exposure, type: .debug)
    }
    
    private func checkExposureBackground(_ task: BGTask) {
       os_log("Running exposure check in background", log: OSLog.exposure, type: .debug)
       let queue = OperationQueue()
       queue.maxConcurrentOperationCount = 1
       queue.addOperation(ExposureCheck(false, false))

       task.expirationHandler = {
          os_log("Background task expiring", log: OSLog.checkExposure, type: .debug)
          queue.cancelAllOperations()
       }

       let lastOperation = queue.operations.last
       lastOperation?.completionBlock = {
           os_log("Background exposure check is complete, %d", log: OSLog.exposure, type: .debug, lastOperation?.isCancelled ?? false)
           task.setTaskCompleted(success: !(lastOperation?.isCancelled ?? false))
        
           self.scheduleCheckExposure()
       }
    }
    
    public func checkExposureForeground(_ exposureDetails: Bool, _ skipTimeCheck: Bool) {
       os_log("Running exposure check in foreground", log: OSLog.exposure, type: .debug)
       let queue = OperationQueue()
       queue.maxConcurrentOperationCount = 1
       queue.addOperation(ExposureCheck(skipTimeCheck, exposureDetails))

       let lastOperation = queue.operations.last
       lastOperation?.completionBlock = {
           os_log("Foreground exposure check is complete, %d", log: OSLog.exposure, type: .debug, lastOperation?.isCancelled ?? false)
       }
    }
    
    private func scheduleCheckExposure() {
      let context = Storage.PersistentContainer.shared.newBackgroundContext()
      guard ENManager.authorizationStatus == .authorized else {
           os_log("Not authorised so can't schedule exposure checks", log: OSLog.exposure, type: .info)
           Storage.shared.updateRunData(context, "Not authorised so can't schedule exposure checks")
           return
      }
      
      do {
          let request = BGProcessingTaskRequest(identifier: self.backgroundName)
          request.requiresNetworkConnectivity = true
          try BGTaskScheduler.shared.submit(request)
          os_log("Scheduling background exposure check", log: OSLog.setup, type: .debug)
      } catch {
          os_log("An error occurred scheduling background task, %@", log: OSLog.setup, type: .error, error.localizedDescription)
          Storage.shared.updateRunData(context, "An error occurred scheduling the background task, \(error.localizedDescription)")
      }
    }
    
}

extension Notification.Name {
  static var onStatusChanged: Notification.Name {
    return .init(rawValue: "ExposureProcessor.onStatusChanged")
  }
}
