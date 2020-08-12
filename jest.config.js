const {defaults: tsjPreset} = require('ts-jest/presets');
const rtlPreset = require('@testing-library/react-native/jest-preset');

module.exports = {
  ...tsjPreset,
  preset: '@testing-library/react-native',
  transform: {
    ...tsjPreset.transform,
    '\\.js$': '<rootDir>/node_modules/react-native/jest/preprocessor.js'
  },
  globals: {
    'ts-jest': {
      babelConfig: true
    }
  },
  testMatch: [
    '<rootDir>/src/__tests__/**/*.test.tsx',
    '<rootDir>/src/__tests__/**/*.test.ts'
  ],
  setupFiles: [...rtlPreset.setupFiles],
  setupFilesAfterEnv: ['@testing-library/react-native/cleanup-after-each'],
  // This is the only part which you can keep
  // from the above linked tutorial's config:
  cacheDirectory: '.jest/cache'
};
