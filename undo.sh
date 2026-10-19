#!/bin/sh
# Restore recorded previous Home, then disable Luma Home without deleting it.
set -eu
cd "$(dirname "$0")"
TV=$1
case "$TV" in *.*.*.*:*) ;; *.*.*.*) TV=$TV:5555 ;; esac
TAG=$(printf '%s' "$TV" | tr -c 'A-Za-z0-9_.-' '_')
RECORD="out/previous-home-$TAG.txt"
[ -f "$RECORD" ] || { echo 'No previous Home recorded. Select and verify another home before disabling Luma Home.' >&2; exit 1; }
PREVIOUS=$(cat "$RECORD")
printf '%s' "$PREVIOUS" | grep -Eq '^[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+$' || exit 1
PACKAGE=$(printf '%s' "$PREVIOUS" | cut -d/ -f1)
adb -s "$TV" shell cmd role add-role-holder --user 0 android.app.role.HOME "$PACKAGE" >/dev/null 2>&1 || true
adb -s "$TV" shell cmd package set-home-activity "$PREVIOUS"
ACTUAL=$(adb -s "$TV" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -1)
[ "$ACTUAL" = "$PREVIOUS" ] || { echo 'Previous Home did not become active. Luma Home remains enabled.' >&2; exit 1; }
adb -s "$TV" shell pm disable-user --user 0 com.lumahome.launcher
adb -s "$TV" shell input keyevent KEYCODE_HOME
echo 'Previous Home restored. Luma Home is disabled and remains installed.'
