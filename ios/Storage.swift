
import Foundation
import CoreData
import ExposureNotification
import os.log
import KeychainSwift

public class Storage {
    public struct Config: Codable {
        let refreshToken: String
        let serverURL: String
        let checkExposureInterval: Int
        let storeExposuresFor: Int
        let notificationTitle: String
        let notificationDesc: String
        var authToken: String
        var version: String
        var fileLimit: Int
        var datesLastRan: String!
        var lastExposureIndex: Int!
        var notificationRaised: Bool!
        var callbackNumber: String!
        var analyticsOptin: Bool!
        var dailyTrace: Date?
        var lastError: String?
        var lastRunDate: Date?
    }
    
    private struct CodableCallback: Decodable {
        let code: String
        let number: String
    }
    
    public static let shared = Storage()
    
    public func readSettings(_ context: NSManagedObjectContext) -> Config! {
       var settings: Config!
       
       let fetchRequest =
         NSFetchRequest<NSManagedObject>(entityName: "Settings")
       do {
         let data = try context.fetch(fetchRequest)
         if data.count > 0 {
            let keychain = KeychainSwift()
          
            let callbackData = keychain.get("cti.callBack") ?? "{\"code\":\"\",\"number\":\"\"}"
            let callBack = try JSONDecoder().decode(CodableCallback.self, from: callbackData.data(using: .utf8)!)
            
            var callbackNum = keychain.get("nm:callbackNumber") ?? ""
            if callbackNum.isEmpty {
                callbackNum = "\(callBack.code)\(callBack.number)"
            }
            let authToken = keychain.get("nm:authToken") ?? ""
            let refreshToken = keychain.get("nm:refreshToken") ?? ""
            let defaultDate = Date().addingTimeInterval(-1*24*60*60)
            
            
            settings = Config(
              refreshToken:refreshToken,
              serverURL: data[0].value(forKey: "serverURL") as! String,
              checkExposureInterval: data[0].value(forKey: "checkExposureInterval") as! Int,
              storeExposuresFor: data[0].value(forKey: "storeExposuresFor") as! Int,
              notificationTitle: data[0].value(forKey: "notificationTitle") as! String,
              notificationDesc: data[0].value(forKey: "notificationDesc") as! String,
              authToken: authToken,
              version: data[0].value(forKey: "version") as! String,
              fileLimit: data[0].value(forKey: "fileLimit") as! Int,
              datesLastRan: data[0].value(forKey: "datesLastRan") as? String ?? "",
              lastExposureIndex: data[0].value(forKey: "lastIndex") as? Int,
              notificationRaised: data[0].value(forKey: "notificationRaised") as? Bool,
              callbackNumber: callbackNum,
              analyticsOptin: data[0].value(forKey: "analyticsOptin") as? Bool,
              dailyTrace: data[0].value(forKey: "dailyTrace") as? Date,
              lastError: data[0].value(forKey: "errorDetails") as? String,
              lastRunDate: data[0].value(forKey: "lastRunDate") as? Date ?? defaultDate
            )
         }
       } catch  {
         os_log("Could not retrieve settings: %@", log: OSLog.storage, type: .error, error.localizedDescription)
       }
       os_log("Fetching settings from store", log: OSLog.storage, type: .debug)

       return settings
    }

    public func updateRunData(_ context: NSManagedObjectContext, _ errorDesc: String) {
      self.updateRunData(context, errorDesc, nil)
    }
  
