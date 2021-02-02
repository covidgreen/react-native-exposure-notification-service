import Foundation
import ExposureNotification
import os.log
import BackgroundTasks

@available(iOS 12.5, *)
public class ExposureProcessor {  
    public struct ExposureInfo: Codable {
        let daysSinceLastExposure: Int
        let attenuationDurations: [Int]
        let matchedKeyCount: Int
        let maxRiskScore: Int
        let exposureDate: Date
        let exposureContactDate: Date
        var maximumRiskScoreFullRange: Int!
        var riskScoreSumFullRange: Int!
        var customAttenuationDurations: [Int]!
        var windows: [ExposureDetailsWindow]!
    }
    public struct ExposureScanData: Codable {
        var buckets: [Int]
        var weightedBuckets: [Int]!
        var exceedsThreshold: Bool
        var numScans: Int
    }
    public struct ExposureDetailsWindow: Codable {
        let date: Date
        let calibrationConfidence: UInt8
        let diagnosisReportType: UInt32
        let infectiousness: UInt32
        var scanData: ExposureScanData
    }
    
    public enum SupportedENAPIVersion {
        case version2
        case version1
        case unsupported
    }

    private let backgroundName = Bundle.main.bundleIdentifier! + ".exposure-notification"
    
    static public let shared = ExposureProcessor()
    var keyValueObservers = [NSKeyValueObservation]()
    
    init() {
        
    }
    
    deinit {
        self.keyValueObservers.removeAll()
    }

