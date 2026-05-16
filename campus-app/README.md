# Calliope Campus — Android wrapper

Capacitor shell that loads [calliope-campus](https://github.com/calliope-edu/calliope-campus) from Cloudflare and routes BLE through Android's native `BluetoothManager` via [`@capacitor-community/bluetooth-le`](https://github.com/capacitor-community/bluetooth-le).

The native side owns the OS-level bond. System pair prompts fire reliably, bonds persist across launches.

## Layout

| Path | What |
| --- | --- |
| `capacitor.config.ts` | Wrapper config — `server.url` points at rc05 by default |
| `android/` | Auto-generated Android Studio project (Capacitor) |
| `www/` | Offline fallback page (only used when network is unreachable) |
| `package.json` | Node side: Capacitor CLI + the BLE plugin |

The repo's existing native flash app remains at the root and is unaffected by this directory.

## Build / run

Prerequisites: Node 22+, pnpm 10+, Android Studio with SDK 34+.

```bash
cd campus-app
pnpm install
pnpm sync                                    # copies www + plugin config into android/
pnpm open                                    # opens Android Studio — hit Run
# or
pnpm run                                     # builds + installs on a connected device
```

To point at a different campus build:

```bash
CAPACITOR_SERVER_URL=https://campus.calliope.cc pnpm sync
```

## Bundle ID

`cc.calliope.mini.campus` while we're testing — doesn't collide with the existing `cc.calliope.mini` Play Store install. Strip the `.campus` suffix when we're ready to replace the existing listing.