    public func updateRunData(_ context: NSManagedObjectContext, _ errorDesc: String, _ lastIndex: Int?) {
        let currentData = self.readSettings(context)
      
        let fetchRequest =
          NSFetchRequest<NSManagedObject>(entityName: "Settings")

        do {
            let settingsResult = try context.fetch(fetchRequest)

            if settingsResult.count > 0 {
              let managedObject = settingsResult[0]
              let lastRanDates = currentData?.datesLastRan ?? ""
              
              let runDate = String(Int64(Date().timeIntervalSince1970 * 1000.0))
              var runDates = lastRanDates.components(separatedBy: ",")
              runDates.append(runDate)
              managedObject.setValue(runDates.suffix(10).joined(separator: ","), forKey: "datesLastRan")
              if let index = lastIndex {
                managedObject.setValue(index, forKey: "lastIndex")
              }
              managedObject.setValue(errorDesc, forKey: "errorDetails")
              managedObject.setValue(Date(), forKey: "lastRunDate")
              
              try context.save()
            } else {
                os_log("No settings have been stored, can't update", log: OSLog.storage, type: .debug)
            }
            
        } catch {
          os_log("Could not update last run settings. %@", log: OSLog.storage, type: .error, error.localizedDescription)
        }
        os_log("Last run settings stored", log: OSLog.storage, type: .debug)
    }
  
    public func updateDailyTrace(_ context: NSManagedObjectContext, date: Date) {
      let fetchRequest =
      NSFetchRequest<NSManagedObject>(entityName: "Settings")

      do {
          let settingsResult = try context.fetch(fetchRequest)

          if settingsResult.count > 0 {
            let managedObject = settingsResult[0]
            managedObject.setValue(date, forKey: "dailyTrace")
            try context.save()
          } else {
              os_log("No settings have been stored, can't update", log: OSLog.storage, type: .debug)
          }
      } catch {
        os_log("Could not update daily trace settings. %@", log: OSLog.storage, type: .error, error.localizedDescription)
      }
      os_log("Daily Trace settings stored", log: OSLog.storage, type: .debug)
    }

    public func flagNotificationRaised(_ context: NSManagedObjectContext) {
      let fetchRequest =
      NSFetchRequest<NSManagedObject>(entityName: "Settings")

      do {
          let settingsResult = try context.fetch(fetchRequest)

          if settingsResult.count > 0 {
            let managedObject = settingsResult[0]

            managedObject.setValue(true, forKey: "notificationRaised")
            
            try context.save()
          } else {
              os_log("No settings have been stored, can't flag as notified", log: OSLog.storage, type: .debug)
          }
          
      } catch {
        os_log("Could not flag as notified. %@", log: OSLog.storage, type: .error, error.localizedDescription)
      }
      os_log("Flagged that user has been notified", log: OSLog.storage, type: .debug)

    }

    public func updateAppSettings(_ config: Config) {
        
       let context = PersistentContainer.shared.newBackgroundContext()

       var managedObject: NSManagedObject
       let fetchRequest =
         NSFetchRequest<NSManagedObject>(entityName: "Settings")
       
       do {
         let settingsResult = try context.fetch(fetchRequest)

         if (settingsResult.count > 0) {
           os_log("Updating stored settings", log: OSLog.storage, type: .debug)
           
           managedObject = settingsResult[0]
         } else {
           os_log("Creating settings entity for first time", log: OSLog.storage, type: .debug)
           
           let entity =
               NSEntityDescription.entity(forEntityName: "Settings",
                                          in: context)!
           managedObject = NSManagedObject(entity: entity,
                                             insertInto: context)
         }
         let keychain = KeychainSwift()
         
         keychain.set(config.callbackNumber, forKey: "nm:callbackNumber", withAccess: .accessibleAfterFirstUnlock)
         keychain.set(config.authToken, forKey: "nm:authToken", withAccess: .accessibleAfterFirstUnlock)
         keychain.set(config.refreshToken, forKey: "nm:refreshToken", withAccess: .accessibleAfterFirstUnlock)
        
         managedObject.setValue(config.serverURL, forKey: "serverURL")
         managedObject.setValue(config.checkExposureInterval, forKey: "checkExposureInterval")
         managedObject.setValue(config.storeExposuresFor, forKey: "storeExposuresFor")
         managedObject.setValue(config.notificationTitle, forKey: "notificationTitle")
         managedObject.setValue(config.notificationDesc, forKey: "notificationDesc")
         managedObject.setValue(config.analyticsOptin, forKey: "analyticsOptin")
         managedObject.setValue(config.version, forKey: "version")
         managedObject.setValue(config.fileLimit, forKey: "fileLimit")

         try context.save()
       } catch {
         os_log("Could not create/update settings. %@", log: OSLog.storage, type: .error, error.localizedDescription)
       }
       os_log("Settings stored", log: OSLog.storage, type: .debug)

    }
    
