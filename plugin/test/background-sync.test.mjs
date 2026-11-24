import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { test } from 'node:test';

const require = createRequire(import.meta.url);
const plugin = require('../build/index.js').default;

const IDENTIFIER = '$(PRODUCT_BUNDLE_IDENTIFIER).els.queue-sync';
const META_DATA = 'expo-location-session.backgroundSync';
const passThrough = async (config) => config;

async function runMod(platform, modName, props, modResults) {
  const config = plugin({ name: 'app', slug: 'app', mods: { [platform]: { [modName]: passThrough } } }, props);
  const result = await config.mods[platform][modName]({
    ...config,
    modResults,
    modRequest: { platform, modName, projectRoot: '/app', platformProjectRoot: `/app/${platform}`, introspect: false },
  });
  return result.modResults;
}

const infoPlist = (props, plist) => runMod('ios', 'infoPlist', props, plist);

const emptyManifest = () => ({
  manifest: {
    $: { 'xmlns:android': 'http://schemas.android.com/apk/res/android' },
    application: [{ $: { 'android:name': '.MainApplication' } }],
  },
});

const manifest = (props, start = emptyManifest()) => runMod('android', 'manifest', props, start);

const metaData = (result) => (result.manifest.application[0]['meta-data'] ?? []).map((item) => item.$['android:name']);

test('backgroundSync on is idempotent and keeps existing iOS entries', async () => {
  const existing = {
    UIBackgroundModes: ['audio'],
    BGTaskSchedulerPermittedIdentifiers: ['com.app.refresh'],
  };
  const once = await infoPlist({ backgroundSync: true }, structuredClone(existing));
  const twice = await infoPlist({ backgroundSync: true }, structuredClone(once));
  assert.deepEqual(twice.UIBackgroundModes, ['audio', 'location', 'processing']);
  assert.deepEqual(twice.BGTaskSchedulerPermittedIdentifiers, ['com.app.refresh', IDENTIFIER]);
  assert.deepEqual(twice, once);
});

test('backgroundSync off adds no task identifier or processing mode', async () => {
  const plist = await infoPlist({}, { UIBackgroundModes: ['audio'] });
  assert.deepEqual(plist.UIBackgroundModes, ['audio', 'location']);
  assert.equal(plist.BGTaskSchedulerPermittedIdentifiers, undefined);
});

test('backgroundSync adds the Android meta-data once and only when on', async () => {
  const once = await manifest({ backgroundSync: true });
  const twice = await manifest({ backgroundSync: true }, structuredClone(once));
  assert.deepEqual(metaData(twice), [META_DATA]);
  assert.deepEqual(metaData(await manifest({})), []);
});

const BOOT = 'android.permission.RECEIVE_BOOT_COMPLETED';
const STARTUP = 'androidx.startup.InitializationProvider';
const INITIALIZER = 'androidx.work.WorkManagerInitializer';

const bootPermissions = (result) =>
  (result.manifest['uses-permission'] ?? []).map((item) => item.$).filter((item) => item['android:name'] === BOOT);
const startupProviders = (result) =>
  (result.manifest.application[0].provider ?? []).filter((item) => item.$['android:name'] === STARTUP);

test('backgroundSync off removes the WorkManager initializer and boot permission, once', async () => {
  const once = await manifest({});
  const twice = await manifest({}, structuredClone(once));
  assert.deepEqual(twice, once);
  assert.equal(once.manifest.$['xmlns:tools'], 'http://schemas.android.com/tools');
  assert.deepEqual(bootPermissions(once), [{ 'android:name': BOOT, 'tools:node': 'remove' }]);
  const providers = startupProviders(once);
  assert.equal(providers.length, 1);
  assert.equal(providers[0].$['tools:node'], 'merge');
  assert.equal(providers[0].$['android:authorities'], '${applicationId}.androidx-startup');
  assert.deepEqual(
    providers[0]['meta-data'].map((item) => item.$),
    [{ 'android:name': INITIALIZER, 'android:value': 'androidx.startup', 'tools:node': 'remove' }],
  );
});

test('backgroundSync off keeps a boot permission and startup provider the app declares itself', async () => {
  const start = emptyManifest();
  start.manifest['uses-permission'] = [{ $: { 'android:name': BOOT } }];
  start.manifest.application[0].provider = [
    {
      $: { 'android:name': STARTUP, 'android:authorities': 'com.app.androidx-startup', 'android:exported': 'false' },
      'meta-data': [{ $: { 'android:name': 'com.app.Init', 'android:value': 'androidx.startup' } }],
    },
  ];
  const result = await manifest({}, start);
  assert.deepEqual(bootPermissions(result), [{ 'android:name': BOOT }]);
  const providers = startupProviders(result);
  assert.equal(providers.length, 1);
  assert.equal(providers[0].$['android:authorities'], 'com.app.androidx-startup');
  assert.deepEqual(
    providers[0]['meta-data'].map((item) => item.$['android:name']),
    ['com.app.Init', INITIALIZER],
  );
});

test('backgroundSync on leaves WorkManager in the merged manifest', async () => {
  const result = await manifest({ backgroundSync: true });
  assert.deepEqual(bootPermissions(result), []);
  assert.deepEqual(startupProviders(result), []);
});
