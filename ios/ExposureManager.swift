import Foundation
import ExposureNotification
import os.log

@available(iOS 12.5, *)
class ExposureManager {
  static let shared = ExposureManager()
  let manager = ENManager()
  
  init() {
    self.manager.activate { error in
        if let error = error as? ENError {
            os_log("Error activating ENManager, %@", log: OSLog.setup, type: .error, error.localizedDescription)
        } else {
            // Ensure exposure notifications are enabled if the app is authorized. The app
            // could get into a state where it is authorized, but exposure
            // notifications are not enabled,  if the user initially denied Exposure Notifications
            // during onboarding, but then flipped on the "COVID-19 Exposure Notifications" switch
            // in Settings.
            if ENManager.authorizationStatus == .authorized && !self.manager.exposureNotificationEnabled {

                if !ExposureManager.shared.isPaused() && !ExposureManager.shared.isStopped() {
                    self.manager.setExposureNotificationEnabled(true) { error in
                        // No error handling for attempts to enable on launch
                      if let error = error as? ENError {
                        os_log("Unable to enable ENS, %@", log: OSLog.setup, type: .error, error.localizedDescription)
                      }
                    }
                } else {
                    os_log("Service is paused so don't start", log: OSLog.setup, type: .debug)
                }
            }
        }
    }
  }
    
  public func isPaused() -> Bool {
     let context = Storage.PersistentContainer.shared.newBackgroundContext()
     guard let config = Storage.shared.readSettings(context) else {
       return false
     }
    
     return config.paused
  }

  public func isStopped() -> Bool {
     let context = Storage.PersistentContainer.shared.newBackgroundContext()
     guard let config = Storage.shared.readSettings(context) else {
       return true
     }
      
     return config.stopped
  }

  public func launchBackgroundiOS12() {
     self.manager.setLaunchActivityHandler { (activityFlags) in
        // ENManager gives apps that register an activity handler
        // in iOS 12.5 up to 3.5 minutes of background time at
        // least once per day. In iOS 13 and later, registering an
        // activity handler does nothing.
        if activityFlags.contains(.periodicRun) {
            os_log("Scheduling background exposure check ios 12.5", log: OSLog.setup, type: .debug)
            ExposureProcessor.shared.checkExposureForeground(false, false, 0)
        }
     }
  }
    
  deinit {
    manager.invalidate()
  }

}
