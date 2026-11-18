import { fixupPluginRules } from '@eslint/compat';
import typescriptEslint from '@typescript-eslint/eslint-plugin';
import tsParser from '@typescript-eslint/parser';
import importX from 'eslint-plugin-import-x';
import reactHooks from 'eslint-plugin-react-hooks';
import reactNative from 'eslint-plugin-react-native';

export default [
  {
    ignores: [
      '**/node_modules/',
      'lib/',
      'nitrogen/generated/',
      'plugin/build/',
      'example/ios/',
      'example/android/',
      'ios/Core/.build/',
      '.superpowers/',
      '.claude/',
    ],
  },
  reactHooks.configs.flat.recommended,
  {
    plugins: {
      '@typescript-eslint': typescriptEslint,
      import: importX,
      'react-native': fixupPluginRules(reactNative),
    },
    languageOptions: {
      parser: tsParser,
      parserOptions: {
        ecmaFeatures: {
          jsx: true,
        },
      },
    },
  },
  {
    files: ['**/*.ts', '**/*.tsx'],
    rules: {
      '@typescript-eslint/no-shadow': ['error'],
      'no-shadow': 'off',
      'no-undef': 'off',
      '@typescript-eslint/consistent-type-imports': 'warn',
      'arrow-body-style': ['error'],
      curly: ['error', 'multi', 'consistent'],
      'react-native/no-unused-styles': 'error',
      'react-native/split-platform-components': 'error',
      'react-native/no-inline-styles': 'warn',
      'react-native/no-color-literals': 'warn',
      'react-native/no-raw-text': 'off',
      'import/order': [
        'error',
        {
          groups: ['external', 'internal', 'parent', 'sibling', 'index', 'unknown'],
          pathGroups: [
            { pattern: 'react-native', patternOptions: { matchBase: true }, group: 'external', position: 'before' },
            { pattern: 'react', patternOptions: { matchBase: true }, group: 'external', position: 'before' },
          ],
          pathGroupsExcludedImportTypes: ['react'],
          alphabetize: { order: 'asc', caseInsensitive: true },
        },
      ],
    },
  },
  {
    files: ['example/**'],
    rules: {
      'react-hooks/set-state-in-effect': 'off',
    },
  },
];
