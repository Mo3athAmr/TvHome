#!/bin/bash
# restore.sh — puts the TV back the way it was before TvHome.
# Re-enables every app listed in scripts/disabled-packages.txt (nothing is ever uninstalled),
# turns the accessibility service off and gives the Home button back to the stock launcher.
# Usage:  TV=192.168.1.50:5555 ./scripts/restore.sh  [--uninstall]
set -u
ADB="${ADB:-adb}"
TV="${TV:?set TV=<tv-ip>:5555}"
P=com.moath.tvhome
LIST="$(dirname "$0")/disabled-packages.txt"
sh() { "$ADB" -s "$TV" shell "$@"; }
"$ADB" connect "$TV" >/dev/null
if [ -f "$LIST" ]; then
  # Reverse order: a launcher's setup app comes back before the launcher itself.
  grep -v '^\s*#' "$LIST" | grep -v '^\s*$' | tail -r 2>/dev/null || grep -v '^\s*#' "$LIST" | grep -v '^\s*$' | tac
fi | while read -r pkg; do sh pm enable "$pkg"; done
sh settings put secure enabled_accessibility_services '""'
sh settings put secure accessibility_enabled 0
for L in com.google.android.apps.tv.launcherx com.google.android.tvlauncher; do
  sh pm list packages | grep -q "package:$L$" && sh cmd package set-home-activity "$(sh cmd package resolve-activity --brief -c android.intent.category.HOME $L | tail -1)" 2>/dev/null
done
sh settings delete secure screensaver_components
[ "${1:-}" = "--uninstall" ] && sh pm uninstall $P
sh input keyevent KEYCODE_HOME
echo "✅ Restored"
