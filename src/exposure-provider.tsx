import React, {
  useEffect,
  useState,
  createContext,
  useCallback,
  useContext,
  SetStateAction
} from 'react';
import {
  NativeEventEmitter,
  Alert,
  AppState,
  AppStateStatus
} from 'react-native';

import ExposureNotification, {
  AuthorisedStatus,
  StatusState,
  Status,
  CloseContact,
  StatusType,
  KeyServerType
} from './exposure-notification-module';

import {getPermissions, requestPermissions} from './utils/permissions';
import {
  ExposurePermissions,
  PermissionStatus,
  TraceConfiguration
} from './types';

const emitter = new NativeEventEmitter(ExposureNotification);

interface State {
  status: Status;
  supported: boolean;
  canSupport: boolean;
  isAuthorised: AuthorisedStatus;
  enabled: boolean;
  contacts?: CloseContact[];
  initialised: boolean;
  permissions: ExposurePermissions;
}

export interface ExposureContextValue extends State {
  start: () => Promise<boolean>;
  stop: () => void;
  pause: () => Promise<boolean>;
  configure: () => void;
  checkExposure: (skipTimeCheck: boolean) => void;
  simulateExposure: (timeDelay: number, exposureDays: number) => void;
  getDiagnosisKeys: () => Promise<any[]>;
  exposureEnabled: () => Promise<boolean>;
  authoriseExposure: () => Promise<boolean>;
  deleteAllData: () => Promise<void>;
  supportsExposureApi: () => Promise<void>;
  getCloseContacts: () => Promise<CloseContact[]>;
  getLogData: () => Promise<{[key: string]: any}>;
  triggerUpdate: () => Promise<string | undefined>;
  deleteExposureData: () => Promise<void>;
  readPermissions: () => Promise<void>;
  askPermissions: () => Promise<void>;
  setExposureState: (setStateAction: SetStateAction<State>) => void;
  cancelNotifications: () => void;
}

const initialState = {
  status: {
    state: StatusState.unavailable,
    type: [StatusType.starting]
  },
  supported: false,
  canSupport: false,
  isAuthorised: 'unknown' as AuthorisedStatus,
  enabled: false,
  contacts: [] as CloseContact[],
  initialised: false,
  permissions: {
    exposure: {status: PermissionStatus.Unknown},
    notifications: {status: PermissionStatus.Unknown}
  }
};

export const ExposureContext = createContext<ExposureContextValue>({
  ...initialState,
  start: () => Promise.resolve(false),
  stop: () => {},
  pause: () => Promise.resolve(false),
  configure: () => {},
  checkExposure: () => {},
  simulateExposure: () => {},
  getDiagnosisKeys: () => Promise.resolve([]),
  exposureEnabled: () => Promise.resolve(false),
  authoriseExposure: () => Promise.resolve(false),
  deleteAllData: () => Promise.resolve(),
  supportsExposureApi: () => Promise.resolve(),
  getCloseContacts: () => Promise.resolve([]),
  getLogData: () => Promise.resolve({}),
  triggerUpdate: () => Promise.resolve(undefined),
  deleteExposureData: () => Promise.resolve(),
  readPermissions: () => Promise.resolve(),
  askPermissions: () => Promise.resolve(),
  setExposureState: () => {},
  cancelNotifications: () => {}
});

export interface ExposureProviderProps {
  isReady: boolean;
  traceConfiguration: TraceConfiguration;
  serverUrl: string;
  keyServerUrl: string;
  keyServerType: KeyServerType;
  authToken: string;
  refreshToken: string;
  notificationTitle: string;
  notificationDescription: string;
  callbackNumber?: string;
  analyticsOptin?: boolean;
  notificationRepeat?: number;
  certList?: string;
  hideForeground?: boolean;
}

export const getVersion = async () => {
  try {
    const result = await ExposureNotification.version();
    return result;
  } catch (e) {
    console.log('build version error', e);
  }
};

