declare module "@nearform/react-native-exposure-notification-service" {
  import { EventSubscriptionVendor } from "react-native"

  export enum AuthorisedStatus {
    granted = "granted",
    denied = "denied",
    blocked = "blocked",
    unavailable = "unavailable",
    unknown = "unknown",
  }

  export interface ConfigurationOptions {
    exposureCheckFrequency: number
    serverURL: string
    authToken: string
    refreshToken: string
    storeExposuresFor: number
    fileLimit: number
    version: string
    notificationTitle: string
    notificationDesc: string
    callbackNumber: string
    analyticsOptin: boolean
  }

  export interface DiagnosisKey {
    keyData: string
  }

  export interface CloseContact {
    exposureAlertDate: string
    attenuationDurations: number[]
    daysSinceLastExposure: number
    matchedKeyCount: number
    maxRiskScore: number
    summationRiskScore: number
  }

  export enum StatusState {
    unknown = "unknown",
    restricted = "restricted",
    disabled = "disabled",
    active = "active",
  }

  export enum StatusType {
    bluetooth = "bluetooth",
    exposure = "exposure",
    resolution = "resolution",
  }

  interface Status {
    state: StatusState
    type?: StatusType[]
  }

  const ExposureNotificationModuleInstance: ExposureNotificationModule

  export default ExposureNotificationModuleInstance

  interface ExposureNotificationModule extends EventSubscriptionVendor {
    canSupport(): Promise<boolean>

    isSupported(): Promise<boolean>

    exposureEnabled(): Promise<boolean>

    isAuthorised(): Promise<AuthorisedStatus>

    authoriseExposure(): Promise<boolean>

    configure(options: ConfigurationOptions): void

    start(): Promise<boolean>

    stop(): Promise<boolean>

    deleteAllData(): Promise<boolean>

    deleteExposureData(): Promise<boolean>

    getDiagnosisKeys(): Promise<DiagnosisKey[]>

    checkExposure(readDetails?: boolean, skipTimeCheck?: boolean): void

    getCloseContacts(): Promise<CloseContact[]>

    status(): Promise<Status>

    getLogData(): Promise<any>

    /**
     * @platform android
     */
    triggerUpdate(): Promise<string>
  }
}
