// Flat ESLint config for CRAG Web Console.
// Enforces MVVM boundaries: View/pages must not import transport (services/http);
// features may not reach into another feature's internals.
import js from '@eslint/js';
import tseslint from '@typescript-eslint/eslint-plugin';
import tsparser from '@typescript-eslint/parser';
import reactPlugin from 'eslint-plugin-react';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';

export default [
  js.configs.recommended,
  {
    files: ['**/*.{ts,tsx,js,jsx,mjs}'],
    languageOptions: {
      parser: tsparser,
      parserOptions: {
        ecmaFeatures: { jsx: true },
        sourceType: 'module',
        ecmaVersion: 2022,
      },
      globals: {
        ...globals.browser,
        ...globals.node,
      },
    },
    plugins: {
      '@typescript-eslint': tseslint,
      react: reactPlugin,
      'react-hooks': reactHooks,
    },
    settings: { react: { version: 'detect' } },
    rules: {
      ...tseslint.configs.recommended.rules,
      ...reactPlugin.configs.recommended.rules,
      ...reactHooks.configs.recommended.rules,
      'react/react-in-jsx-scope': 'off',
      'react/prop-types': 'off',
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/consistent-type-imports': [
        'error',
        { prefer: 'type-imports', fixStyle: 'inline-type-imports' },
      ],
    },
  },
  {
    // View layer: pages and feature components must not import transport.
    files: ['src/pages/**/*.{ts,tsx}', 'src/features/**/components/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-syntax': [
        'error',
        {
          selector:
            "ImportDeclaration[source.value=/^(@services\\/http|\\.\\.\\/.*services\\/http|src\\/services\\/http|\\.\\.?\\/.*\\bservices\\/http)/]",
          message:
            'View/page components must not import from services/http. Use a ViewModel and the API client instead (architecture.md).',
        },
      ],
    },
  },
  {
    // Features must not import another feature's internal files.
    files: ['src/features/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-syntax': [
        'error',
        {
          selector:
            "ImportDeclaration[source.value=/^(\\.\\.\\/|@features\\/)(?!\\.\\/)(?!\\.\\.\\/shared)/]",
          message:
            'Cross-feature internal imports are forbidden. Depend on shared/ or a feature public entry (architecture.md).',
        },
      ],
    },
  },
  {
    files: ['**/*.config.{ts,js,mjs}', 'server/**/*.{ts,js,mjs}', 'tests/**/*.{ts,tsx}'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },
  {
    ignores: ['dist/**', 'node_modules/**', 'coverage/**', 'playwright-report/**', 'test-results/**'],
  },
];
