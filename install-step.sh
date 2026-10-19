#!/bin/sh
# Local adapter for the user's requirement to approve every TV change.
# Each invocation performs exactly one mutation. No package is ever removed.
set -eu
cd "$(dirname "$0")"
TARGET=${1:?TV IP or serial required}
APPS=${2:-}
case "$TARGET" in
  *.*.*.*:*) TV=$TARGET ;;
  *.*.*.*) TV=$TARGET:5555 ;;
  *) TV=$TARGET ;;
esac
[ "$(adb -s "$TV" get-state | tr -d '\r')" = device ] || exit 1
case "${LUMA_HOME_STEP:-}" in
  install)
    [ -f out/luma-home.apk ] || { echo 'Reviewed local APK is missing' >&2; exit 1; }
    # No uninstall fallback, launcher switch, app launch, or app disabling.
    adb -s "$TV" install -r out/luma-home.apk
    ;;
  apps)
    [ -n "$APPS" ] || { echo 'Explicit app list required' >&2; exit 1; }
    adb -s "$TV" shell am start -n com.lumahome.launcher/.MainActivity --es apps "\"$APPS\"" --es stamp "$(date +%s)"
    ;;
  home)
    PREVIOUS=$(adb -s "$TV" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -1)
    TAG=$(printf '%s' "$TV" | tr -c 'A-Za-z0-9_.-' '_')
    mkdir -p out
    if [ "$PREVIOUS" != com.lumahome.launcher/.MainActivity ]; then
      printf '%s\n' "$PREVIOUS" > "out/previous-home-$TAG.txt"
    fi
    adb -s "$TV" shell cmd role add-role-holder --user 0 android.app.role.HOME com.lumahome.launcher >/dev/null 2>&1 || true
    adb -s "$TV" shell cmd package set-home-activity com.lumahome.launcher/.MainActivity
    ;;
  show)
    adb -s "$TV" shell am start -n com.lumahome.launcher/.MainActivity
    ;;
  *) echo 'Choose one approved LUMA_HOME_STEP: install, apps, home, show' >&2; exit 1 ;;
esac