    public func deleteData(_ exposuresOnly: Bool) {
      let context = PersistentContainer.shared.newBackgroundContext()
      var fetchRequest = NSFetchRequest<NSFetchRequestResult>(entityName: "Exposures")
      var deleteRequest = NSBatchDeleteRequest(fetchRequest: fetchRequest)
      
      do {
        try context.execute(deleteRequest)
      } catch let error as NSError {
        os_log("Error deleting the stored exposures: %@", log: OSLog.storage, type: .error, error.localizedDescription)
      }
  
      if exposuresOnly {
        os_log("Exposure details cleared", log: OSLog.storage, type: .info)
        return
      }
    
      fetchRequest = NSFetchRequest<NSFetchRequestResult>(entityName: "Settings")
      deleteRequest = NSBatchDeleteRequest(fetchRequest: fetchRequest)
      let keychain = KeychainSwift()
      keychain.delete("nm:authToken")
      keychain.delete("nm:refreshToken")
      keychain.delete("nm:callbackNumber")
      do {
        try context.execute(deleteRequest)
      } catch let error as NSError {
        os_log("Error deleting the stored settings: %@", log: OSLog.storage, type: .error, error.localizedDescription)
      }

      os_log("All stored details cleared", log: OSLog.storage, type: .info)
    }
    
    @available(iOS 13.5, *)
    public func saveExposureDetails(_ context: NSManagedObjectContext, _ exposureInfo: ExposureProcessor.ExposureInfo) {
      var managedObject: NSManagedObject
      
      do {
        os_log("Creating exposure entry", log: OSLog.storage, type: .debug)
          
        let entity =
              NSEntityDescription.entity(forEntityName: "Exposures",
                                         in: context)!
        managedObject = NSManagedObject(entity: entity,
                                            insertInto: context)
        let attenuations = exposureInfo.attenuationDurations.map { String($0) }
        let customAttenuations = exposureInfo.customAttenuationDurations.map { String($0) }
        managedObject.setValue(exposureInfo.exposureDate, forKey: "exposureDate")
        managedObject.setValue(exposureInfo.daysSinceLastExposure, forKey: "daysSinceExposure")
        managedObject.setValue(exposureInfo.matchedKeyCount, forKey: "matchedKeys")
        managedObject.setValue(exposureInfo.maxRiskScore, forKey: "riskScore")
        managedObject.setValue(exposureInfo.maximumRiskScoreFullRange, forKey: "maximumRiskScoreFullRange")
        managedObject.setValue(exposureInfo.riskScoreSumFullRange, forKey: "riskScoreSumFullRange")
        managedObject.setValue(customAttenuations.joined(separator: ","), forKey: "customAttenuationDurations")
        managedObject.setValue(attenuations.joined(separator: ","), forKey: "attenuations")
        
        let encoder = JSONEncoder()
        
        if exposureInfo.details != nil {
          if let jsonData = try? encoder.encode(exposureInfo.details) {
            let coded = String(data: jsonData, encoding: .utf8)
            managedObject.setValue(coded, forKey: "exposureDetails")
          }
        }
        try context.save()
      } catch {
        os_log("Could not create exposure. %@", log: OSLog.storage, type: .error, error.localizedDescription)
      }
      os_log("Exposure data stored", log: OSLog.storage, type: .debug)

    }
  
