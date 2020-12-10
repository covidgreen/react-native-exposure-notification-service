// Having to use doMock & manually hoist to the top before the ExposureProvider import
//   because ts-jest doesn't support out of scope mock variables beginning with mock
//   see https://github.com/kulshekhar/ts-jest/issues/1088
const mockNativeEventEmitterRemoveListenerSpy = jest.fn();
const mockNativeEventEmitterRemoveSpy = jest.fn();
const mockNativeEventEmitterAddListenerSpy = jest
  .fn()
  .mockReturnValue({remove: mockNativeEventEmitterRemoveSpy});
jest.doMock('react-native/Libraries/EventEmitter/NativeEventEmitter.js', () => {
  return jest.fn().mockImplementation(() => {
    return {
      addListener: mockNativeEventEmitterAddListenerSpy,
      removeAllListeners: jest.fn(),
      removeSubscription: jest.fn(),
      once: jest.fn(),
      removeCurrentListener: jest.fn(),
      removeListener: mockNativeEventEmitterRemoveListenerSpy,
      listeners: [],
      emit: jest.fn()
    };
  });
});

import React from 'react';
import {AppState, Alert} from 'react-native';
import {render, act} from '@testing-library/react-native';
import {renderHook} from '@testing-library/react-hooks';
import {mocked} from 'ts-jest/utils';
import NativeEventEmitter from 'react-native/Libraries/EventEmitter/NativeEventEmitter';

import {
  ExposureProvider,
  ExposureProviderProps,
  useExposure,
  getBundleId,
  getVersion,
  getConfigData
} from '../exposure-provider';

import ExposureNotificationModule, {
  KeyServerType
} from '../exposure-notification-module';
import {getPermissions, requestPermissions} from '../utils/permissions';
import {PermissionStatus} from '../types';

jest.mock('react-native/Libraries/EventEmitter/NativeEventEmitter');
jest.mock('react-native/Libraries/Alert/Alert');

jest.mock('../utils/permissions', () => ({
  getPermissions: jest.fn().mockResolvedValue({
    exposure: {status: 'allowed'},
    notifications: {status: 'allowed'}
  }),
  requestPermissions: jest.fn().mockResolvedValue(null)
}));

jest.mock('../exposure-notification-module', () => ({
  StatusState: {
    unavailable: 'unavailable'
  },
  StatusType: {
    starting: 'starting'
  },
  AuthorisedStatus: {
    unknown: 'unknown'
  },
  KeyServerType: {
    nearform: 'nearform'
  },
  authoriseExposure: jest.fn().mockResolvedValue(true),
  configure: jest.fn().mockResolvedValue(true),
  start: jest.fn().mockResolvedValue(true),
  stop: jest.fn().mockResolvedValue(true),
  checkExposure: jest.fn(),
  simulateExposure: jest.fn(),
  getDiagnosisKeys: jest.fn().mockResolvedValue([{keyData: 'mockKeyData'}]),
  deleteAllData: jest.fn().mockResolvedValue(true),
  deleteExposureData: jest.fn().mockResolvedValue(true),
  canSupport: jest.fn().mockResolvedValue(true),
  isSupported: jest.fn().mockResolvedValue(true),
  exposureEnabled: jest.fn().mockResolvedValue(true),
  isAuthorised: jest.fn().mockResolvedValue(true),
  pause: jest.fn().mockResolvedValue(true),
  getLogData: jest.fn().mockResolvedValue({}),
  bundleId: jest.fn().mockResolvedValue('testbundle'),
  version: jest.fn().mockResolvedValue({version: '123', build: '5'}),
  getConfigData: jest.fn().mockResolvedValue({lastRan: '123'}),
  triggerUpdate: jest.fn().mockResolvedValue(true),
  status: jest.fn().mockResolvedValue({
    state: 'active'
  }),
  getCloseContacts: jest.fn().mockResolvedValue([]),
  cancelNotifications: jest.fn()
}));

