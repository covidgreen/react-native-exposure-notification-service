export interface TraceConfiguration {
  exposureCheckInterval: number;
  storeExposuresFor: number;
}

export enum PermissionStatus {
  Unknown = 'unknown',
  NotAvailable = 'not_available',
  Allowed = 'allowed',
  NotAllowed = 'not_allowed'
}

export interface PermissionDetails {
  status:
    | PermissionStatus.Unknown
    | PermissionStatus.NotAvailable
    | PermissionStatus.NotAllowed
    | PermissionStatus.Allowed;
  internal?: any;
}

export interface ExposurePermissions {
  exposure: PermissionDetails;
  notifications: PermissionDetails;
}

export interface Version {
  version: String;
  build: String;
  display: String;
}