export const getBundleId = async () => {
  try {
    const result = await ExposureNotification.bundleId();
    return result;
  } catch (e) {
    console.log('bundle id error', e);
  }
};

export const getConfigData = async () => {
  try {
    const result = await ExposureNotification.getConfigData();
    return result;
  } catch (e) {
    console.log('getConfigData error', e);
  }
};

export const ExposureProvider: React.FC<ExposureProviderProps> = ({
  children,
  isReady = false,
  traceConfiguration,
  serverUrl,
  keyServerUrl,
  keyServerType = KeyServerType.nearform,
  authToken = '',
  refreshToken = '',
  notificationTitle,
  notificationDescription,
  callbackNumber = '',
  analyticsOptin = false,
  notificationRepeat = 0,
  certList = '',
  hideForeground
}) => {
  const [state, setState] = useState<State>(initialState);

  useEffect(() => {
    function handleEvent(
      ev: {onStatusChanged?: Status; status?: any; scheduledTask?: any} = {}
    ) {
      console.log(`exposureEvent: ${JSON.stringify(ev)}`);
      if (ev.onStatusChanged) {
        return validateStatus(ev.onStatusChanged);
      }
    }

    let subscription = emitter.addListener('exposureEvent', handleEvent);

    const listener = (type: AppStateStatus) => {
      if (type === 'active') {
        validateStatus();
        getCloseContacts();
      }
    };

    AppState.addEventListener('change', listener);

    return () => {
      subscription.remove();
      emitter.removeListener('exposureEvent', handleEvent);
      AppState.removeEventListener('change', listener);
    };
  }, []);

  useEffect(() => {
    async function checkSupportAndStart() {
      await supportsExposureApi();

      await configure();

      // Start as soon as we're able to
      if (
        isReady &&
        state.permissions.exposure.status === PermissionStatus.Allowed
      ) {
        const latestStatus = await ExposureNotification.status();

        if (
          !(
            latestStatus &&
            (latestStatus.type?.indexOf(StatusType.paused) > -1 ||
              latestStatus.type?.indexOf(StatusType.stopped) > -1)
          )
        ) {
          start();
        }
      }
    }
    checkSupportAndStart();
  }, [
    state.permissions.exposure.status,
    state.permissions.notifications.status,
    isReady
  ]);

  const supportsExposureApi = async function () {
    const can = await ExposureNotification.canSupport();
    const is = await ExposureNotification.isSupported();
    const status = await ExposureNotification.status();
    const enabled = await ExposureNotification.exposureEnabled();
    const isAuthorised = await ExposureNotification.isAuthorised();

    setState((s) => ({
      ...s,
      status,
      enabled,
      canSupport: can,
      supported: is,
      isAuthorised
    }));
    await validateStatus(status);
    await getCloseContacts();
  };

  const validateStatus = async (status?: Status) => {
    let newStatus = status || ((await ExposureNotification.status()) as Status);
    const enabled = await ExposureNotification.exposureEnabled();
    const isAuthorised = await ExposureNotification.isAuthorised();
    const canSupport = await ExposureNotification.canSupport();

    const isStarting =
      (isAuthorised === AuthorisedStatus.unknown ||
        isAuthorised === AuthorisedStatus.granted) &&
      newStatus.state === StatusState.unavailable &&
      newStatus.type?.includes(StatusType.starting);
    const initialised = !isStarting || !canSupport;

    setState((s) => ({
      ...s,
      status: newStatus,
      enabled,
      isAuthorised,
      canSupport,
      initialised
    }));
  };

  const start = async () => {
    try {
      const result = await ExposureNotification.start();
      await validateStatus();
      await getCloseContacts();

      return result;
    } catch (err) {
      console.log('start err', err);
    }
  };

  const pause = async () => {
    try {
      const result = await ExposureNotification.pause();
      await validateStatus();
      return result;
    } catch (err) {
      console.log('pause err', err);
    }
  };

  const stop = async () => {
    try {
      await ExposureNotification.stop();
      await validateStatus();
    } catch (err) {
      console.log('stop err', err);
    }
  };

  const configure = async () => {
    try {
      const config = {
        exposureCheckFrequency: traceConfiguration.exposureCheckInterval,
        serverURL: serverUrl,
        keyServerUrl,
        keyServerType,
        authToken,
        refreshToken,
        storeExposuresFor: traceConfiguration.storeExposuresFor,
        notificationTitle,
        notificationDesc: notificationDescription,
        callbackNumber,
        analyticsOptin,
        notificationRepeat,
        certList,
        hideForeground
      };
      await ExposureNotification.configure(config);

      return true;
    } catch (err) {
      console.log('configure err', err);
      return false;
    }
  };

  const checkExposure = (skipTimeCheck: boolean) => {
    ExposureNotification.checkExposure(skipTimeCheck);
  };

  const simulateExposure = (timeDelay: number, exposureDays: number) => {
    ExposureNotification.simulateExposure(timeDelay, exposureDays);
  };

  const getDiagnosisKeys = () => {
    return ExposureNotification.getDiagnosisKeys();
  };

  const exposureEnabled = async () => {
    return ExposureNotification.exposureEnabled();
  };

  const authoriseExposure = async () => {
    return ExposureNotification.authoriseExposure();
  };

  const deleteAllData = async () => {
    await ExposureNotification.deleteAllData();
    await validateStatus();
  };

  const getCloseContacts = async () => {
    try {
      const contacts = await ExposureNotification.getCloseContacts();
      setState((s) => ({...s, contacts}));
      return contacts;
    } catch (err) {
      console.log('getCloseContacts err', err);
      return null;
    }
  };

  const getLogData = async () => {
    try {
      const data = await ExposureNotification.getLogData();
      return data;
    } catch (err) {
      console.log('getLogData err', err);
      return null;
    }
  };

  const triggerUpdate = async () => {
    try {
      const result = await ExposureNotification.triggerUpdate();
      console.log('trigger update: ', result);
      // this will not occur after play services update available to public
      if (result === 'api_not_available') {
        Alert.alert(
          'API Not Available',
          'Google Exposure Notifications API not available on this device yet'
        );
      }
      return result;
    } catch (e) {
      console.log('trigger update error', e);
    }
  };

  const deleteExposureData = async () => {
    try {
      await ExposureNotification.deleteExposureData();
      setState((s) => ({...s, contacts: []}));
    } catch (e) {
      console.log('delete exposure data error', e);
    }
  };

  const cancelNotifications = async () => {
    try {
      ExposureNotification.cancelNotifications();
    } catch (e) {
      console.log('cancel notifications exposure data error', e);
    }
  };

  const readPermissions = useCallback(async () => {
    console.log('Read permissions...');

    const perms = await getPermissions();
    console.log('perms: ', JSON.stringify(perms, null, 2));

    setState((s) => ({...s, permissions: perms}));
  }, []);

  const askPermissions = useCallback(async () => {
    console.log('Requesting permissions...', state.permissions);
    await requestPermissions();

    await readPermissions();
  }, []);

  useEffect(() => {
    readPermissions();
  }, [readPermissions]);

  const value: ExposureContextValue = {
    ...state,
    start,
    stop,
    pause,
    configure,
    checkExposure,
    simulateExposure,
    getDiagnosisKeys,
    exposureEnabled,
    authoriseExposure,
    deleteAllData,
    supportsExposureApi,
    getCloseContacts,
    getLogData,
    triggerUpdate,
    deleteExposureData,
    readPermissions,
    askPermissions,
    setExposureState: setState,
    cancelNotifications
  };

  return (
    <ExposureContext.Provider value={value}>
      {children}
    </ExposureContext.Provider>
  );
};

export const useExposure = () => useContext(ExposureContext);
