require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = 'react-native-exposure-notification-service'
  s.version      = package['version']
  s.summary      = package['description']
  s.license      = package['license']

  s.authors      = package['author']
  s.homepage     = package['homepage']
  s.platform     = :ios, "10.0"
  s.source       = { :git => "https://github.com/github_account/react-native-exposure-notification-service.git", :tag => "#{s.version}" }

  s.source_files = "ios/**/*.{h,m,swift}"
  s.resources = "ios/ExposureNotification.xcdatamodeld"
  s.requires_arc = true

  s.pod_target_xcconfig = { "DEFINES_MODULE" => "YES" }
  s.swift_version = '5.0'
  s.dependency 'React'
  s.dependency 'SSZipArchive'
  s.dependency 'Alamofire', '~> 5.2'
  s.dependency 'KeychainSwift', '~> 19.0'
end
