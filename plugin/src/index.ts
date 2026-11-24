import {
  AndroidConfig,
  createRunOncePlugin,
  withAndroidManifest,
  withDangerousMod,
  withFinalizedMod,
  withInfoPlist,
  WarningAggregator,
  type ConfigPlugin,
} from 'expo/config-plugins';
import fs from 'node:fs';
import path from 'node:path';

const pkg = require('../../package.json') as { name: string; version: string };

export type LocationSessionPluginProps = {
  locationWhenInUseText?: string;
  locationAlwaysText?: string;
  enableBackgroundLocationPermission?: boolean;
  androidNotificationIcon?: string;
  backgroundSync?: boolean;
};

const DEFAULT_WHEN_IN_USE_TEXT = 'Allow $(PRODUCT_NAME) to access your location';
const DEFAULT_ALWAYS_TEXT = 'Allow $(PRODUCT_NAME) to access your location in the background';
const ICON_NAME = 'expo_location_session_notification_icon';
const QUEUE_SYNC_IDENTIFIER = '$(PRODUCT_BUNDLE_IDENTIFIER).els.queue-sync';
const BACKGROUND_SYNC_META_DATA = 'expo-location-session.backgroundSync';

const withIos: ConfigPlugin<LocationSessionPluginProps> = (config, props) =>
  withInfoPlist(config, (cfg) => {
    const plist = cfg.modResults;
    plist.NSLocationWhenInUseUsageDescription =
      props.locationWhenInUseText ?? plist.NSLocationWhenInUseUsageDescription ?? DEFAULT_WHEN_IN_USE_TEXT;
    plist.NSLocationAlwaysAndWhenInUseUsageDescription =
      props.locationAlwaysText ?? plist.NSLocationAlwaysAndWhenInUseUsageDescription ?? DEFAULT_ALWAYS_TEXT;
    const modes: string[] = Array.isArray(plist.UIBackgroundModes) ? plist.UIBackgroundModes : [];
    const required = props.backgroundSync ? ['location', 'processing'] : ['location'];
    const missing = required.filter((mode) => !modes.includes(mode));
    if (missing.length > 0) plist.UIBackgroundModes = [...modes, ...missing];
    if (props.backgroundSync) {
      const current = plist.BGTaskSchedulerPermittedIdentifiers;
      const identifiers = Array.isArray(current) ? current : [];
      if (!identifiers.includes(QUEUE_SYNC_IDENTIFIER))
        plist.BGTaskSchedulerPermittedIdentifiers = [...identifiers, QUEUE_SYNC_IDENTIFIER];
    }
    return cfg;
  });

const withAndroidBackgroundSync: ConfigPlugin = (config) =>
  withAndroidManifest(config, (cfg) => {
    AndroidConfig.Manifest.addMetaDataItemToMainApplication(
      AndroidConfig.Manifest.getMainApplicationOrThrow(cfg.modResults),
      BACKGROUND_SYNC_META_DATA,
      'true',
    );
    return cfg;
  });

const RECEIVE_BOOT_COMPLETED = 'android.permission.RECEIVE_BOOT_COMPLETED';
const STARTUP_PROVIDER = 'androidx.startup.InitializationProvider';
const WORK_MANAGER_INITIALIZER = 'androidx.work.WorkManagerInitializer';

type ManifestItem = { $: Record<string, string | undefined>; 'meta-data'?: ManifestItem[] };

