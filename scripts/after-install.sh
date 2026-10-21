#!/bin/bash
# after-install.sh — run after every install/update of TvHome on the TV.
# Grants the permissions TvHome needs, enables its system service and checks that it runs.
# Usage:  TV=192.168.1.50:5555 ./scripts/after-install.sh      (ADB=/path/to/adb optional)
set -u
ADB="${ADB:-adb}"
TV="${TV:?set TV=<tv-ip>:5555}"
P=com.moath.tvhome
sh() { "$ADB" -s "$TV" shell "$@"; }
"$ADB" connect "$TV" >/dev/null
sh pm grant $P android.permission.WRITE_SECURE_SETTINGS
sh pm grant $P android.permission.RECORD_AUDIO
sh appops set $P SCHEDULE_EXACT_ALARM allow
sh appops set $P GET_USAGE_STATS allow
sh appops set $P WRITE_SETTINGS allow
sh appops set $P APP_AUTO_START allow 2>/dev/null   # TCL TVs: without it the system blocks the service (TclAppBoot)
sh am start -n $P/.MainActivity >/dev/null
sleep 3
# Re-binding the accessibility service makes the system pick up the new build.
sh settings put secure enabled_accessibility_services '""'
sleep 2
sh settings put secure enabled_accessibility_services $P/$P.system.SystemUiService
sh settings put secure accessibility_enabled 1
sleep 5
if sh dumpsys accessibility | grep -q "$P/.system.SystemUiService\|$P/$P.system.SystemUiService"; then
  echo "✅ TvHome system service is running"
else
  echo "❌ The system service did not start. Check: adb logcat | grep -i -E 'TclAppBoot|accessibility'"
fi