    @available(iOS 13.5, *)
    public func getExposures(_ storeExposuresFor: Int) -> [ExposureProcessor.ExposureInfo] {
      let context = PersistentContainer.shared.newBackgroundContext()
      
      var exposures: [ExposureProcessor.ExposureInfo] = []
      let calendar = Calendar.current
      let dateToday = calendar.startOfDay(for: Date())
      let oldestAllowed = calendar.date(byAdding: .day, value: (0 - storeExposuresFor), to: dateToday)
      
      let fetchRequest =
        NSFetchRequest<NSManagedObject>(entityName: "Exposures")
      fetchRequest.predicate = NSPredicate(format: "exposureDate >= %@", oldestAllowed! as CVarArg)
      do {
        let data = try context.fetch(fetchRequest)
        
        exposures = data.map{exposure in
          let attenuationData = exposure.value(forKey: "attenuations") as! String
          let attenuations = attenuationData.split(separator: ",").map { Int($0) ?? 0 }
          let customAttenuationData = exposure.value(forKey: "customAttenuationDurations") as? String ?? ""
          let customAttenuations = customAttenuationData.split(separator: ",").map { Int($0) ?? 0 }

          var info = ExposureProcessor.ExposureInfo(daysSinceLastExposure: exposure.value(forKey: "daysSinceExposure") as! Int, attenuationDurations: attenuations, matchedKeyCount: exposure.value(forKey: "matchedKeys") as! Int, maxRiskScore: exposure.value(forKey: "riskScore") as! Int, exposureDate: exposure.value(forKey: "exposureDate") as! Date,
              maximumRiskScoreFullRange: exposure.value(forKey: "maximumRiskScoreFullRange") as? Int ?? 0,
              riskScoreSumFullRange: exposure.value(forKey: "riskScoreSumFullRange") as? Int ?? 0,
              customAttenuationDurations: customAttenuations)
          
          if exposure.value(forKey: "exposureDetails") != nil {
            let details = exposure.value(forKey: "exposureDetails") as! String
            let decoder = JSONDecoder()
            let data = try? decoder.decode([ExposureProcessor.ExposureDetails].self, from: details.data(using: .utf8)!)
            info.details = data
          }
          return info
        }
      } catch  {
        os_log("Could not retrieve settings: %@", log: OSLog.storage, type: .error, error.localizedDescription)
      }
      os_log("Fetching settings from store", log: OSLog.storage, type: .debug)

      return exposures
    }
  
    /*private lazy var persistentContainer: NSPersistentContainer? = {
        let modelURL = Bundle(for: type(of: self)).url(forResource: "ExposureNotification", withExtension: "momd")!
        let managedObjectModel = NSManagedObjectModel(contentsOf: modelURL)!
        let container = NSPersistentContainer(name: "ExposureNotification", managedObjectModel: managedObjectModel)
        container.loadPersistentStores(completionHandler: { (storeDescription, error) in
            if let error = error {
                fatalError("Unresolved error \(error)")
            }
            
            os_log("Successfully loaded persistent store at: %@", log: OSLog.storage, type: .debug, storeDescription.url?.description ?? "nil")
        })
        container.viewContext.automaticallyMergesChangesFromParent = true
        container.viewContext.mergePolicy = NSMergePolicy(merge: NSMergePolicyType.mergeByPropertyStoreTrumpMergePolicyType)
        return container
    }()*/
    
    
    public class PersistentContainer: NSPersistentContainer {

        static let shared: PersistentContainer = {
            let modelURL = Bundle(for: Storage.self).url(forResource: "ExposureNotification", withExtension: "momd")!
            let managedObjectModel = NSManagedObjectModel(contentsOf: modelURL)!
            let container = PersistentContainer(name: "ExposureNotification", managedObjectModel: managedObjectModel)

            //let container = PersistentContainer(name: "ExposureNotification")
            container.loadPersistentStores { (desc, error) in
                if let error = error {
                    fatalError("Unresolved error \(error)")
                }
                
                os_log("Successfully loaded persistent store at: %@", log: OSLog.storage, type: .debug, desc.url?.description ?? "nil")
            }
            
            container.viewContext.automaticallyMergesChangesFromParent = true
            container.viewContext.mergePolicy = NSMergePolicy(merge: NSMergePolicyType.mergeByPropertyStoreTrumpMergePolicyType)
            
            return container
        }()
        
        public override func newBackgroundContext() -> NSManagedObjectContext {
            let backgroundContext = super.newBackgroundContext()
            backgroundContext.automaticallyMergesChangesFromParent = true
            backgroundContext.mergePolicy = NSMergePolicy(merge: NSMergePolicyType.mergeByPropertyStoreTrumpMergePolicyType)
            return backgroundContext
        }
    }
}

