import Foundation
import ExposureNotification
import os.log

@available(iOS 13.5, *)
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
                self.manager.setExposureNotificationEnabled(true) { error in
                    // No error handling for attempts to enable on launch
                  if let error = error as? ENError {
                    os_log("Unable to enable ENS, %@", log: OSLog.setup, type: .error, error.localizedDescription)
                  }
                }
            }
        }
    }
  }
  
  deinit {
    manager.invalidate()
  }

}
