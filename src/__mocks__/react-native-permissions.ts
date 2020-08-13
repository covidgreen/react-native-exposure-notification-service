import * as RNPermission from 'react-native-permissions/lib/typescript';
const {
  PERMISSIONS,
  RESULTS
} = require('react-native-permissions/lib/commonjs/constants.js');

export {PERMISSIONS, RESULTS};

// eslint-disable-next-line @typescript-eslint/no-unused-vars
export async function check(permission: RNPermission.Permission) {
  jest.fn();
}
