#!/usr/bin/env bash
# Build Cosy Home, install it on the TV over the network and make it the Home screen.
#   TV_HOST=192.168.1.50 ./install.sh
# Run it again after changing cosy.properties. Undo: see README, "Go back to the stock launcher".
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the IP address of the TV, e.g. TV_HOST=192.168.1.50 $0}"
ADB="${ADB:-adb}"
TV="$TV_HOST"; [[ "$TV" == *:* ]] || TV="$TV:5555"
PKG=com.cosyhome.tv
# Stock launchers this script will disable. Anything else that owns Home is left alone.
KNOWN_STOCK="com.google.android.tvlauncher com.google.android.apps.tv.launcherx com.google.android.leanbacklauncher"

home() {
  "$ADB" -s "$TV" shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1 | tr -d '\r'
}

"$ADB" connect "$TV" >/dev/null
[ -f local.properties ] || [ -n "${ANDROID_HOME:-}" ] || {
  echo "Set ANDROID_HOME, or write sdk.dir=<path to the Android SDK> into local.properties." >&2; exit 1; }
./gradlew assembleRelease -q --console=plain
"$ADB" -s "$TV" install -r app/build/outputs/apk/release/app-release.apk

NOW=$(home)
case "$NOW" in
  "$PKG"/*) echo "Cosy Home is the Home screen."; exit 0 ;;
esac
STOCK="${STOCK_LAUNCHER:-${NOW%%/*}}"
case " $KNOWN_STOCK " in
  *" $STOCK "*) ;;
  *)
    echo "Home belongs to '${NOW:-nothing}', which isn't a stock launcher this script knows, so nothing" >&2
    echo "was disabled. 'android' means Android's chooser: pick Cosy Home on the TV. To disable a" >&2
    echo "launcher yourself, run again with STOCK_LAUNCHER=<package>." >&2
    exit 1 ;;
esac

# The stock launcher outranks every other Home app while it is enabled, so it has to go. Start Cosy
# Home first, so the TV is never without a Home screen in between.
"$ADB" -s "$TV" shell am start -n "$PKG/.HomeActivity" >/dev/null
"$ADB" -s "$TV" shell pm disable-user --user 0 "$STOCK"
NOW=$(home)
case "$NOW" in
  "$PKG"/*)
    echo "Cosy Home is the Home screen. $STOCK is disabled."
    echo "Wait ten seconds before switching the TV off at the wall: some TVs save the change late." ;;
  *)
    "$ADB" -s "$TV" shell pm enable "$STOCK" >/dev/null
    echo "Home went to '${NOW:-nothing}' instead of Cosy Home, so $STOCK was re-enabled." >&2
    echo "On Google TV the setup wizard (com.google.android.tungsten.setupwraith) can claim Home;" >&2
    echo "cleanyourtv's troubleshooting guide covers it." >&2
    exit 1 ;;
esac
