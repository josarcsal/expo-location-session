const fs = require('node:fs');
const path = require('node:path');
const { withDangerousMod } = require('expo/config-plugins');

const NETWORK_SECURITY_CONFIG = `<network-security-config>
  <base-config cleartextTrafficPermitted="true">
    <trust-anchors>
      <certificates src="system" />
      <certificates src="@raw/els_dev_ca" />
    </trust-anchors>
  </base-config>
</network-security-config>
`;

module.exports = (config) =>
  withDangerousMod(config, [
    'android',
    async (cfg) => {
      const ca = process.env.ELS_DEV_CA;
      if (!ca) return cfg;
      const debug = path.join(cfg.modRequest.platformProjectRoot, 'app/src/debug');
      await fs.promises.mkdir(path.join(debug, 'res/raw'), { recursive: true });
      await fs.promises.mkdir(path.join(debug, 'res/xml'), { recursive: true });
      await fs.promises.copyFile(path.resolve(ca), path.join(debug, 'res/raw/els_dev_ca.pem'));
      await fs.promises.writeFile(path.join(debug, 'res/xml/network_security_config.xml'), NETWORK_SECURITY_CONFIG);
      const manifestPath = path.join(debug, 'AndroidManifest.xml');
      const manifest = await fs.promises.readFile(manifestPath, 'utf8');
      if (!manifest.includes('android:networkSecurityConfig')) {
        await fs.promises.writeFile(
          manifestPath,
          manifest.replace(
            '<application ',
            '<application android:networkSecurityConfig="@xml/network_security_config" ',
          ),
        );
      }
      return cfg;
    },
  ]);