const withoutWorkManager: ConfigPlugin = (config) =>
  withAndroidManifest(config, (cfg) => {
    const manifest = AndroidConfig.Manifest.ensureToolsAvailable(cfg.modResults);
    const root = manifest.manifest as unknown as { 'uses-permission'?: ManifestItem[] };
    const permissions = (root['uses-permission'] ??= []);
    if (!permissions.some((item) => item.$['android:name'] === RECEIVE_BOOT_COMPLETED))
      permissions.push({ $: { 'android:name': RECEIVE_BOOT_COMPLETED, 'tools:node': 'remove' } });
    const application = AndroidConfig.Manifest.getMainApplicationOrThrow(manifest) as unknown as {
      provider?: ManifestItem[];
    };
    const providers = (application.provider ??= []);
    let provider = providers.find((item) => item.$['android:name'] === STARTUP_PROVIDER);
    if (!provider) {
      provider = {
        $: {
          'android:name': STARTUP_PROVIDER,
          'android:authorities': '${applicationId}.androidx-startup',
          'android:exported': 'false',
          'tools:node': 'merge',
        },
      };
      providers.push(provider);
    }
    const entries = (provider['meta-data'] ??= []);
    if (!entries.some((item) => item.$['android:name'] === WORK_MANAGER_INITIALIZER))
      entries.push({
        $: { 'android:name': WORK_MANAGER_INITIALIZER, 'android:value': 'androidx.startup', 'tools:node': 'remove' },
      });
    cfg.modResults = manifest;
    return cfg;
  });

const withBackgroundPermission: ConfigPlugin = (config) =>
  withAndroidManifest(config, (cfg) => {
    AndroidConfig.Permissions.ensurePermissions(cfg.modResults, ['android.permission.ACCESS_BACKGROUND_LOCATION']);
    return cfg;
  });

const BACKUP_RULES: [attribute: string, value: string][] = [
  ['android:fullBackupContent', '@xml/expo_location_session_backup_rules'],
  ['android:dataExtractionRules', '@xml/expo_location_session_data_extraction_rules'],
];

const BACKUP_EXCLUDES: [domain: string, path: string][] = [
  ['sharedpref', 'expo_location_session.xml'],
  ['sharedpref', 'expo_location_session_debug.xml'],
  ['sharedpref', 'expo_location_session_targets.xml'],
  ['file', 'expo-location-session/'],
];

const hasAttribute = (tag: string, name: string, value: string) =>
  new RegExp(`\\s${name}\\s*=\\s*(["'])${value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\1`).test(tag);

const hasExclude = (body: string, [domain, file]: [string, string]) =>
  (body.match(/<exclude\b[^>]*>/g) ?? []).some(
    (tag) => hasAttribute(tag, 'domain', domain) && hasAttribute(tag, 'path', file),
  );

const maskComments = (xml: string) => xml.replace(/<!--[\s\S]*?-->/g, (comment) => comment.replace(/[^\n]/g, ' '));

const excludeLines = (body: string, indent: string) =>
  BACKUP_EXCLUDES.filter((rule) => !hasExclude(body, rule))
    .map(([domain, file]) => `${indent}<exclude domain="${domain}" path="${file}" />\n`)
    .join('');

const insertInto = (xml: string, tag: string, content: (body: string, indent: string) => string) => {
  const masked = maskComments(xml);
  const open = new RegExp(`<${tag}\\b[^>]*?(/?)>`).exec(masked);
  if (!open) return undefined;
  const lineStart = masked.lastIndexOf('\n', open.index) + 1;
  const indent = /^[ \t]*/.exec(masked.slice(lineStart))?.[0] ?? '';
  const start = open.index + open[0].length;
  if (open[1]) {
    const added = content('', `${indent}  `);
    if (!added) return xml;
    const openTag = open[0].replace(/\s*\/>$/, '>');
    return `${xml.slice(0, open.index)}${openTag}\n${added}${indent}</${tag}>${xml.slice(start)}`;
  }
  const end = masked.indexOf(`</${tag}>`, start);
  if (end < 0) return undefined;
  const added = content(masked.slice(start, end), `${indent}  `);
  if (!added) return xml;
  const closeLineStart = masked.lastIndexOf('\n', end) + 1;
  if (masked.slice(closeLineStart, end).trim() === '')
    return `${xml.slice(0, closeLineStart)}${added}${xml.slice(closeLineStart)}`;
  return `${xml.slice(0, end)}\n${added}${xml.slice(end)}`;
};

