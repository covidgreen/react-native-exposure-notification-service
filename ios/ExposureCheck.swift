import Foundation
import os.log
import ExposureNotification
import SSZipArchive
import Alamofire

@available(iOS 12.5, *)
class ExposureCheck: AsyncOperation {
    public static let REPEAT_NOTIFICATION_ID = "repeatExposure"
    public static let INITIAL_NOTIFICATION_ID = "exposure"
      
    public enum endPoints {
        case metrics
        case exposures
        case dataFiles
        case callback
        case settings
        case refresh
    }
  
    public struct Thresholds {
      let thresholdWeightings: [Double]
      let timeThreshold: Int
      let numFiles: Int
      let contiguousMode: Bool
    }
    
    private struct CodableSettings: Decodable {
      let exposureConfig: String
    }
  
    private struct CodableExposureConfiguration: Codable {
        let minimumRiskScore: ENRiskScore
        let attenuationLevelValues: [ENRiskLevelValue]
        let attenuationWeight: Double
        let daysSinceLastExposureLevelValues: [ENRiskLevelValue]
        let daysSinceLastExposureWeight: Double
        let durationLevelValues: [ENRiskLevelValue]
        let durationWeight: Double
        let transmissionRiskLevelValues: [ENRiskLevelValue]
        let transmissionRiskWeight: Double
        let durationAtAttenuationThresholds: [Int]
        let thresholdWeightings: [Double]
        let timeThreshold: Int
        let numFilesiOS: Int?
        let immediateDurationWeight: Double?
        let nearDurationWeight: Double?
        let mediumDurationWeight: Double?
        let otherDurationWeight: Double?
        let infectiousnessStandardWeight: Double?
        let infectiousnessHighWeight: Double?
        let reportTypeConfirmedTestWeight: Double?
        let reportTypeConfirmedClinicalDiagnosisWeight: Double?
        let reportTypeSelfReportedWeight: Double?
        let reportTypeRecursiveWeight: Double?
        let reportTypeNoneMap: UInt32?
        let daysSinceLastExposureThreshold: Int?
        let minimumRiskScoreFullRange: Double?
        let infectiousnessForDaysSinceOnsetOfSymptoms: [Int]?
        let attenuationDurationThresholds: [Int]?
        let v2Mode: Bool?
        let contiguousMode: Bool?
    }
  
    private struct CodableExposureFiles: Codable {
        let id: Int
        let path: String
    }

    private struct FileDownloadTracking {
        let remoteURL: URL
        let localURL: URL
        let unzipPath: URL
    }

    private func serverURL(_ url: endPoints) -> String {
      switch url {
        case .metrics:
          return self.configData.serverURL + "/metrics"
        case .exposures:
            switch self.configData.keyServerType {
            case .GoogleRefServer:
                return self.configData.keyServerUrl + "/v1/index.txt"
            default:
                return self.configData.serverURL + "/exposures"
            }
                
        case .dataFiles:
            switch self.configData.keyServerType {
            case .GoogleRefServer:
                return self.configData.keyServerUrl + "/"
            default:
                return self.configData.serverURL + "/data/"
            }
        case .callback:
          return self.configData.serverURL + "/callback"
        case .settings:
          return self.configData.serverURL + "/settings/exposures"
        case .refresh:
          return self.configData.serverURL + "/refresh"
      }
    }
  
    private let defaultSession = URLSession(configuration: .default)
    private var dataTask: URLSessionDataTask?
    private var configData: Storage.Config!
    private var skipTimeCheck: Bool = false
    private var simulateExposureOnly: Bool = false
    private var simulateExposureDays: Int = 2
    private let storageContext = Storage.PersistentContainer.shared.newBackgroundContext()
    private var sessionManager: Session!
    
    init(_ skipTimeCheck: Bool, _ simulateExposureOnly: Bool, _ simulateDays: Int) {
        super.init()
    
        self.skipTimeCheck = skipTimeCheck
        self.simulateExposureOnly = simulateExposureOnly
        self.simulateExposureDays = simulateDays
    }
    
    override func cancel() {
        super.cancel()
    }
       