    public func getSupportedExposureNotificationsVersion() -> SupportedENAPIVersion {
        if #available(iOS 13.7, *) {
            return .version2
        } else if #available(iOS 13.5, *) {
            return .version1
        } else if ExposureNotificationModule.ENManagerIsAvailable() {
            return .version2
        } else {
            return .unsupported
        }
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
      result["type"] = [""]
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
        case .paused:
            result["state"] = "disabled"
            result["type"] = ["paused"]
        case .unauthorized:
            result["state"] = "disabled"
            result["type"] = ["unauthorized"]            
        default:
            result["state"] = "unavailable"
            result["type"] = ["starting"]
      }
      if ExposureManager.shared.isPaused() && (result["state"] as! String == "disabled" || result["state"] as! String == "unknown") {
         result["state"] = "disabled"
         result["type"] = ["paused"]
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
        let context = Storage.PersistentContainer.shared.newBackgroundContext()
        Storage.shared.flagPauseStatus(context, false)
        Storage.shared.flagStopped(context, false)
        ExposureManager.shared.manager.setExposureNotificationEnabled(true) { error in
            if let error = error as? ENError {
                os_log("Error starting notification services, %@", log: OSLog.exposure, type: .error, error.localizedDescription)
                return reject("START", "Error starting notification services", error)
            } else {
                os_log("Service started", log: OSLog.exposure, type: .debug)
                self.scheduleCheckExposure()
                resolve(true)
            }
        }
        self.scheduleCheckExposure()
    }

    public func pause(_ resolve: @escaping RCTPromiseResolveBlock,
                        rejecter reject: @escaping RCTPromiseRejectBlock) {
        guard ENManager.authorizationStatus == .authorized else {
            os_log("Not authorised so can't pause", log: OSLog.exposure, type: .info)
            return reject("NOTAUTH", "Not authorised to start", nil)
        }
        let context = Storage.PersistentContainer.shared.newBackgroundContext()
        Storage.shared.flagPauseStatus(context, true)
        ExposureManager.shared.manager.setExposureNotificationEnabled(false) { error in
            if let error = error as? ENError {
                os_log("Error pausing./stopping notification services, %@", log: OSLog.exposure, type: .error, error.localizedDescription)
                /// clear pause flag if we failed to stop ens
                Storage.shared.flagPauseStatus(context, false)
                return reject("PAUSE", "Error pausing notification services", error)
            } else {
                os_log("Service paused", log: OSLog.exposure, type: .debug)
                resolve(true)
            }
        }
        
    }
    
    public func stop(_ resolve: @escaping RCTPromiseResolveBlock,
                    rejecter reject: @escaping RCTPromiseRejectBlock) {
        guard ENManager.authorizationStatus == .authorized else {
            os_log("Not authorised so can't stop", log: OSLog.exposure, type: .info)
            return reject("NOTAUTH", "Not authorised to stop", nil)
        }
        let context = Storage.PersistentContainer.shared.newBackgroundContext()
        Storage.shared.flagPauseStatus(context, false)
        Storage.shared.flagStopped(context, true)
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
  
    public func getConfigData(_ resolve: @escaping RCTPromiseResolveBlock,
                           rejecter reject: @escaping RCTPromiseRejectBlock) {
      let context = Storage.PersistentContainer.shared.newBackgroundContext()
      guard let config = Storage.shared.readSettings(context) else {
        return resolve([])
      }
      var data:[String: Any] = [:]
      data["token"] = config.authToken
      data["refreshToken"] = config.refreshToken
      data["serverURL"] = config.serverURL
      data["keyServerURL"] = config.keyServerUrl
      data["serverType"] = config.keyServerType.rawValue
      data["analyticsOptin"] = config.analyticsOptin
      data["keychainGetError"] = config.lastKeyChainGetError
      data["keychainSetError"] = config.lastKeyChainSetError
      data["lastExposureIndex"] = config.lastExposureIndex
        
      let formatter = DateFormatter()
      formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
      
      if let when = config.lastUpdated {
        data["lastUpdated"] = formatter.string(from: when)
      } else {
        data["lastUpdated"] = "Not updated"
      }
      if let ran = config.lastRunDate {
        data["lastRunDate"] = formatter.string(from: ran)
      } else {
        data["lastRunDate"] = "Not run"
      }
      resolve(data)
      
    }

    public func getCloseContacts(_ resolve: @escaping RCTPromiseResolveBlock,
                                  rejecter reject: @escaping RCTPromiseRejectBlock) {
       
       let context = Storage.PersistentContainer.shared.newBackgroundContext()
       var exposurePeriod:Int = 14
       if let config = Storage.shared.readSettings(context) {
         exposurePeriod = config.storeExposuresFor
       }
       let info = Storage.shared.getExposures(exposurePeriod).reversed()
       
       let exposures = info.compactMap { exposure -> [String: Any]? in
         var item: [String: Any] = [
           "daysSinceLastExposure": exposure.daysSinceLastExposure,
           "matchedKeyCount": exposure.matchedKeyCount,
           "maxRiskScore": exposure.maxRiskScore,
           "attenuationDurations": exposure.attenuationDurations,
           "exposureAlertDate": Int64(exposure.exposureDate.timeIntervalSince1970 * 1000.0),
           "exposureDate": Int64(exposure.exposureContactDate.timeIntervalSince1970 * 1000.0),
           "maxRiskScoreFullRange": exposure.maximumRiskScoreFullRange ?? 0,
           "riskScoreSumFullRange": exposure.riskScoreSumFullRange ?? 0,
         ]
         if let windows = exposure.windows {
           let windowInfo = windows.compactMap { window -> [String: Any]? in
            return ["date": Int64(window.date.timeIntervalSince1970 * 1000.0),
                    "calibrationConfidence": window.calibrationConfidence,
                    "diagnosisReportType": window.diagnosisReportType,
                    "infectiousness": window.infectiousness,
                    "buckets": window.scanData.buckets,
                    "weightedBuckets": window.scanData.weightedBuckets,
                    "numScans": window.scanData.numScans,
                    "exceedsThreshold": window.scanData.exceedsThreshold
             ]
           }
           item["windows"] = windowInfo
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
        if #available(iOS 13.5, *) {
            BGTaskScheduler.shared.register(
                forTaskWithIdentifier: self.backgroundName,
                using: .main) { task in
                    ExposureProcessor.shared.checkExposureBackground(task as! BGProcessingTask)
            }
            os_log("Registering background task", log: OSLog.exposure, type: .debug)
        }
        self.scheduleCheckExposure()
    }
    
    @available(iOS 13.5, *)
    private func checkExposureBackground(_ task: BGTask) {
       os_log("Running exposure check in background", log: OSLog.exposure, type: .debug)
       let queue = OperationQueue()
       queue.maxConcurrentOperationCount = 1
       queue.addOperation(ExposureCheck(false, false, 0))

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
    
    public func checkExposureForeground(_ skipTimeCheck: Bool, _ simulateExposure: Bool, _ simulateDays: Int) {
       os_log("Running exposure check in foreground", log: OSLog.exposure, type: .debug)
       let queue = OperationQueue()
       queue.maxConcurrentOperationCount = 1
       queue.addOperation(ExposureCheck(skipTimeCheck, simulateExposure, simulateDays))

       let lastOperation = queue.operations.last
       lastOperation?.completionBlock = {
           os_log("Foreground exposure check is complete, %d", log: OSLog.exposure, type: .debug, lastOperation?.isCancelled ?? false)
       }
    }
    
    private func scheduleCheckExposure() {
      let context = Storage.PersistentContainer.shared.newBackgroundContext()
      
      if #available(iOS 13.5, *) {
          do {
              let request = BGProcessingTaskRequest(identifier: self.backgroundName)
              request.requiresNetworkConnectivity = true
              try BGTaskScheduler.shared.submit(request)
              os_log("Scheduling background exposure check", log: OSLog.setup, type: .debug)
          } catch {
              os_log("An error occurred scheduling background task, %@", log: OSLog.setup, type: .error, error.localizedDescription)
              Storage.shared.updateRunData(context, "An error occurred scheduling the background task, \(error.localizedDescription)")
          }
      } else if ExposureNotificationModule.ENManagerIsAvailable() {
        ExposureManager.shared.launchBackgroundiOS12()
      }
    }
    
}

extension Notification.Name {
  static var onStatusChanged: Notification.Name {
    return .init(rawValue: "ExposureProcessor.onStatusChanged")
  }
}