const mockConfig = {
  isReady: true,
  serverUrl: 'https://test.server.url',
  keyServerUrl: 'https://key.server.url',
  keyServerType: KeyServerType.nearform,
  authToken: 'testAuthToken',
  refreshToken: 'testRefreshToken',
  traceConfiguration: {
    exposureCheckInterval: 180,
    storeExposuresFor: 14
  },
  notificationTitle: 'testNotificationTitle',
  notificationDescription: 'testNotificationDescription',
  analyticsOptin: true,
  callbackNumber: '0123456789',
  notificationRepeat: 0,
  certList: 'cert12'
};

const ExposureProviderWithMockConfig: React.FC<Partial<
  ExposureProviderProps
>> = ({children, ...overrides}) => {
  return (
    <ExposureProvider {...mockConfig} {...overrides}>
      {children}
    </ExposureProvider>
  );
};

function flushPromises() {
  return new Promise((resolve) => setImmediate(resolve));
}

const renderExposureProvider = async (
  overrides?: React.PropsWithChildren<Partial<ExposureProviderProps>>
) => {
  const result = render(<ExposureProviderWithMockConfig {...overrides} />);
  await act(async () => {
    await flushPromises();
  });
  return result;
};

const renderExposureHook = async () => {
  const result = renderHook(() => useExposure(), {
    wrapper: ExposureProviderWithMockConfig
  });
  await act(async () => {
    await flushPromises();
  });
  return result;
};

beforeEach(() => {
  // suppress console logs
  jest.spyOn(global.console, 'log').mockImplementation(() => jest.fn());
});

afterEach(() => {
  jest.clearAllMocks();
});

describe('<ExposureProvider />', () => {
  it('listens & stops listening for exposure events', async () => {
    const {unmount} = await renderExposureProvider();
    expect(mocked(NativeEventEmitter)).toHaveBeenCalledWith(
      ExposureNotificationModule
    );
    expect(mockNativeEventEmitterAddListenerSpy).toHaveBeenCalledWith(
      'exposureEvent',
      expect.any(Function)
    );
    expect(mockNativeEventEmitterAddListenerSpy).toHaveBeenCalledWith(
      'exposureEvent',
      expect.any(Function)
    );

    unmount();

    expect(mockNativeEventEmitterRemoveListenerSpy).toHaveBeenCalledWith(
      'exposureEvent',
      expect.any(Function)
    );
    expect(mockNativeEventEmitterRemoveSpy).toHaveBeenCalledTimes(1);
  });

  it('renders', async () => {
    await renderExposureProvider();
  });

  it('reads permissions when mounted', async () => {
    await renderExposureProvider();
    expect(getPermissions).toHaveBeenCalledTimes(1);
  });

  it('listens to app state changes', async () => {
    await renderExposureProvider();
    expect(AppState.addEventListener).toHaveBeenCalledWith(
      'change',
      expect.any(Function)
    );
  });

  it('stops listening to app state changes when unmounted', async () => {
    const {unmount} = await renderExposureProvider();
    unmount();
    expect(AppState.removeEventListener).toHaveBeenCalledWith(
      'change',
      expect.any(Function)
    );
  });

  it('configures the exposure module with the expected properties', async () => {
    await renderExposureProvider();
    expect(ExposureNotificationModule.configure).toHaveBeenCalledWith({
      analyticsOptin: mockConfig.analyticsOptin,
      authToken: mockConfig.authToken,
      callbackNumber: mockConfig.callbackNumber,
      exposureCheckFrequency:
        mockConfig.traceConfiguration.exposureCheckInterval,
      notificationDesc: mockConfig.notificationDescription,
      notificationTitle: mockConfig.notificationTitle,
      refreshToken: mockConfig.refreshToken,
      serverURL: mockConfig.serverUrl,
      keyServerUrl: mockConfig.keyServerUrl,
      keyServerType: mockConfig.keyServerType,
      storeExposuresFor: mockConfig.traceConfiguration.storeExposuresFor,
      notificationRepeat: mockConfig.notificationRepeat,
      certList: mockConfig.certList
    });
  });

  it('configures, starts & gets close contacts when isReady & permissions are allowed', async () => {
    await renderExposureProvider();
    expect(ExposureNotificationModule.start).toHaveBeenCalled();
    expect(ExposureNotificationModule.getCloseContacts).toHaveBeenCalled();
  });

  it('does not configure & start when not isReady', async () => {
    await renderExposureProvider({isReady: false});
    expect(ExposureNotificationModule.start).not.toHaveBeenCalled();
  });

  it('does not configure & start when permissions are not allowed', async () => {
    mocked(getPermissions).mockResolvedValueOnce({
      exposure: {status: PermissionStatus.NotAllowed},
      notifications: {status: PermissionStatus.Allowed}
    });
    await renderExposureProvider();
    expect(ExposureNotificationModule.start).not.toHaveBeenCalled();
  });
});

