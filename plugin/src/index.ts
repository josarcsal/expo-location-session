import {
  AndroidConfig,
  createRunOncePlugin,
  withAndroidManifest,
  withDangerousMod,
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
};

const DEFAULT_WHEN_IN_USE_TEXT = 'Allow $(PRODUCT_NAME) to access your location';
const DEFAULT_ALWAYS_TEXT = 'Allow $(PRODUCT_NAME) to access your location in the background';
const ICON_NAME = 'expo_location_session_notification_icon';

const withIos: ConfigPlugin<LocationSessionPluginProps> = (config, props) =>
  withInfoPlist(config, (cfg) => {
    const plist = cfg.modResults;
    plist.NSLocationWhenInUseUsageDescription =
      props.locationWhenInUseText ?? plist.NSLocationWhenInUseUsageDescription ?? DEFAULT_WHEN_IN_USE_TEXT;
    plist.NSLocationAlwaysAndWhenInUseUsageDescription =
      props.locationAlwaysText ?? plist.NSLocationAlwaysAndWhenInUseUsageDescription ?? DEFAULT_ALWAYS_TEXT;
    const modes: string[] = Array.isArray(plist.UIBackgroundModes) ? plist.UIBackgroundModes : [];
    if (!modes.includes('location')) plist.UIBackgroundModes = [...modes, 'location'];
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

const withBackupRules: ConfigPlugin = (config) =>
  withAndroidManifest(config, (cfg) => {
    const attributes: Record<string, string | undefined> = AndroidConfig.Manifest.getMainApplicationOrThrow(
      cfg.modResults,
    ).$;
    if (attributes['android:allowBackup'] === 'false') return cfg;
    for (const [attribute, value] of BACKUP_RULES) {
      const current = attributes[attribute];
      if (current === undefined) attributes[attribute] = value;
      else if (current !== value)
        WarningAggregator.addWarningAndroid(
          pkg.name,
          `${attribute} is already set to ${current}. Add the expo-location-session excludes to that file (see the README, "Backup").`,
        );
    }
    return cfg;
  });

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
  if (options.androidNotificationIcon) config = withNotificationIcon(config, options.androidNotificationIcon);
  return config;
};

export default createRunOncePlugin(withLocationSession, pkg.name, pkg.version);
