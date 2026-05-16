import type { CapacitorConfig } from '@capacitor/cli';

/**
 * Native Android wrapper for calliope-campus.
 *
 * The WebView loads campus from Cloudflare (rc05 by default). All BLE
 * traffic routes through @capacitor-community/bluetooth-le → native
 * BluetoothManager, so the OS-level bond is owned by THIS app (not by
 * Chrome, not by the OS bluetooth picker in isolation). System pair
 * prompt fires natively, bond persists across app launches.
 *
 * To target a different campus build:
 *   CAPACITOR_SERVER_URL=https://campus.calliope.cc pnpm sync
 *
 * To ship an offline-first variant: set webDir to a baked campus build
 * and unset CAPACITOR_SERVER_URL.
 *
 * appId uses '.campus' suffix during development so installs don't
 * collide with the existing Calliope mini flash app (cc.calliope.mini).
 * Strip the suffix to '.mini' once we're ready to replace the existing
 * Play Store listing.
 */
const remoteUrl = process.env.CAPACITOR_SERVER_URL ?? 'https://rc05.calliope-campus.pages.dev';

const config: CapacitorConfig = {
  appId: 'cc.calliope.mini.campus',
  appName: 'Calliope Campus',
  webDir: 'www',
  server: {
    url: remoteUrl,
    cleartext: false,
    androidScheme: 'https',
    allowNavigation: [
      new URL(remoteUrl).hostname,
      'campus.calliope.cc',
      '*.calliope-campus.pages.dev',
      'makecode.calliope.cc',
      'python.calliope.cc',
    ],
  },
  plugins: {
    BluetoothLe: {
      displayStrings: {
        scanning: 'Suche Calliope mini…',
        cancel: 'Abbrechen',
        availableDevices: 'Gefundene Geräte',
        noDeviceFound: 'Kein Calliope mini gefunden',
      },
    },
  },
};

export default config;