    override func main() {
       self.configData = Storage.shared.readSettings(self.storageContext)
       guard self.configData != nil else {
          self.finishNoProcessing("No config set so can't proceed with checking exposures", false)
          return
       }
 
       let serverDomain: String = Storage.getDomain(self.configData.serverURL)
       let keyServerDomain: String = Storage.getDomain(self.configData.keyServerUrl)
       var manager: ServerTrustManager
       if (self.configData.keyServerType != Storage.KeyServerType.NearForm && serverDomain != keyServerDomain) {
          manager = ServerTrustManager(evaluators: [serverDomain: PinnedCertificatesTrustEvaluator(), keyServerDomain: DefaultTrustEvaluator()])
       } else {
          manager = ServerTrustManager(evaluators: [serverDomain: PinnedCertificatesTrustEvaluator()])
       }
       self.sessionManager = Session(interceptor: RequestInterceptor(self.configData, self.serverURL(.refresh)), serverTrustManager: manager)
        
       os_log("Running with params %@, %@, %@", log: OSLog.checkExposure, type: .debug, self.configData.serverURL, self.configData.authToken, self.configData.refreshToken)
               
       /*guard (self.configData.lastRunDate!.addingTimeInterval(TimeInterval(self.configData.checkExposureInterval * 60)) < Date() || self.skipTimeCheck) else {
            let formatter = DateFormatter()
            formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
            self.finishNoProcessing("Check was run at \(formatter.string(from: self.configData.lastRunDate!)), interval is \(self.configData.checkExposureInterval), its too soon to check again", false)
            return
       }*/

       guard ENManager.authorizationStatus == .authorized else {
            self.finishNoProcessing("Not authorised so can't run exposure checks")
            return
       }

       guard !ExposureManager.shared.isPaused() else {
            self.finishNoProcessing("ENS is paused", false)
            return
       }
        
       // clean out any expired exposures
       Storage.shared.deleteOldExposures(self.configData.storeExposuresFor)
        
       if (self.simulateExposureOnly) {
           os_log("Simulating exposure alert", log: OSLog.exposure, type: .debug)
           simulateExposureEvent(self.simulateExposureDays)
           return
       }
        
       os_log("Starting exposure checking", log: OSLog.exposure, type: .debug)

       self.getExposureConfiguration { result in
            switch result {
              case let .failure(error):
                 self.finishNoProcessing("Failed to retrieve settings, \(error.localizedDescription)")
              case let .success((configuration, thresholds, v2Mode)):
                self.processExposureFiles(configuration, thresholds, v2Mode)
            }
       }
    }
    
    private func processExposureFiles(_ configuration: ENExposureConfiguration, _ thresholds: Thresholds, _ v2Mode: Bool) {
        
        self.getExposureFiles(thresholds.numFiles) { fileResult in
            switch fileResult {
                case let .success((urls, lastIndex)):
                    if urls.count > 0 {
                      self.processExposures(urls, lastIndex, configuration, thresholds, v2Mode)
                    }
                    else {
                      self.finishNoProcessing("No files available to process", false)
                    }
                case let .failure(error):
                    self.finishNoProcessing("Failed to retrieve exposure files for processing, \(error.localizedDescription)")
            }
        }
    }
    
    private func simulateExposureEvent(_ simulateDays: Int) {
        let calendar = Calendar.current
        let dateToday = calendar.startOfDay(for: Date())
        let contactDate = calendar.date(byAdding: .day, value: (0 - simulateDays), to: dateToday)

        var info = ExposureProcessor.ExposureInfo(daysSinceLastExposure: simulateDays, attenuationDurations: [30, 30, 30], matchedKeyCount: 1,  maxRiskScore: 10, exposureDate: Date(), exposureContactDate: contactDate!)
      
        info.maximumRiskScoreFullRange = 10
        info.riskScoreSumFullRange = 10
        info.customAttenuationDurations = [30, 30, 30]
                
        let lastId = self.configData.lastExposureIndex!
        return self.finishProcessing(.success((info, lastId)))
    }

    private func getDomain(_ url: String) -> String {
        let url = URL(string: url)
        return url!.host!
    }
    
    private func finishNoProcessing(_ message: String, _ log: Bool = true) {
      os_log("%@", log: OSLog.checkExposure, type: .info, message)

      Storage.shared.updateRunData(self.storageContext, message)

      if (log) {
        let payload:[String: Any] = [
          "description": message
        ]
        self.saveMetric(event: "LOG_ERROR", payload: payload) { _ in
          self.trackDailyMetrics()
        }
      } else {
        self.trackDailyMetrics()
      }
    }
  
