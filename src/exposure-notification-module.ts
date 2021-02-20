import {NativeModules, EventSubscriptionVendor} from 'react-native';
import {Version} from './types';

export enum AuthorisedStatus {
  granted = 'granted',
  denied = 'denied',
  blocked = 'blocked',
  unavailable = 'unavailable',
  unknown = 'unknown'
}

export enum KeyServerType {
  nearform = 'nearform',
  google = 'google'
}

export interface ConfigurationOptions {
  exposureCheckFrequency: number;
  serverURL: string;
  keyServerUrl: string;
  keyServerType: KeyServerType;
  authToken: string;
  refreshToken: string;
  storeExposuresFor: number;
  notificationTitle: string;
  notificationDesc: string;
  callbackNumber: string;
  analyticsOptin: boolean;
  notificationRepeat: number;
  certList: string;
  hideForeground?: boolean;
}

export interface DiagnosisKey {
  keyData: string;
}

export interface WindowData {
  date: number;
  calibrationConfidence: number;
  diagnosisReportType: number;
  infectiousness: number;
  buckets: number[];
  weightedBuckets: number[];
  numScans: number;
  exceedsThreshold: boolean;
}

export interface CloseContact {
  exposureAlertDate: number;
  exposureDate: number;
  attenuationDurations: number[];
  daysSinceLastExposure: number;
  matchedKeyCount: number;
  maxRiskScore: number;
  riskScoreSumFullRange: number;
  maxRiskScoreFullRange: number;
  windows?: WindowData[];
}

export enum StatusState {
  unavailable = 'unavailable',
  unknown = 'unknown',
  restricted = 'restricted',
  disabled = 'disabled',
  active = 'active'
}

export enum StatusType {
  bluetooth = 'bluetooth',
  exposure = 'exposure',
  resolution = 'resolution',
  paused = 'paused',
  starting = 'starting',
  stopped = 'stopped'
}

export interface Status {
  state: StatusState;
  type?: StatusType[];
}

export interface ExposureNotificationModule extends EventSubscriptionVendor {
  canSupport(): Promise<boolean>;

  isSupported(): Promise<boolean>;

  exposureEnabled(): Promise<boolean>;

  isAuthorised(): Promise<AuthorisedStatus>;

  authoriseExposure(): Promise<boolean>;

  configure(options: ConfigurationOptions): void;

  start(): Promise<boolean>;

  pause(): Promise<boolean>;

  stop(): Promise<boolean>;

  deleteAllData(): Promise<boolean>;

  deleteExposureData(): Promise<boolean>;

  getDiagnosisKeys(): Promise<DiagnosisKey[]>;

  checkExposure(skipTimeCheck?: boolean): void;

  simulateExposure(timeDelay: number, exposureDays: number): void;

  getCloseContacts(): Promise<CloseContact[]>;

  status(): Promise<Status>;

  getLogData(): Promise<any>;

  getConfigData(): Promise<any>;

  version(): Promise<Version>;

  bundleId(): Promise<string>;

  cancelNotifications(): void;

  /**
   * @platform android
   */
  triggerUpdate(): Promise<string>;
}

const {
  ExposureNotificationModule: NativeExposureNotificationModule
} = NativeModules;

export default NativeExposureNotificationModule as ExposureNotificationModule;
