#!/bin/bash
# set-home.sh — makes TvHome the Home screen and sets its screensaver.
# Usage:  TV=192.168.1.50:5555 ./scripts/set-home.sh
# Disabling the stock launcher is reversible (scripts/restore.sh). Ask the TV owner first.
set -u
ADB="${ADB:-adb}"
TV="${TV:?set TV=<tv-ip>:5555}"
P=com.moath.tvhome
sh() { "$ADB" -s "$TV" shell "$@"; }
"$ADB" connect "$TV" >/dev/null
sh cmd package set-home-activity $P/.MainActivity
# Google TV's own launcher outranks third-party ones. Find it with: adb shell pm list packages | grep -i launcher
for L in com.google.android.apps.tv.launcherx com.google.android.tvlauncher; do
  if sh pm list packages | grep -q "package:$L$"; then
    sh pm disable-user --user 0 $L && echo "$L" >> "$(dirname "$0")/disabled-packages.txt"
  fi
done
sh settings put secure screensaver_components $P/$P.dashboard.PrayerDashboardDream
sh settings put secure screensaver_enabled 1
sh input keyevent KEYCODE_HOME
echo "✅ TvHome is the Home screen"
