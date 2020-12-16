
import UIKit

public extension UIDevice {
    static let supportsENS: Bool = {
        var systemInfo = utsname()
        uname(&systemInfo)
        let machineMirror = Mirror(reflecting: systemInfo.machine)
        let identifier = machineMirror.children.reduce("") { identifier, element in
            guard let value = element.value as? Int8, value != 0 else { return identifier }
            return identifier + String(UnicodeScalar(UInt8(value)))
        }

        func mapToDevice(identifier: String) -> Bool {
            switch identifier {
            case "iPod5,1":                                 return false
            case "iPod7,1":                                 return false
            case "iPod9,1":                                 return false
            case "iPhone3,1", "iPhone3,2", "iPhone3,3":     return false
            case "iPhone4,1":                               return false
            case "iPhone5,1", "iPhone5,2":                  return false
            case "iPhone5,3", "iPhone5,4":                  return false
            case "iPhone6,1", "iPhone6,2":                  return true
            case "iPhone7,2":                               return true
            case "iPhone7,1":                               return true
            case "iPhone8,1":                               return true
            case "iPhone8,2":                               return true
            case "iPhone9,1", "iPhone9,3":                  return true
            case "iPhone9,2", "iPhone9,4":                  return true
            case "iPhone8,4":                               return true
            case "iPhone10,1", "iPhone10,4":                return true
            case "iPhone10,2", "iPhone10,5":                return true
            case "iPhone10,3", "iPhone10,6":                return true
            case "iPhone11,2":                              return true
            case "iPhone11,4", "iPhone11,6":                return true
            case "iPhone11,8":                              return true
            case "iPhone12,1":                              return true
            case "iPhone12,3":                              return true
            case "iPhone12,5":                              return true
            case "iPad2,1", "iPad2,2", "iPad2,3", "iPad2,4":return false
            case "iPad3,1", "iPad3,2", "iPad3,3":           return false
            case "iPad3,4", "iPad3,5", "iPad3,6":           return false
            case "iPad6,11", "iPad6,12":                    return true
            case "iPad7,5", "iPad7,6":                      return true
            case "iPad7,11", "iPad7,12":                    return true
            case "iPad4,1", "iPad4,2", "iPad4,3":           return false
            case "iPad5,3", "iPad5,4":                      return true
            case "iPad11,4", "iPad11,5":                    return true
            case "iPad2,5", "iPad2,6", "iPad2,7":           return false
            case "iPad4,4", "iPad4,5", "iPad4,6":           return false
            case "iPad4,7", "iPad4,8", "iPad4,9":           return false
            case "iPad5,1", "iPad5,2":                      return true
            case "iPad11,1", "iPad11,2":                    return true
            case "iPad6,3", "iPad6,4":                      return true
            case "iPad6,7", "iPad6,8":                      return true
            case "iPad7,1", "iPad7,2":                      return true
            case "iPad7,3", "iPad7,4":                      return true
            case "iPad8,1", "iPad8,2", "iPad8,3", "iPad8,4":return true
            case "iPad8,5", "iPad8,6", "iPad8,7", "iPad8,8":return true
            case "AppleTV5,3":                              return false
            case "AppleTV6,2":                              return false
            case "AudioAccessory1,1":                       return false
            case "i386", "x86_64":                          return false // simulator
            default:
              return true
            }
        }

        return mapToDevice(identifier: identifier)
    }()

}