    private func processExposures(_ files: [URL], _ lastIndex: Int, _ configuration: ENExposureConfiguration, _ thresholds: Thresholds, _ v2Mode: Bool) {
       
        os_log("Checking exposures against %d files", log: OSLog.checkExposure, type: .info, files.count)
        
        ExposureManager.shared.manager.detectExposures(configuration: configuration, diagnosisKeyURLs: files) { summary, error in
           
           self.deleteLocalFiles(files)
           if let error = error {
              return self.finishProcessing(.failure(self.wrapError("Failure in detectExposures", error)))
           }
            
           guard let summaryData = summary else {
              os_log("No summary data returned", log: OSLog.checkExposure, type: .debug)
              return self.finishProcessing(.success((nil, lastIndex)))
           }
        
           if ExposureProcessor.shared.getSupportedExposureNotificationsVersion() == .version2, v2Mode {
                RiskCalculationV2.calculateRisk(summaryData, configuration, thresholds) { result in
                    switch result {
                        case let .success(exposureInfo):
                            return self.finishProcessing(.success((exposureInfo, lastIndex)))
                        case let .failure(error):
                            self.finishProcessing((.failure(error)))
                    }
                 }
           } else if #available(iOS 13.5, *) {
                 RiskCalculationV1.calculateRisk(summaryData, thresholds) { result in
                    switch result {
                        case let .success(exposureInfo):
                            return self.finishProcessing(.success((exposureInfo, lastIndex)))
                    case let .failure(error):
                        self.finishProcessing((.failure(error)))
                    }

                 }
           } else {
               os_log("Config not set for v2, iOS12 only supports V2", log: OSLog.checkExposure, type: .info)
               return self.finishProcessing(.success((nil, lastIndex)))
           }
        }
    }

    
    private func wrapError(_ description: String, _ error: Error?) -> Error {
      
      if let err = error {
        let nsErr = err as NSError
        return NSError(domain: nsErr.domain, code: nsErr.code, userInfo: [NSLocalizedDescriptionKey: "\(description), \(nsErr.localizedDescription)"])
      } else {
        return NSError(domain: "exposurecheck", code: 500, userInfo: [NSLocalizedDescriptionKey: "\(description)"])
      }
    }
  
    private func deleteLocalFiles(_ files:[URL]) {
      for localURL in files {
         try? FileManager.default.removeItem(at: localURL)
      }
    }
  

    private func finishProcessing(_ result: Result<(ExposureProcessor.ExposureInfo?, Int), Error>) {
      switch result {
      case let .success((exposureData, lastFileIndex)):
          os_log("We successfully completed checks", log: OSLog.checkExposure, type: .info)
          Storage.shared.updateRunData(self.storageContext, "", lastFileIndex)
  
        
          if let exposure = exposureData  {
             let existingExposures = Storage.shared.getExposures(self.configData.storeExposuresFor).reversed()
            
             if (existingExposures.count > 0 && existingExposures.first!.exposureContactDate < exposure.exposureContactDate) || existingExposures.count == 0 {
                os_log("Detected exposure event", log: OSLog.checkExposure, type: .info)
                Storage.shared.saveExposureDetails(self.storageContext, exposure)
            
                self.triggerUserNotification(exposure) { _ in
                    self.trackDailyMetrics()
                }
             } else {
                os_log("Exposure detected but not more recent then currently recorded exposures", log: OSLog.checkExposure, type: .info)
                self.trackDailyMetrics()
             }
          } else {
            os_log("No exposure detected", log: OSLog.checkExposure, type: .info)
            self.trackDailyMetrics()
          }
      case let .failure(error):
          return self.finishNoProcessing("Failure processing exposure keys, \(error.localizedDescription)");
      }
        
    }
  
    private func trackDailyMetrics() {
      guard !self.isCancelled else {
        return self.cancelProcessing()
      }
      guard self.configData != nil else {
        // don't track daily trace if config not setup
        return self.finish()
      }
        
      let calendar = Calendar.current
      let checkDate: Date = self.configData.dailyTrace ?? calendar.date(byAdding: .day, value: -1, to: Date())!
        
      if (!self.isCancelled && !calendar.isDate(Date(), inSameDayAs: checkDate)) {
         Storage.shared.updateDailyTrace(self.storageContext, date: Date())
         self.saveMetric(event: "DAILY_ACTIVE_TRACE") { _ in
           self.finish()
         }
      } else {
        self.finish()
      }
      
    }

    private func getExposureFiles(_ numFiles: Int, completion: @escaping  (Result<([URL], Int), Error>) -> Void) {
      guard !self.isCancelled else {
        return self.cancelProcessing()
      }
      os_log("Key server type set to %@", log: OSLog.checkExposure, type: .debug, self.configData.keyServerType.rawValue)
      switch self.configData.keyServerType {
      case .GoogleRefServer:
        getGoogleExposureFiles(numFiles, completion)
      default:
        getNearFormExposureFiles(numFiles, completion)
      }
    }

    private func getNearFormExposureFiles(_ numFiles: Int, _ completion: @escaping  (Result<([URL], Int), Error>) -> Void) {
      guard !self.isCancelled else {
        return self.cancelProcessing()
      }

      let lastId = self.configData.lastExposureIndex ?? 0
      let version = Storage.shared.version()["display"] ?? "unknown"
      os_log("Checking for exposures against nearform server since %d, limit %d", log: OSLog.checkExposure, type: .debug, lastId, numFiles)
        self.sessionManager.request(self.serverURL(.exposures), parameters: ["since": lastId, "limit": numFiles, "version": version, "os": "ios"])
      .validate()
      .responseDecodable(of: [CodableExposureFiles].self) { response in
        switch response.result {
          case .success:
            self.processFileLinks(response.value!, completion)
          case let .failure(error):
              os_log("Failure occurred while reading exposure files %@", log: OSLog.checkExposure, type: .error, error.localizedDescription)
              completion(.failure(error))
        }
      }
    }

    private func getGoogleExposureFiles(_ numFiles: Int, _ completion: @escaping  (Result<([URL], Int), Error>) -> Void) {
      guard !self.isCancelled else {
        return self.cancelProcessing()
      }

      os_log("Checking for exposures against google server type, %@", log: OSLog.checkExposure, type: .debug, self.serverURL(.exposures))
      self.sessionManager.request(self.serverURL(.exposures))
      .validate()
        .responseString { response in
          switch response.result {
          case .success:
            let files = self.findFilesToProcess(numFiles, response.value!)

            self.processFileLinks(files, completion)
          case let .failure(error):
              os_log("Failure occurred while reading exposure files %@", log: OSLog.checkExposure, type: .error, error.localizedDescription)
              completion(.failure(error))
        }
      }
    }

    private func processFileLinks(_ files: [CodableExposureFiles], _ completion: @escaping  (Result<([URL], Int), Error>) -> Void) {
        var lastId = self.configData.lastExposureIndex ?? 0
        if files.count > 0 {
          os_log("Files available to process, %d", log: OSLog.checkExposure, type: .debug, files.count)
          lastId = files.last!.id
          let urls: [URL] = files.map{ url in
            lastId = max(url.id, lastId)
            return URL(string: self.serverURL(.dataFiles) + url.path)!
          }
        
          self.downloadFilesForProcessing(urls) { result in
            switch result {
            case let .success(localURLS):
              completion(.success((localURLS, lastId)))
            case let .failure(error):
              completion(.failure(error))
            }
          }
        } else {
            /// no keys to be processed
            os_log("No key files returned from server, last file index: %d", log: OSLog.checkExposure, type: .info, lastId)
            completion(.success(([], lastId)))
        }
    }
    
    private func findFilesToProcess(_ numFiles:Int, _ fileList: String) -> [CodableExposureFiles] {
        /// fileList if of format
        /*
         v1/1597846020-1597846080-00001.zip
         v1/1597847700-1597847760-00001.zip
         v1/1597848660-1597848720-00001.zip
        */
        let listData = fileList.split(separator: "\n").map { String($0) }
        
        var filesToProcess: [CodableExposureFiles] = []
        for key in listData {
            let fileURL = URL(fileURLWithPath: key)
            let fileName = fileURL.deletingPathExtension().lastPathComponent
            let idVal = self.parseGoogleFileName(fileName)
            os_log("Parsing google file entry item %@, %@, %d", log: OSLog.checkExposure, type: .debug, String(key), fileName, idVal)

            if idVal > -1  {
                let fileItem = CodableExposureFiles(id: idVal, path: String(key))
                filesToProcess.append(fileItem)
            }
        }
        if (self.configData.lastExposureIndex <= 0) {
            return Array(filesToProcess.suffix(numFiles))
        } else {
            return Array(filesToProcess.prefix(numFiles))
        }
    }
    
    private func parseGoogleFileName(_ key: String) -> Int {
        /// key format is 1598375820-1598375880-00001
        /// start time - end time - ??
        /// we look for any start times > than the last end time we stored
        /// return the end time as the last id
        let keyParts = key.split(separator: "-").map { String($0) }
        let lastId = self.configData.lastExposureIndex ?? 0
        
        if (Int(keyParts[0]) ?? 0 >= lastId) {
            return Int(keyParts[1]) ?? 0
        } else {
            return -1
        }
        
    }
    
    private func downloadFilesForProcessing(_ files: [URL], _ completion: @escaping (Result<[URL], Error>) -> Void) {
      
      guard !self.isCancelled else {
        return self.cancelProcessing()
      }
      
      var validFiles: [URL] = []
      var errorDetails: [Error] = []

      let taskGroup = DispatchGroup()
      let queue = DispatchQueue(label: "ENSWorkerQueuer")
        
      let downloadData: [FileDownloadTracking] = files.enumerated().compactMap { (index, file) in
        let local = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!.appendingPathComponent("diagnosisZip-\(index)")
        let unzipPath = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!.appendingPathComponent("diagnosisKeys-\(index)", isDirectory: true)
        return FileDownloadTracking(remoteURL: file, localURL: local, unzipPath: unzipPath)
      }
      
      downloadData.enumerated().forEach { (index, item) in
        taskGroup.enter()
        queue.async(group: taskGroup) {
            self.downloadURL(item.remoteURL, item.localURL) { result in
              switch result {
              case let .success(url):
                  if item.remoteURL.path.hasSuffix(".zip") {
                    do {
                        let extractedFiles = try self.unzipFile(index, url, item)
                        if extractedFiles.count > 0 {
                            os_log("Adding files to list, %d", log: OSLog.checkExposure, type: .debug, extractedFiles.count)
                            validFiles.append(contentsOf: extractedFiles)
                        }
                    } catch {
                       os_log("Error unzipping, %@", log: OSLog.checkExposure, type: .error, error.localizedDescription)
                        errorDetails.append(self.wrapError("Error unzipping file \(item.remoteURL.path)", error))
                    }
                    /// remove the zip
                    try? FileManager.default.removeItem(at: url)

                  } else {
                    /// not a zip, used during testing
                    validFiles.append(url)
                  }
                  
              case let .failure(error):
                try? FileManager.default.removeItem(at: item.localURL)
                
                os_log("Failed to download the file %@, %@", log: OSLog.checkExposure, type: .error, item.remoteURL.absoluteString, error.localizedDescription)
                errorDetails.append(self.wrapError("Failed to download the file \(item.remoteURL.path)", error))
                
            }
            taskGroup.leave()
          }
          
        }
      }
        
      //Notify when all task completed at main thread queue.
        taskGroup.notify(queue: queue) {
        // All tasks are done.
        if validFiles.count > 0 {
          completion(.success(validFiles))
        } else {
          let errorData = self.flattenError(errorDetails)
          completion(.failure(self.wrapError("Error downloading files: \(errorData)", nil)))
        }
      }
    }
  
    private func flattenError(_ errors: [Error]) -> String {
        var errText = ""
        
        for err in errors {
            errText = err.localizedDescription + "\n"
        }
        
        return errText
    }
    
    private func unzipFile(_ index: Int, _ url: URL, _ item: FileDownloadTracking) throws -> [URL] {
        var renamedFiles: [URL] = []
        
        try? FileManager.default.createDirectory(at: item.unzipPath, withIntermediateDirectories: false, attributes: nil)
        let success = SSZipArchive.unzipFile(atPath: url.path, toDestination: item.unzipPath.path, progressHandler: nil, completionHandler: downloadComplete)
        if success {
          let fileURLs = try FileManager.default.contentsOfDirectory(at: item.unzipPath, includingPropertiesForKeys: nil)
          for (file) in fileURLs.enumerated() {
            var renamePath: URL!
            if file.element.path.hasSuffix(".sig") {
              renamePath = item.unzipPath.appendingPathComponent("export\(index).sig")
            } else if file.element.path.hasSuffix(".bin") {
              renamePath = item.unzipPath.appendingPathComponent("export\(index).bin")
            }
            if renamePath != nil {
              try? FileManager.default.moveItem(at: file.element, to: renamePath)
              renamedFiles.append(renamePath)
            }
          }
        }
        return renamedFiles
    }
    
    private func downloadComplete(_ path: String, _ succeeded: Bool, _ error: Error?) {
      if  error != nil {
        os_log("Error unzipping keys file, %@", log: OSLog.checkExposure, type: .error, error!.localizedDescription)
      }
      os_log("Keys file successfully unzipped, %@, %d", log: OSLog.checkExposure, type: .debug, path, succeeded)
    }
    
    private func cancelProcessing() {
      Storage.shared.updateRunData(self.storageContext, "Background processing expiring, cancelling")
      self.finish()
    }
  
    private func downloadURL(_ fileURL: URL, _ destinationFileUrl: URL, _ completion: @escaping          (Result<URL, Error>) -> Void) {
      guard !self.isCancelled else {
        return self.cancelProcessing()
      }

      let destination: DownloadRequest.Destination = { _, _ in
          return (destinationFileUrl, [.removePreviousFile, .createIntermediateDirectories])
      }
      
      self.sessionManager.download(
        fileURL,
        method: .get,
        to: destination).downloadProgress(closure: { (progress) in
            /// progress closure
        })
        .validate()
        .response() { response in
          switch response.result {
          case .success:
            completion(.success(destinationFileUrl))
          case let .failure(error):
            os_log("Failed to download file successfully, %@, %@", log: OSLog.checkExposure, type: .error, fileURL.absoluteString, error.localizedDescription)
              completion(.failure(error))
          }
      }
    }
  
    private func getExposureConfiguration(_ completion: @escaping  (Result<(ENExposureConfiguration, Thresholds, Bool), Error>) -> Void) {
      guard !self.isCancelled else {
        return self.cancelProcessing()
      }
      let version = Storage.shared.version()["display"] ?? "unknown"
        self.sessionManager.request(self.serverURL(.settings), parameters: ["version": version, "os": "ios"])
          .validate()
          .responseDecodable(of: CodableSettings.self) { response in
          
           switch response.result {
           case .success:
              let exposureData = response.value!
              do {
                let codableExposureConfiguration = try JSONDecoder().decode(CodableExposureConfiguration.self, from: exposureData.exposureConfig.data(using: .utf8)!)
                let exposureConfiguration = ENExposureConfiguration()
                
                let thresholds = Thresholds(thresholdWeightings: codableExposureConfiguration.thresholdWeightings, timeThreshold: codableExposureConfiguration.timeThreshold, numFiles: codableExposureConfiguration.numFilesiOS ?? 6, contiguousMode: codableExposureConfiguration.contiguousMode ?? false)

                exposureConfiguration.minimumRiskScore = codableExposureConfiguration.minimumRiskScore
                exposureConfiguration.attenuationLevelValues = codableExposureConfiguration.attenuationLevelValues as [NSNumber]
                exposureConfiguration.attenuationWeight = codableExposureConfiguration.attenuationWeight
                exposureConfiguration.daysSinceLastExposureLevelValues = codableExposureConfiguration.daysSinceLastExposureLevelValues as [NSNumber]
                exposureConfiguration.daysSinceLastExposureWeight = codableExposureConfiguration.daysSinceLastExposureWeight
                exposureConfiguration.durationLevelValues = codableExposureConfiguration.durationLevelValues as [NSNumber]
                exposureConfiguration.durationWeight = codableExposureConfiguration.durationWeight
                exposureConfiguration.transmissionRiskLevelValues = codableExposureConfiguration.transmissionRiskLevelValues as [NSNumber]
                exposureConfiguration.transmissionRiskWeight = codableExposureConfiguration.transmissionRiskWeight
                
                let meta:[AnyHashable: Any] = [AnyHashable("attenuationDurationThresholds"): codableExposureConfiguration.durationAtAttenuationThresholds as [NSNumber]]
                exposureConfiguration.metadata = meta
                
                let v2Mode = codableExposureConfiguration.v2Mode ?? false
                if ExposureProcessor.shared.getSupportedExposureNotificationsVersion() == .version2, v2Mode == true {
                    exposureConfiguration.immediateDurationWeight =  codableExposureConfiguration.immediateDurationWeight ?? 100.0
                    exposureConfiguration.nearDurationWeight =  codableExposureConfiguration.nearDurationWeight ?? 100.0
                    exposureConfiguration.mediumDurationWeight =  codableExposureConfiguration.mediumDurationWeight ?? 100.0
                    exposureConfiguration.otherDurationWeight =  codableExposureConfiguration.otherDurationWeight ?? 100.0

                    exposureConfiguration.infectiousnessStandardWeight =  codableExposureConfiguration.infectiousnessStandardWeight ?? 100.0
                    exposureConfiguration.infectiousnessHighWeight =   codableExposureConfiguration.infectiousnessHighWeight ?? 100.0
                  
                    exposureConfiguration.reportTypeConfirmedTestWeight =  codableExposureConfiguration.reportTypeConfirmedTestWeight ?? 100.0
                    exposureConfiguration.reportTypeConfirmedClinicalDiagnosisWeight =  codableExposureConfiguration.reportTypeConfirmedClinicalDiagnosisWeight ?? 100.0
                    exposureConfiguration.reportTypeSelfReportedWeight =  codableExposureConfiguration.reportTypeSelfReportedWeight ?? 100.0
                    exposureConfiguration.reportTypeRecursiveWeight =  codableExposureConfiguration.reportTypeRecursiveWeight ?? 100.0

                    exposureConfiguration.daysSinceLastExposureThreshold =  codableExposureConfiguration.daysSinceLastExposureThreshold ?? 0

                    exposureConfiguration.minimumRiskScoreFullRange =  codableExposureConfiguration.minimumRiskScoreFullRange ?? 1

                    if let data = codableExposureConfiguration.attenuationDurationThresholds {
                        exposureConfiguration.attenuationDurationThresholds = data as [NSNumber]
                    }
                
                    if let data = codableExposureConfiguration.infectiousnessForDaysSinceOnsetOfSymptoms {
                        exposureConfiguration.infectiousnessForDaysSinceOnsetOfSymptoms = self.convertToMap(data)
                    }
                    
                    exposureConfiguration.reportTypeNoneMap = ENDiagnosisReportType(rawValue:  codableExposureConfiguration.reportTypeNoneMap ?? ENDiagnosisReportType.confirmedTest.rawValue) ?? ENDiagnosisReportType.confirmedTest
 
                }
                
                completion(.success((exposureConfiguration, thresholds, v2Mode)))
              } catch {
                os_log("Unable to decode settings data, %@", log: OSLog.checkExposure, type: .error, error.localizedDescription)
                completion(.failure(error))
              }
           case let .failure(error):
               os_log("Error occurred retrieveing settings data, %@", log: OSLog.checkExposure, type: .error, error.localizedDescription)

               completion(.failure(error))
           }
       }
    }
    
    private func convertToMap(_ daysSinceOnsetToInfectiousness: [Int]) -> [NSNumber : NSNumber] {
        var map: [Int: Int] = [:]
        var counter = 0
        
        for index in -14...14 {
            if counter < daysSinceOnsetToInfectiousness.count {
                map[index] = daysSinceOnsetToInfectiousness[counter]
            } else {
                map[index] = Int(ENInfectiousness.none.rawValue)
            }
            counter += 1
        }
        
        return map as [NSNumber : NSNumber]
    }

    private func triggerUserNotification(_ exposures: ExposureProcessor.ExposureInfo, _ completion: @escaping  (Result<Bool, Error>) -> Void) {
      let content = UNMutableNotificationContent()
      let calendar = Calendar.current
      let dateToday = calendar.startOfDay(for: Date())
    
      content.title = self.configData.notificationTitle
      content.body = self.configData.notificationDesc
      content.badge = 1
      content.sound = .default
              
      let initialRequest = UNNotificationRequest(identifier: ExposureCheck.INITIAL_NOTIFICATION_ID, content: content, trigger: nil)
      UNUserNotificationCenter.current().add(initialRequest) { error in
            DispatchQueue.main.async {
                if let error = error {
                  os_log("Notification error %@", log: OSLog.checkExposure, type: .error, error.localizedDescription)
                }
                if (self.configData.notificationRepeat > 0) {
                    let trigger = UNTimeIntervalNotificationTrigger(timeInterval: TimeInterval(self.configData.notificationRepeat * 60), repeats: true)
                    let request = UNNotificationRequest(identifier: ExposureCheck.REPEAT_NOTIFICATION_ID, content: content, trigger: trigger)
                    UNUserNotificationCenter.current().add(request) { error in
                        DispatchQueue.main.async {
                            if let error = error {
                              os_log("Repeat Notification error %@", log: OSLog.checkExposure, type: .error, error.localizedDescription)
                            }
                        }
                    }
                }
            }
      }
    
      var payload:[String: Any] = [
        "matchedKeys": exposures.matchedKeyCount,
        "attenuations": exposures.customAttenuationDurations ?? exposures.attenuationDurations,
        "maxRiskScore": exposures.maxRiskScore,
        "daysSinceExposure": exposures.daysSinceLastExposure,
        "simulate": simulateExposureOnly,
        "os": "ios",
        "version": Storage.shared.version()["display"] ?? "unknown"
      ]
        
      if let windowData = exposures.windows {
          let windowInfo = windowData.compactMap { window -> [String: Any]? in
           return ["date": Int64(window.date.timeIntervalSince1970 * 1000.0),
                   "calibrationConfidence": window.calibrationConfidence,
                   "diagnosisReportType": window.diagnosisReportType,
                   "infectiousness": window.infectiousness,
                   "buckets": window.scanData.buckets,
                   "weightedBuckets": window.scanData.weightedBuckets!,
                   "exceedsThreshold": window.scanData.exceedsThreshold
            ]
          }
          payload["windows"] = windowInfo
      }
      self.saveMetric(event: "CONTACT_NOTIFICATION", payload: payload) { _ in
        let lastExposure = calendar.date(byAdding: .day, value: (0 - exposures.daysSinceLastExposure), to: dateToday)
        self.triggerCallBack(lastExposure!, exposures.daysSinceLastExposure, payload, completion)
      }
    }
  
    private func triggerCallBack(_ lastExposure: Date, _ daysSinceExposure: Int, _ payload: [String: Any], _ completion: @escaping  (Result<Bool, Error>) -> Void) {
      guard !self.isCancelled else {
        return self.cancelProcessing()
      }
      
      guard let callbackNum = self.configData.callbackNumber, !callbackNum.isEmpty else {
        os_log("No callback number configured", log: OSLog.checkExposure, type: .info)
        return completion(.success(true))
      }
           
      let version = Storage.shared.version()["display"] ?? "unknown"
        self.sessionManager.request(self.serverURL(.callback), method: .post , parameters: ["mobile": callbackNum, "closeContactDate": Int64(lastExposure.timeIntervalSince1970 * 1000.0), "daysSinceExposure": daysSinceExposure, "payload": payload, "version": version, "os": "ios"], encoding: JSONEncoding.default)
        .validate()
        .response() { response in
          switch response.result {
          case .success:
            os_log("Request for callback sent", log: OSLog.checkExposure, type: .debug)
            completion(.success(true))
          case let .failure(error):
            os_log("Unable to send callback request, %@", log: OSLog.checkExposure, type: .error, error.localizedDescription)
            completion(.failure(error))
        }
      }
    
    }

  private func saveMetric(event: String, completion: @escaping  (Result<Bool, Error>) -> Void) {
    self.saveMetric(event: event, payload: nil, completion: completion)
  }
  
  private func saveMetric(event: String, payload: [String:Any]?, completion: @escaping  (Result<Bool, Error>) -> Void) {
    guard !self.isCancelled else {
      return self.cancelProcessing()
    }
    guard self.configData != nil else {
      // don'trun if config not setup
      return completion(.success(true))
    }
    
    guard self.sessionManager != nil else {
      // don't run if session manager not setup
      return completion(.success(true))
    }
    
    if (!self.configData.analyticsOptin) {
      os_log("Metric opt out", log: OSLog.exposure, type: .error)
      return completion(.success(true))
    }
    os_log("Sending metric, %@", log:OSLog.checkExposure, type: .info, event)
    var params: Parameters = [:]
    params["os"] = "ios"
    params["event"] = event
    params["version"] = Storage.shared.version()["display"]
    if let packet = payload {
        params["payload"] = packet
    }
    self.sessionManager.request(self.serverURL(.metrics), method: .post , parameters: params, encoding: JSONEncoding.default)
      .validate()
      .response() { response in
        switch response.result {
          case .success:
            os_log("Metric sent, %@", log: OSLog.checkExposure, type: .debug, event)
            completion(.success(true))
          case let .failure(error):
            os_log("Unable to send metric, %@", log: OSLog.checkExposure, type: .error, error.localizedDescription)
            completion(.failure(error))
        }
    }
  }
}

