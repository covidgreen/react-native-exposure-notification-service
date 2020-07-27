<img alttext="COVID Green Logo" src="https://raw.githubusercontent.com/lfph/artwork/master/projects/covidgreen/stacked/color/covidgreen-stacked-color.png" width="300" />

# React Native Exposure Notification Service

React Native Exposure Notification Service is a react native module, which provides a common interface to
Apple/Google's Exposure Notification APIs.

For more on contact tracing see:
- https://www.google.com/covid19/exposurenotifications/
- https://www.apple.com/covid19/contacttracing

## Contents

- [Getting Started](#-getting-started)
- [Usage](#-usage)
- [Test Application](#-test-application)
- [Caveats](#-caveats)
- [License](#-license)


## Getting started

To integrate with your react-native app, edit your apps package.json and add the following dependency:

```json
{
    "dependencies": {
        "react-native-exposure-notification-service": "git+https://github.com/nearform/react-native-exposure-notification.git"
    }
}
```

or from the terminal type:

```
yarn add git+https://github.com/nearform/react-native-exposure-notification.git
```

**Note:** you must have access to this git repository, and supply a generated personal access token with adequate rights.

### Mostly automatic installation

React Native Exposure Notifications uses autolinking to allow your project discover and use this code.

On Android there are no further steps.

CocoaPods on iOS needs this extra step:

```
cd ios && pod install && cd ..
````


## Usage

### Example

```javascript
import ExposureNotificationModule from 'react-native-exposure-notification-service'

ExposureNotificationModule.start()
```

### Methods

#### `canSupport()`

```javascript
const canSupport = await ExposureNotificationModule.canSupport()
```

Used to check if the device can support the relevant exposure notification API.  This returns a promise that resolves a boolean determining if the device can support tracing or not.

---

#### `isSupported()`

exposure api is available on the device.

```javascript
const supported = await ExposureNotificationModule.isSupported()
```

Used to check if the device has the contact tracing APIs installed. This returns a promise that resolves with true if contact tracing is supported.

---

#### `exposureEnabled()`

```javascript
const enabled = await ExposureNotificationModule.exposureEnabled()
```

Use to check if the contact tracing is enabled. This returns a promise that resolves with true if contact tracing is enabled.

**Note:** On android, if enabled is true, tracing has started.

---

#### `isAuthorised()`

```javascript
const authorised = await ExposureNotificationModule.isAuthorised()
```

Use to check if the user has authorised contact tracing. Calling this method will NOT trigger an authorisation request. This returns a promise that resolves with a string representing the current authorisation state and can be one of: `granted`, `denied`, `blocked`, `unavailable` or `unknown`

---

#### `authoriseExposure()`

```javascript
const authorised = await ExposureNotificationModule.authoriseExposure()
```

Use to trigger an authorisation dialogue. This returns a promise that resolves with true if the user has authorised contact tracing, if they denied the request then false is returned.

---

#### `configure()`

```javascript
ExposureNotificationModule.configure(options)
```

Use to configure the module. This method is synchronous, and should be called before start etc. It takes an options object as a parameter with the following properties:

- `exposureCheckFrequency`: a number representing the period between exposure downloads in minutes
- `serverURL`: a string representing the the server api url (should not have trailing /)
- `authToken`: a string representing the current authorization token
- `refreshToken`: a string representing a token used to refresh the authorization token
- `storeExposuresFor`: a number representing the number of days to store data for
- `fileLimit`: a number representing the file limit
- `version`: a string representing the app version number
- `notificationTitle`: a string representing the title for positive exposure notifications popup,
- `notificationDesc`: a string representing the description for positive exposure notifications popup,
- `callbackNumber`: a string representing the phone number of a user if opted into automatic callback on positive exposure notification,
- `analyticsOptin`: a boolean representing whether the user opted in or not

---

#### `start()`

```javascript
const started = await ExposureNotificationModule.start()
```

Use to start exposure notifications. This method should only be called when canSupport(), isSupported() and isAuthorised() all return/resolve positive values and after configure() has been called.

A promise is returned and will resolve to true after a successful start, otherwise it will resolve to false.

---

#### `status()`

```javascript
const status = await ExposureNotificationModule.status()
```

Used to get the current start status.  This method returns a promise that resolves to a map containing 2 keys `state` and `type` both with string values.

The state can return as `active`, `disabled`, `unavailable` or `unknown`, and if set to `disabled` will contain the type presenting the reason why.

State changes also trigger the event `onStatusChanged`.

---

#### `stop()`

```javascript
ExposureNotificationModule.stop()
```

Used to stop contact tracing and all scheduled tasks. Exposure notifications must be authorised again after this method is called.

---

#### `deleteAllData()`

```javascript
const result = await ExposureNotificationModule.deleteAllData()
```

Used to delete all app related data including config & exposure data.  This returns a promise that resolves true if all data is successfully removed.

---

#### `deleteExposureData()`

```javascript
const result = await ExposureNotificationModule.deleteExposureData()
```

Used to deletes exposure data but leaves configuration data intact. This returns a promise that resolves true if exposure data is successfully removed.

---

#### `getDiagnosisKeys()`

```javascript
const keys = await ExposureNotificationModule.getDiagnosisKeys()
```

Used to retrieve a devices own diagnosis keys (typically all keys before today within a 14 day window).  This returns a promise that resolves to an array of maps containing the key `keyData`, encoded as a base64 string. This key should be used in export files for exposure matching.  If the user denies the request, the returned promise will reject.

**Note:** this will trigger a dialog from the underlying exposure notifications API, requesting permission from the device user.

---

#### `checkExposure()`

```javascript
ExposureNotificationModule.checkExposure()
```

Used to manually check exposure during testing.  Typically checkExposure is performed in background on schedule specified in configure.
This facilitates an immediate check.

On successful matches, this will raise a notification to the user, and also raise an `exposure` event to the app

---

#### `getCloseContacts()`

```javascript
const contacts = await ExposureNotificationModule.getCloseContacts()
```

Used to retrieve the summary array of matches recorded.  This async function returns a promise that resolves to a array of maps with the following keys:

- `exposureAlertDate`
- `attenuationDurations`
- `daysSinceLastExposure`
- `matchedKeyCount`
- `maxRiskScore`
- `summationRiskScore`

---

#### `triggerUpdate()` (Android Only)

```javascript
const result = await ExposureNotificationModule.triggerUpdate()
```

Used to trigger play services update should the user be using a version older than `201817017`

---

### Subscribing to Events

Create an `emitter` object using the `ExposureNotificationModule`

```javascript
import { NativeEventEmitter } from 'react-native'
import ExposureNotificationModule from 'react-native-exposure-notifications'
const emitter = new NativeEventEmitter(ExposureNotificationModule)
```

In a component or custom hook, you could use an effect to subscribe to the native module's events as follows:

```javascript
useEffect(() => {
    function handleEvent(ev) {
        if(ev.exposure) {
            console.log('You have come in contact with someone diagnosed with COVID-19')
        }

        // handle other events...
    }

    const subscription = emitter.addListener('exposureEvent', handleEvent)

    return () => {
        subscription.remove()
        emitter.removeListener('exposureEvent', handleEvent)
    }
}, [])
```

**There are two major events:**

1. `exposure`: fires when an exposure match event happens, which could be used to update the UI (contains a string)
2. `onStatusChange`: fires when the exposure tracing status changes. This event is a map map containing 2 keys `state` and `type` both with string values.

When `exposure` fires, `getCloseContacts()` would typically be called to retrieve the summary information.

When `onStatusChange` fires, it will contain the same data returned from a call to `status()`

**Note:** Other events may fire, depending on the platform and whether debug/release. These are mostly for debug and error logging only and should not be used for triggering application logic.

## Test Application

A test application to run the methods above, can be found under the  `test-app` folder.

To run this, from the terminal:

- `cd test-app`
- `yarn`
- `yarn start`
- and in a separate terminal window `yarn android` or `yarn ios`

Typically, it is better to run the test application on a device rather than a simulator.

*Note*: The check exposure function will not succeed unless connected to a valid server, if you have access to a valid server, modify `./test-app/config.js` with your settings.

To debug in android you will need to generate a debug keystore file, from the terminal execute:

```
cd android/app
```

then

```
keytool -genkey -v -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000
```

```
cd ../..
```

## Caveats

When building/running an app using the native module, several issues can arise.

### Android

#### Google Play Services (Exposure Notifications)

The Exposure Notification API provided by Google uses the Nearby API installed with Google Play Services.

This API is being rolled out by Google so that as many devices as possible support the API.

The minimum android version required is 23 (marshmallow).

The application performs a test to determine if the required API is available on the device using the `isSupported()` method.  If it is not installed google play services must be updated with the Nearby API.

If you have trouble with this, try `triggerUpdate()` to see if Play Services can be updated or update manually.

Applications using the Exposure API should be government agencies, and so not all applications can access the API directly.

#### Minify

If using minify when building an .apk on android, classes are often obfuscated, in which case some errors can arise when using reflection.

To keep required classes from being obfuscated, edit you `proguard-rules.pro` and add the following keep rules

```
-keep public class com.horcrux.svg.** {*;}
-keep class com.google.crypto.tink.** { *; }
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.* { *; }
-keep class * extends androidx.room.RoomDatabase
-keep class * extends com.google.auto
-keep class org.checkerframework.checker.nullness.qual.** { *; }
```

### iOS

### Common

#### Reloading the Native Module

If you make changes to the native module, and are referencing the package in your project, you may need to re-install it occasionally.  You can do this by pointing at a different commit, branch or version in your package.json.

You can link to commit using `#<commit>` at the end of the git reference, or add `#<feature>\/<branch>` or if versioning  use, `#semver:<semver>` (if the repo has any tags or refs matching that range).  If none of these are specified, then the master branch is used.

eg.

```json
{
    "dependencies": {
        "react-native-exposure-notification": "git+https://github.com/nearform/react-native-exposure-notification.git#semver:^1.0"
    }
}
```

If you are developing and make changes to a branch, and are not seeing changes being reflected in your react-native app, you can try reinstall the module as follows:

```
yarn remove react-native-exposure-notification && yarn add git+https://github.com/nearform/react-native-exposure-notification.git`
```

You can also link to the module directly on your file system if developing locally:

```
yarn add file:<path-to-module>`
```

#### Server Connectivity for Diagnosis Key Uploads and Exposure Notifications

In order to upload/download diagnosis keys for exposure notifications, an applications using this module needs to connect to a server that accepts upload of tokens, and packages them into valid export zip files.

## Team

### Lead Maintainers

* @colmharte - Colm Harte <colm.harte@nearform.com>
* @jasnell - James M Snell <jasnell@gmail.com>
* @aspiringarc - Gar Mac Críosta <gar.maccriosta@hse.ie>

### Core Team

* @ShaunBaker - Shaun Baker <shaun.baker@nearform.com>
* @floridemai - Paul Negrutiu <paul.negrutiu@nearform.com>
* @jackdclark - Jack Clark <jack.clark@nearform.com>
* @andreaforni - Andrea Forni <andrea.forni@nearform.com>
* @jackmurdoch - Jack Murdoch <jack.murdoch@nearform.com>

### Contributors

* TBD
* TBD

### Past Contributors

* TBD
* TBD

## Hosted By

<img alttext="Linux Foundation Public Health Logo" src="https://www.lfph.io/wp-content/themes/cncf-theme/images/lfph/faces-w_2000.png" width="100">

[Linux Foundation Public Health](https://lfph.io)

## Acknowledgements

<a href="https://www.hse.ie"><img alttext="HSE Ireland Logo" src="https://www.hse.ie/images/hse.jpg" width="200" /></a><a href="https://nearform.com"><img alttext="NearForm Logo" src="https://openjsf.org/wp-content/uploads/sites/84/2019/04/nearform.png" width="400" /></a>

## License

Copyright (c) 2020 Health Service Executive (HSE)
Copyright (c) The COVID Green Contributors

[Licensed](LICENSE) under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    [http://www.apache.org/licenses/LICENSE-2.0](http://www.apache.org/licenses/LICENSE-2.0)

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