describe('useExposure', () => {
  it('has expected default values', async () => {
    const {result} = await renderExposureHook();
    expect(result.current).toEqual({
      askPermissions: expect.any(Function),
      authoriseExposure: expect.any(Function),
      canSupport: true,
      checkExposure: expect.any(Function),
      configure: expect.any(Function),
      contacts: [],
      deleteAllData: expect.any(Function),
      deleteExposureData: expect.any(Function),
      enabled: true,
      exposureEnabled: expect.any(Function),
      getCloseContacts: expect.any(Function),
      getDiagnosisKeys: expect.any(Function),
      getLogData: expect.any(Function),
      cancelNotifications: expect.any(Function),
      initialised: true,
      isAuthorised: true,
      permissions: {
        exposure: {
          status: 'allowed'
        },
        notifications: {
          status: 'allowed'
        }
      },
      readPermissions: expect.any(Function),
      setExposureState: expect.any(Function),
      simulateExposure: expect.any(Function),
      start: expect.any(Function),
      status: {
        state: 'active'
      },
      stop: expect.any(Function),
      pause: expect.any(Function),
      supported: true,
      supportsExposureApi: expect.any(Function),
      triggerUpdate: expect.any(Function)
    });
  });

  describe('start()', () => {
    it('starts the exposure module', async () => {
      const {result} = await renderExposureHook();
      mocked(ExposureNotificationModule.start).mockClear();
      await act(async () => {
        await result.current.start();
      });
      expect(ExposureNotificationModule.start).toHaveBeenCalledTimes(1);
    });

    it('validates the status', async () => {
      const {result} = await renderExposureHook();
      mocked(ExposureNotificationModule.status).mockClear();
      mocked(ExposureNotificationModule.exposureEnabled).mockClear();
      await act(async () => {
        await result.current.start();
      });
      expect(ExposureNotificationModule.status).toHaveBeenCalledTimes(1);
      expect(ExposureNotificationModule.exposureEnabled).toHaveBeenCalledTimes(
        1
      );
    });

    it('gets close contacts', async () => {
      const {result} = await renderExposureHook();
      mocked(ExposureNotificationModule.getCloseContacts).mockClear();
      await act(async () => {
        await result.current.start();
      });
      expect(ExposureNotificationModule.getCloseContacts).toHaveBeenCalledTimes(
        1
      );
    });

    it('does not throw', async () => {
      const {result} = await renderExposureHook();
      mocked(ExposureNotificationModule.start).mockRejectedValueOnce(
        new Error('oops!')
      );
      await expect(result.current.start()).resolves.toBeUndefined();
    });
  });

  describe('stop()', () => {
    it('stops the exposure module', async () => {
      const {result} = await renderExposureHook();
      await act(async () => {
        await result.current.stop();
      });
      expect(ExposureNotificationModule.stop).toHaveBeenCalledTimes(1);
    });

    it('validates the status', async () => {
      const {result} = await renderExposureHook();
      mocked(ExposureNotificationModule.status).mockClear();
      await act(async () => {
        await result.current.stop();
      });
      expect(ExposureNotificationModule.status).toHaveBeenCalledTimes(1);
    });

    it('does not throw', async () => {
      const {result} = await renderExposureHook();
      mocked(ExposureNotificationModule.stop).mockRejectedValueOnce(
        new Error('oops!')
      );
      await expect(result.current.stop()).resolves.toBeUndefined();
    });
  });

  describe('pause()', () => {
    it('pauses the exposure module', async () => {
      const {result} = await renderExposureHook();
      await act(async () => {
        await result.current.pause();
      });
      expect(ExposureNotificationModule.pause).toHaveBeenCalledTimes(1);
    });

    it('validates the status', async () => {
      const {result} = await renderExposureHook();
      mocked(ExposureNotificationModule.status).mockClear();
      await act(async () => {
        await result.current.pause();
      });
      expect(ExposureNotificationModule.status).toHaveBeenCalledTimes(1);
    });
  });

  describe('configure()', () => {
    it('configures the exposure module', async () => {
      const {result} = await renderExposureHook();
      mocked(ExposureNotificationModule.configure).mockClear();
      await act(async () => {
        await result.current.configure();
      });
      expect(ExposureNotificationModule.configure).toHaveBeenCalledTimes(1);
      expect(ExposureNotificationModule.configure).toHaveBeenCalledWith({
        analyticsOptin: mockConfig.analyticsOptin,
        authToken: mockConfig.authToken,
        callbackNumber: mockConfig.callbackNumber,
        exposureCheckFrequency:
          mockConfig.traceConfiguration.exposureCheckInterval,
        notificationDesc: mockConfig.notificationDescription,
        notificationTitle: mockConfig.notificationTitle,
        refreshToken: mockConfig.refreshToken,
        serverURL: mockConfig.serverUrl,
        keyServerUrl: mockConfig.keyServerUrl,
        keyServerType: mockConfig.keyServerType,
        storeExposuresFor: mockConfig.traceConfiguration.storeExposuresFor,
        notificationRepeat: mockConfig.notificationRepeat,
        certList: mockConfig.certList
      });
    });

    it('does not throw', async () => {
      const {result} = await renderExposureHook();
      mocked(ExposureNotificationModule.start).mockRejectedValueOnce(
        new Error('oops!')
      );
      await expect(result.current.start()).resolves.toBeUndefined();
    });
  });

  describe('checkExposure()', () => {
    it('checks exposure on the exposure module', async () => {
      const {result} = await renderExposureHook();
      mocked(ExposureNotificationModule.checkExposure).mockClear();
      await act(async () => {
        await result.current.checkExposure(true);
      });
      expect(ExposureNotificationModule.checkExposure).toHaveBeenCalledTimes(1);
      expect(ExposureNotificationModule.checkExposure).toHaveBeenCalledWith(
        true
      );
    });
  });

  describe('getDiagnosisKeys()', () => {
    it('gets diagnosis keys from the exposure module', async () => {
      const {result} = await renderExposureHook();
      mocked(ExposureNotificationModule.getDiagnosisKeys).mockClear();
      let diagnosisKeys;
      await act(async () => {
        diagnosisKeys = await result.current.getDiagnosisKeys();
      });
      expect(ExposureNotificationModule.getDiagnosisKeys).toHaveBeenCalledTimes(
        1
      );
      expect(diagnosisKeys).toEqual([{keyData: 'mockKeyData'}]);
    });
  });

  describe('exposureEnabled()', () => {
    it('gets exposure enabled from the exposure module', async () => {
      const {result} = await renderExposureHook();
      mocked(ExposureNotificationModule.exposureEnabled).mockClear();
      let exposureEnabled;
      await act(async () => {
        exposureEnabled = await result.current.exposureEnabled();
      });
      expect(ExposureNotificationModule.exposureEnabled).toHaveBeenCalledTimes(
        1
      );
      expect(exposureEnabled).toBe(true);
    });
  });

  describe('authoriseExposure()', () => {
    it('gets authorise exposure from the exposure module', async () => {
      const {result} = await renderExposureHook();
      mocked(ExposureNotificationModule.authoriseExposure).mockClear();
      let authoriseExposure;
      await act(async () => {
        authoriseExposure = await result.current.authoriseExposure();
      });
      expect(
        ExposureNotificationModule.authoriseExposure
      ).toHaveBeenCalledTimes(1);
      expect(authoriseExposure).toBe(true);
    });
  });

  describe('deleteAllData()', () => {
    it('deletes all data on the exposure module & validates the status', async () => {
      const {result} = await renderExposureHook();
      mocked(ExposureNotificationModule.deleteAllData).mockClear();
      mocked(ExposureNotificationModule.status).mockClear();
      await act(async () => {
        await result.current.deleteAllData();
      });
      expect(ExposureNotificationModule.deleteAllData).toHaveBeenCalledTimes(1);
      expect(ExposureNotificationModule.status).toHaveBeenCalledTimes(1);
    });
  });

  describe('getCloseContacts()', () => {
    it('gets close contacts from the exposure module & updates the context', async () => {
      const {result} = await renderExposureHook();
      const mockCloseContacts = [
        {
          exposureAlertDate: 1234567,
          exposureDate: 1234567,
          attenuationDurations: [1, 2, 3],
          daysSinceLastExposure: 1,
          matchedKeyCount: 1,
          maxRiskScore: 1,
          riskScoreSumFullRange: 1,
          maxRiskScoreFullRange: 1
        }
      ];
      mocked(ExposureNotificationModule.getCloseContacts).mockClear();
      mocked(ExposureNotificationModule.getCloseContacts).mockResolvedValue(
        mockCloseContacts
      );
      await act(async () => {
        await result.current.getCloseContacts();
      });
      expect(ExposureNotificationModule.getCloseContacts).toHaveBeenCalledTimes(
        1
      );
      expect(result.current.contacts).toEqual(mockCloseContacts);
    });

    it('does load close contacts regardless if permissions granted or not', async () => {
      const mockCloseContacts = [
        {
          exposureAlertDate: 1234567,
          exposureDate: 1234567,
          attenuationDurations: [1, 2, 3],
          daysSinceLastExposure: 1,
          matchedKeyCount: 1,
          maxRiskScore: 1,
          riskScoreSumFullRange: 1,
          maxRiskScoreFullRange: 1
        }
      ];
      mocked(getPermissions).mockResolvedValueOnce({
        exposure: {status: PermissionStatus.NotAllowed},
        notifications: {status: PermissionStatus.Allowed}
      });
      const {result} = await renderExposureHook();
      mocked(ExposureNotificationModule.getCloseContacts).mockClear();
      await act(async () => {
        await result.current.getCloseContacts();
      });
      expect(ExposureNotificationModule.getCloseContacts).toHaveBeenCalled();
      expect(result.current.contacts).toEqual(mockCloseContacts);
    });

    it('does not throw', async () => {
      const {result} = await renderExposureHook();
      mocked(ExposureNotificationModule.getCloseContacts).mockRejectedValueOnce(
        new Error('oops!')
      );
      await expect(result.current.getCloseContacts()).resolves.toBeNull();
    });
  });

  describe('getLogData()', () => {
    it('gets log data from the exposure module', async () => {
      const {result} = await renderExposureHook();
      const mockLogData = {foo: 'bar'};
      mocked(ExposureNotificationModule.getLogData).mockClear();
      mocked(ExposureNotificationModule.getLogData).mockResolvedValue(
        mockLogData
      );
      let logData;
      await act(async () => {
        logData = await result.current.getLogData();
      });
      expect(ExposureNotificationModule.getLogData).toHaveBeenCalledTimes(1);
      expect(logData).toEqual(mockLogData);
    });

    it('does not throw', async () => {
      const {result} = await renderExposureHook();
      mocked(ExposureNotificationModule.getLogData).mockRejectedValueOnce(
        new Error('oops!')
      );
      await expect(result.current.getLogData()).resolves.toBeNull();
    });
  });

  describe('triggerUpdate()', () => {
    it('calls trigger update on the exposure module', async () => {
      const {result} = await renderExposureHook();
      mocked(ExposureNotificationModule.triggerUpdate).mockClear();
      await act(async () => {
        await result.current.triggerUpdate();
      });
      expect(ExposureNotificationModule.triggerUpdate).toHaveBeenCalledTimes(1);
    });

    it('shows an alert when the api is not available', async () => {
      const {result} = await renderExposureHook();
      mocked(ExposureNotificationModule.triggerUpdate).mockClear();
      mocked(ExposureNotificationModule.triggerUpdate).mockResolvedValueOnce(
        'api_not_available'
      );
      await act(async () => {
        await result.current.triggerUpdate();
      });
      expect(ExposureNotificationModule.triggerUpdate).toHaveBeenCalledTimes(1);
      expect(Alert.alert).toHaveBeenCalledTimes(1);
      expect(Alert.alert).toHaveBeenCalledWith(
        'API Not Available',
        'Google Exposure Notifications API not available on this device yet'
      );
    });

    it('does not throw', async () => {
      const {result} = await renderExposureHook();
      mocked(ExposureNotificationModule.triggerUpdate).mockRejectedValueOnce(
        new Error('oops!')
      );
      await expect(result.current.triggerUpdate()).resolves.toBeUndefined();
    });
  });

  describe('deleteExposureData()', () => {
    it('deletes exposure data on the exposure module & updates the context', async () => {
      const {result} = await renderExposureHook();
      mocked(ExposureNotificationModule.deleteExposureData).mockClear();
      await act(async () => {
        await result.current.deleteExposureData();
      });
      expect(
        ExposureNotificationModule.deleteExposureData
      ).toHaveBeenCalledTimes(1);
      expect(result.current.contacts).toEqual([]);
    });

    it('does not throw', async () => {
      const {result} = await renderExposureHook();
      mocked(
        ExposureNotificationModule.deleteExposureData
      ).mockRejectedValueOnce(new Error('oops!'));
      await expect(
        result.current.deleteExposureData()
      ).resolves.toBeUndefined();
    });
  });

  describe('cancelNotifications()', () => {
    it('cancels any repeating notifications', async () => {
      const {result} = await renderExposureHook();
      mocked(ExposureNotificationModule.cancelNotifications).mockClear();
      await act(async () => {
        await result.current.cancelNotifications();
      });
      expect(
        ExposureNotificationModule.cancelNotifications
      ).toHaveBeenCalledTimes(1);
    });
  });

  describe('readPermissions()', () => {
    it('gets current permissions & updates the state', async () => {
      const {result} = await renderExposureHook();
      const mockPermissions = {
        exposure: {status: PermissionStatus.NotAllowed},
        notifications: {status: PermissionStatus.NotAllowed}
      };
      mocked(getPermissions).mockResolvedValueOnce(mockPermissions);
      await act(async () => {
        await result.current.readPermissions();
      });
      expect(result.current.permissions).toEqual({
        exposure: {status: PermissionStatus.NotAllowed},
        notifications: {status: PermissionStatus.NotAllowed}
      });
    });
  });

  describe('askPermissions()', () => {
    it('requests & reads permissions', async () => {
      const {result} = await renderExposureHook();
      mocked(getPermissions).mockClear();
      await act(async () => {
        await result.current.askPermissions();
      });
      expect(requestPermissions).toHaveBeenCalledTimes(1);
      expect(getPermissions).toHaveBeenCalledTimes(1);
    });
  });

  describe('simulateExposure()', () => {
    it('simulates an exposure event triggering', async () => {
      const {result} = await renderExposureHook();
      mocked(ExposureNotificationModule.simulateExposure).mockClear();
      await act(async () => {
        await result.current.simulateExposure(10, 4);
      });
      expect(ExposureNotificationModule.simulateExposure).toHaveBeenCalledTimes(
        1
      );
      expect(ExposureNotificationModule.simulateExposure).toHaveBeenCalledWith(
        10, 4
      );
    });
  });

  describe('getVersion()', () => {
    it('gets the app build version details', async () => {
      mocked(ExposureNotificationModule.version).mockClear();
      const val = await getVersion();
      expect(ExposureNotificationModule.version).toHaveBeenCalledTimes(1);
      expect(val).toEqual({build: '5', version: '123'});
    });
  });

  describe('getConfigData()', () => {
    it('gets the config used by the module', async () => {
      mocked(ExposureNotificationModule.getConfigData).mockClear();
      const val = await getConfigData();
      expect(ExposureNotificationModule.getConfigData).toHaveBeenCalledTimes(1);
      expect(val).toEqual({lastRan: '123'});
    });
  });

  describe('bundleId()', () => {
    it('gets the app bundle id', async () => {
      mocked(ExposureNotificationModule.bundleId).mockClear();
      const val = await getBundleId();
      expect(ExposureNotificationModule.bundleId).toHaveBeenCalledTimes(1);
      expect(val).toEqual('testbundle');
    });
  });
});