class RequestInterceptor: Alamofire.RequestInterceptor {
    private struct CodableToken: Codable {
      let token: String
    }
    private var config: Storage.Config
    private var refreshURL: String
  
    init(_ config: Storage.Config, _ refreshURL: String ) {
        self.config = config
        self.refreshURL = refreshURL
    }
    
    func adapt(_ urlRequest: URLRequest, for session: Session, completion: @escaping (Result<URLRequest, Error>) -> Void) {

        var urlRequest = urlRequest

        let nfServer = Storage.getDomain(self.config.serverURL)
        if (urlRequest.url?.host == nfServer) {
            /// Set the Authorization header value using the access token, only on nearform server requests
            urlRequest.setValue("Bearer " + self.config.authToken, forHTTPHeaderField: "Authorization")
        }
        
        completion(.success(urlRequest))
    }

    func retry(_ request: Request, for session: Session, dueTo error: Error, completion: @escaping (RetryResult) -> Void) {
        guard let response = request.task?.response as? HTTPURLResponse, response.statusCode == 401, request.retryCount == 0 else {
            /// The request did not fail due to a 401 Unauthorized response.
            /// Return the original error and don't retry the request.
            return completion(.doNotRetryWithError(error))
        }

        refreshToken { result in
            switch result {
            case .success(_):
                /// After updating the token we can  retry the original request.
                completion(.retry)
            case .failure(let error):
                completion(.doNotRetryWithError(error))
            }
        }
    }
  
    private func refreshToken(_ completion: @escaping (Result<Bool, Error>) -> Void) {
      let headers: HTTPHeaders = [
        .accept("application/json"),
        .authorization(bearerToken: self.config.refreshToken)
      ]
  
      AF.request(self.refreshURL, method: .post, headers: headers)
        .validate()
        .responseDecodable(of: CodableToken.self) { response in
        switch response.result {
         case .success:
             self.config.authToken = response.value!.token
             Storage.shared.updateAuthToken(self.config.authToken)
             completion(.success(true))
         case let .failure(error):
             completion(.failure(error))
       }
      }
    }
}