const withExcludes = (xml: string) => {
  const masked = maskComments(xml);
  if (/<full-backup-content\b/.test(masked)) return insertInto(xml, 'full-backup-content', excludeLines);
  if (!/<data-extraction-rules\b/.test(masked)) return undefined;
  for (const section of ['cloud-backup', 'device-transfer']) {
    const next =
      insertInto(xml, section, excludeLines) ??
      insertInto(xml, 'data-extraction-rules', (_, indent) => {
        const lines = excludeLines('', `${indent}  `);
        return `${indent}<${section}>\n${lines}${indent}</${section}>\n`;
      });
    if (next === undefined) return undefined;
    xml = next;
  }
  return xml;
};

const withBackupRules: ConfigPlugin = (config) => {
  config = withAndroidManifest(config, (cfg) => {
    const attributes: Record<string, string | undefined> = AndroidConfig.Manifest.getMainApplicationOrThrow(
      cfg.modResults,
    ).$;
    if (attributes['android:allowBackup'] === 'false') return cfg;
    for (const [attribute, value] of BACKUP_RULES)
      if (attributes[attribute] === undefined) attributes[attribute] = value;
    return cfg;
  });
  return withFinalizedMod(config, [
    'android',
    async (cfg) => {
      const manifest = await AndroidConfig.Manifest.readAndroidManifestAsync(
        await AndroidConfig.Paths.getAndroidManifestAsync(cfg.modRequest.projectRoot),
      );
      const attributes: Record<string, string | undefined> =
        AndroidConfig.Manifest.getMainApplicationOrThrow(manifest).$;
      if (attributes['android:allowBackup'] === 'false') return cfg;
      for (const [attribute, value] of BACKUP_RULES) {
        const current = attributes[attribute];
        if (current === undefined || current === value) continue;
        const name = /^@xml\/(\w+)$/.exec(current)?.[1];
        const file = name && path.join(cfg.modRequest.platformProjectRoot, 'app/src/main/res/xml', `${name}.xml`);
        const xml = file && fs.existsSync(file) ? await fs.promises.readFile(file, 'utf8') : undefined;
        const updated = xml === undefined ? undefined : withExcludes(xml);
        if (file && xml !== undefined && updated !== undefined) {
          if (updated !== xml) await fs.promises.writeFile(file, updated);
          continue;
        }
        WarningAggregator.addWarningAndroid(
          pkg.name,
          `${attribute} is already set to ${current}, which is not a rules file in the app's res/xml that the plugin can edit. Add the expo-location-session excludes to it (see the README, "Backup").`,
        );
      }
      return cfg;
    },
  ]);
};

const withNotificationIcon: ConfigPlugin<string> = (config, icon) =>
  withDangerousMod(config, [
    'android',
    async (cfg) => {
      const source = path.resolve(cfg.modRequest.projectRoot, icon);
      if (!fs.existsSync(source))
        throw new Error(`[expo-location-session] androidNotificationIcon not found: ${source}`);
      const dir = path.join(cfg.modRequest.platformProjectRoot, 'app/src/main/res/drawable');
      await fs.promises.mkdir(dir, { recursive: true });
      await fs.promises.copyFile(source, path.join(dir, `${ICON_NAME}.png`));
      return cfg;
    },
  ]);

const withLocationSession: ConfigPlugin<LocationSessionPluginProps | void> = (config, props) => {
  const options = props ?? {};
  config = withIos(config, options);
  if (options.enableBackgroundLocationPermission ?? true) config = withBackgroundPermission(config);
  config = withBackupRules(config);
  config = options.backgroundSync ? withAndroidBackgroundSync(config) : withoutWorkManager(config);
  if (options.androidNotificationIcon) config = withNotificationIcon(config, options.androidNotificationIcon);
  return config;
};

export default createRunOncePlugin(withLocationSession, pkg.name, pkg.version);
