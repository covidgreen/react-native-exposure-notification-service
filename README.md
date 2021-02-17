<img alttext="COVID Green Logo" src="https://raw.githubusercontent.com/lfph/artwork/master/projects/covidgreen/stacked/color/covidgreen-stacked-color.png" width="300" />

# React Native Exposure Notification Service

React Native Exposure Notification Service is a react native module, which provides a common interface to
Apple/Google's Exposure Notification APIs.

For more on contact tracing see:
- https://www.google.com/covid19/exposurenotifications/
- https://www.apple.com/covid19/contacttracing

## Contents

- [Getting Started](#getting-started)
- [Usage](#usage)
  - [ExposureNotificationModule](#exposurenotificationmodule)
  - [ExposureProvider](#exposureprovider)
- [Release process](#release-process)
- [Test Application](#test-application)
- [Caveats](#caveats)
- [License](#license)

## Getting started

To integrate with your react-native app:

```
# with npm
npm install --save react-native-exposure-notification-service

# with yarn
yarn add react-native-exposure-notification-service
```

### Mostly automatic installation

React Native Exposure Notifications uses autolinking to allow your project discover and use this code.

On Android there are no further steps.

CocoaPods on iOS needs this extra step:

```
cd ios && pod install && cd ..
```

## Usage

There are multiple ways to use the React Native Exposure Notification Service in your React application. You can use it directly via the [`ExposureNotificationModule`](#-exposurenotificationmodule) and manage the application state yourself, or you can use the [`ExposureProvider`](#-exposure-provider) [React Context](https://reactjs.org/docs/context.html) implementation, where the application state is managed for you.

### `ExposureNotificationModule`

#### Example

```javascript
import ExposureNotificationModule from 'react-native-exposure-notification-service';

ExposureNotificationModule.start();
```

#### Methods

##### `canSupport()`

```javascript
const canSupport = await ExposureNotificationModule.canSupport();
```

Used to check if the device can support the relevant exposure notification API. This returns a promise that resolves a boolean determining if the device can support tracing or not.

---

##### `isSupported()`

exposure api is available on the device.

```javascript
const supported = await ExposureNotificationModule.isSupported();
```

Used to check if the device has the contact tracing APIs installed. This returns a promise that resolves with true if contact tracing is supported.

---

##### `exposureEnabled()`

```javascript
const enabled = await ExposureNotificationModule.exposureEnabled();
```

Use to check if the contact tracing is enabled. This returns a promise that resolves with true if contact tracing is enabled.

**Note:** On android, if enabled is true, tracing has started.

---

##### `isAuthorised()`

```javascript
const authorised = await ExposureNotificationModule.isAuthorised();
```

Use to check if the user has authorised contact tracing. Calling this method will NOT trigger an authorisation request. This returns a promise that resolves with a string representing the current authorisation state and can be one of: `granted`, `denied`, `blocked`, `unavailable` or `unknown`

---

##### `authoriseExposure()`

```javascript
const authorised = await ExposureNotificationModule.authoriseExposure();
```

Use to trigger an authorisation dialogue. This returns a promise that resolves with true if the user has authorised contact tracing, if they denied the request then false is returned.

---

##### `configure()`

```javascript
ExposureNotificationModule.configure(options);
```

Use to configure the module. This method is synchronous, and should be called before start etc. It takes an options object as a parameter with the following properties:

- `exposureCheckFrequency`: a number representing the period between exposure downloads in minutes
- `serverURL`: a string representing the the server api url (should not have trailing /)
- `keyServerUrl`: a string representing the the key server api url (should not have trailing /). Will default to serverURL
- `keyServerType`: a string representing the the key server type, options are nearform or google. Defaults to nearform
- `authToken`: a string representing the current authorization token
- `refreshToken`: a string representing a token used to refresh the authorization token
- `storeExposuresFor`: a number representing the number of days to store data for
- `notificationTitle`: a string representing the title for positive exposure notifications popup,
- `notificationDesc`: a string representing the description for positive exposure notifications popup,
- `callbackNumber`: a string representing the phone number of a user if opted into automatic callback on positive exposure notification,
- `analyticsOptin`: a boolean representing whether the user opted in or not
- `hideForeground`: Android only. a boolean representing whether to hide foreground notifications

---

##### `start()`

```javascript
const started = await ExposureNotificationModule.start();
```

Use to start exposure notifications. This method should only be called when canSupport(), isSupported() and isAuthorised() all return/resolve positive values and after configure() has been called.

A promise is returned and will resolve to true after a successful start, otherwise it will resolve to false.

---

##### `status()`

```javascript
const status = await ExposureNotificationModule.status();
```

Used to get the current start status. This method returns a promise that resolves to a map containing 2 keys `state` and `type` both with string values.

The state can return as `active`, `disabled`, `unavailable` or `unknown`, and if set to `disabled` will contain the type presenting the reason why.

The type can return as `bluetooth`, `exposure`, `resolution`, `paused`, `starting` and its meaning should be read in combination with `state`, i.e. a state of `disabled` and a type of `bluetooth` indicates that ENS is disabled because bluetooth is off.

State changes also trigger the event `onStatusChanged`.

---

##### `stop()`

```javascript
ExposureNotificationModule.stop();
```

Used to stop contact tracing and all scheduled tasks. Exposure notifications must be authorised again after this method is called.

---

##### `pause()`

```javascript
ExposureNotificationModule.pause();
```

Used to pause contact tracing. Use start() to unpause.

---

##### `deleteAllData()`

```javascript
const result = await ExposureNotificationModule.deleteAllData();
```

Used to delete all app related data including config & exposure data. This returns a promise that resolves true if all data is successfully removed.

---

##### `deleteExposureData()`

```javascript
const result = await ExposureNotificationModule.deleteExposureData();
```

Used to deletes exposure data but leaves configuration data intact. This returns a promise that resolves true if exposure data is successfully removed.

---

##### `getDiagnosisKeys()`

```javascript
const keys = await ExposureNotificationModule.getDiagnosisKeys();
```

Used to retrieve a devices own diagnosis keys (typically all keys before today within a 14 day window). This returns a promise that resolves to an array of maps containing the key `keyData`, encoded as a base64 string. This key should be used in export files for exposure matching. If the user denies the request, the returned promise will reject.

**Note:** this will trigger a dialog from the underlying exposure notifications API, requesting permission from the device user.

---

##### `checkExposure()`

```javascript
ExposureNotificationModule.checkExposure();
```

Used to manually check exposure during testing. Typically checkExposure is performed in background on schedule specified in configure.
This facilitates an immediate check.

On successful matches, this will raise a notification to the user, and also raise an `exposure` event to the app

---

##### `simulateExposure()`

```javascript
ExposureNotificationModule.simulateExposure();
```

Used to manually generate an exposure alert during testing to make it easier to validate UX flows around exposure events.

This will raise a notification to the user, and also raise an `exposure` event to the app.

---

##### `getCloseContacts()`

```javascript
const contacts = await ExposureNotificationModule.getCloseContacts();
```

Used to retrieve the summary array of matches recorded. This async function returns a promise that resolves to a array of maps with the following keys:

- `exposureAlertDate`
- `attenuationDurations`
- `daysSinceLastExposure`
- `matchedKeyCount`
- `maxRiskScore`
- `summationRiskScore`

---

##### `triggerUpdate()` (Android Only)

```javascript
const result = await ExposureNotificationModule.triggerUpdate();
```

Used to trigger play services update should the user be using a version older than `201817017`

---

#### Enabling background processing (iOS only)

Enable exposure checks when the app is backgrounded / closed.

##### Prerequites:

Go to Signing & Capabilities -> Background Modes, tick `Background fetch` & `Background processing`.  

Add a new item to the Info.plist and choose "Permitted background task scheduler identifiers"  
Expand the array and add value `$(PRODUCT_BUNDLE_IDENTIFIER).exposure-notification`  

You should see the following lines in your Info.plist:
```plist
	<key>UIBackgroundModes</key>
	<array>
		<string>fetch</string>
		<string>processing</string>
	</array>
```
```plist
	<key>BGTaskSchedulerPermittedIdentifiers</key>
	<array>
		<string>$(PRODUCT_BUNDLE_IDENTIFIER).exposure-notification</string>
	</array>
```

Add the following header in `AppDelegate.m`
```objective-c
#import <react_native_exposure_notification_service-Swift.h>
```
Add the following lines in `application:didFinishLaunchingWithOptions:`
```objective-c
// Register the background task to perform exposure checks
[ExposureNotificationModule registerBackgroundProcessing];
```

---

#### Subscribing to Events

Create an `emitter` object using the `ExposureNotificationModule`

```javascript
import {NativeEventEmitter} from 'react-native';
import ExposureNotificationModule from 'react-native-exposure-notification-service';
const emitter = new NativeEventEmitter(ExposureNotificationModule);
```

In a component or custom hook, you could use an effect to subscribe to the native module's events as follows:

```javascript
useEffect(() => {
  function handleEvent(ev) {
    if (ev.exposure) {
      console.log(
        'You have come in contact with someone diagnosed with COVID-19'
      );
    }

    // handle other events...
  }

  const subscription = emitter.addListener('exposureEvent', handleEvent);

  return () => {
    subscription.remove();
    emitter.removeListener('exposureEvent', handleEvent);
  };
}, []);
```

**There are two major events:**

1. `exposure`: fires when an exposure match event happens, which could be used to update the UI (contains a string)
2. `onStatusChange`: fires when the exposure tracing status changes. This event is a map map containing 2 keys `state` and `type` both with string values.

When `exposure` fires, `getCloseContacts()` would typically be called to retrieve the summary information.

When `onStatusChange` fires, it will contain the same data returned from a call to `status()`

**Note:** Other events may fire, depending on the platform and whether debug/release. These are mostly for debug and error logging only and should not be used for triggering application logic.

### `ExposureProvider`

You should add `ExposureProvider` in your app root component.

#### Example

```tsx
import {
  ExposureProvider,
  useExposure
} from 'react-native-exposure-notification-service';

function Root() {
  return (
    <ExposureProvider
      traceConfiguration={{
        exposureCheckInterval: 180,
        storeExposuresFor: 14,
      }
      serverUrl="https://your.exposure.api/api"
      keyServerUrl="https://your.exposure.api/api"
      keyServerType=KeyServerType.nearform
      authToken="your-api-auth-token"
      refreshToken="your-api-refresh-token"
      notificationTitle="Close contact detected"
      notificationDescription="Open the app for instructions">
      notificationRepeat=0>
      <App />
    </ExposureProvider>
  );
}
```

#### Props

##### `isReady`

`boolean` (default `false`) If true, will start the exposure notification service if the user has given permission

##### `traceConfiguration` (required)

`object` Tracing related configuration options

```ts
{
  exposureCheckInterval: number;
  storeExposuresFor: number;
}
```

##### `appVersion` (required)

`string` The build version of your application

##### `serverUrl` (required)

`string` The URL of your exposure API

##### `authToken` (required)

`string` The auth token for your exposure API

##### `refreshToken` (required)

`string` The refresh token for your exposure API

##### `notificationTitle` (required)

`string` The title of a close contact push notification

##### `notificationDescription` (required)

`string` The description of a close contact push notification

##### `callbackNumber` (optional)

`string` The phone number to be used for health authority callbacks

##### `analyticsOptin` (optional)

`boolean` (default `false`) Consent to send analytics to your exposure API's `/metrics` endpoint

##### `notificationRepeat` (optional)

`number` (default `0`) Used to repeat exposure notifications after set interval. Internal time is in minutes.

##### `certList` (optional)

`string` (default ``) Used to override the cert names to be looked for in the package on android.

### `useExposure`

Use the `useExposure` hook in any component to consume the `ExposureProvider` context & methods

#### Example

```tsx
import {useExposure} from 'react-native-exposure-notification-service';

function MyComponent() {
  const {status, permissions, requestPermissions} = useExposure();

  return (
    <View>
      <Text>Exposure status: {status}</Text>
      <Text>Exposure permissions: {JSON.stringify(permissions, null, 2)}</Text>
      <Button onPress={requestPermissions}>Ask for exposure permissions</Button>
    </View>
  );
}
```

#### Values

All of these values are available on the return value of `useExposure`

##### `status`

`Status` The current status of the exposure service

##### `supported`

`boolean` The exposure API is available on the device.

##### `canSupport`

`boolean` This device can support the exposure API

##### `isAuthorised`

`boolean` The user has authorised the exposure API

##### `enabled`

`boolean` The exposure API is enabled & has started

##### `contacts`

`CloseContact[] | null` An array of recorded matches

##### `initialised`

`boolean` The native module has successfully initialised

##### `permissions`

`ExposurePermissions` The current status of permissions for `exposure` & `notifications`

##### `start()`

`() => void`

Start the exposure API, check & update the status & check for close contacts

Calls `ExposureNotificationModule.start()`

##### `stop()`

`() => void`

Stop the exposure API & check & update status

Calls `ExposureNotificationModule.stop()`

##### `configure()`

`() => void`

Configure the native module (this is called internally once permissions have been granted)

Calls `ExposureNotificationModule.configure()`

##### `checkExposure()`

`(skipTimeCheck: boolean) => void`

Calls `ExposureNotificationModule.checkExposure()`

##### `getDiagnosisKeys()`

`() => Promise<any[]>`

Calls `ExposureNotificationModule.getDiagnosisKeys()`

##### `exposureEnabled()`

`() => Promise<boolean>`

Calls `ExposureNotificationModule.exposureEnabled()`

##### `authoriseExposure()`

`() => Promise<boolean>`

Calls `ExposureNotificationModule.authoriseExposure()`

##### `deleteAllData()`

`() => Promise<void>`

Calls `ExposureNotificationModule.deleteAllData()` & checks & update the status

##### `supportsExposureApi()`

`() => Promise<void>`

Manually check whether the device supports the exposure API and update the context

##### `cancelNotifications()`

`() => void`

Used to cancel any repeating notifications that have been scheduled

##### `getCloseContacts()`

`() => Promise<CloseContact[] | null>`

Manually retrieve a summary of matched records and update the context

Calls `ExposureNotificationModule.getCloseContacts()`

##### `getLogData()`

`() => Promise<{[key: string]: any}>`

Get log data from the exposure API

Calls `ExposureNotificationModule.getLogData()`

##### `triggerUpdate()`

`() => Promise<string | undefined>`

Triggers a play services update on Android if possible

Calls `ExposureNotificationModule.triggerUpdate()`

##### `deleteExposureData()`

`() => Promise<void>`

Deletes exposure data & updates the context

Calls `ExposureNotificationModule.deleteExposureData()`

##### `readPermissions()`

`() => Promise<void>`

Checks the current status of `exposure` and `notifications` permissions and updates the context

##### `askPermissions()`

`() => Promise<void>`

Requests permissions from the user for `exposure` and `notifications` permissions and updates the context

##### `getVersion()`

`() => Promise<Version>`

Returns the version number for the app

##### `getBundleId()`

`() => Promise<string>`

Returns the bundle identifier / package name for the app

##### `getConfigData()`

`() => Promise<{[key: string]: any}>`

Returns the config being used by the module

##### `setExposureState()`

`(setStateAction: SetStateAction<State>) => void`

Not recommended: Manually update the state in the exposure context. Can be useful for development & simulating different states.

## Test Application

A test application to run the methods above, can be found under the `test-app` folder.

To run this, from the terminal:

- `cd test-app`
- `yarn`
- `yarn start`
- and in a separate terminal window `yarn android` or `yarn ios`

Typically, it is better to run the test application on a device rather than a simulator.

_Note_: The check exposure function will not succeed unless connected to a valid server, if you have access to a valid server, modify `./test-app/config.js` with your settings.

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

The application performs a test to determine if the required API is available on the device using the `isSupported()` method. If it is not installed google play services must be updated with the Nearby API.

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

If you make changes to the native module, and are referencing the package in your project, you may need to re-install it occasionally. You can do this by pointing at a different commit, branch or version in your package.json.

You can link to commit using `#<commit>` at the end of the git reference, or add `#<feature>\/<branch>` or if versioning use, `#semver:<semver>` (if the repo has any tags or refs matching that range). If none of these are specified, then the master branch is used.

eg.

```json
{
  "dependencies": {
    "react-native-exposure-notification": "git+https://github.com/covidgreen/react-native-exposure-notification.git#semver:^1.0"
  }
}
```

If you are developing and make changes to a branch, and are not seeing changes being reflected in your react-native app, you can try reinstall the module as follows:

```
yarn remove react-native-exposure-notification && yarn add git+https://github.com/covidgreen/react-native-exposure-notification.git`
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

* @moogster31 - Katie Roberts <katie@geekworld.co>
* @AlanSl - Alan Slater <alan.slater@nearform.com>
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
