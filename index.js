import { NativeModules, NativeEventEmitter } from 'react-native'

const { ExposureNotificationModule } = NativeModules
const emitter = new NativeEventEmitter(ExposureNotificationModule)

export default ExposureNotificationModule

