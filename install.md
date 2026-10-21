# install.md — TvHome installation playbook for AI agents

You are an AI coding agent (Claude Code, Cursor, Codex, …) helping a person install **TvHome** on their
Android TV / Google TV from this repository. Work **with** the person: ask, explain briefly, then do.
Talk to them in their language (Arabic by default — Saudi/Gulf dialect is fine), keep messages short,
and never paste secrets back into the chat.

Read `README.md` once for the feature list. Everything you need to run is below.

---

## 0 · Golden rules (never break these)

1. **Never disable, uninstall or change anything on the TV without showing the person the exact list
   and getting a clear "yes".** Approval for one item is not approval for the next.
2. Disable apps **only** with `pm disable-user --user 0 <pkg>` (reversible). Never `pm uninstall` a
   system app, never delete data. Append every disabled package to `scripts/disabled-packages.txt`
   so `scripts/restore.sh` can bring it back.
3. Never disable: Google Play services, Google Play Store, Settings, Wi-Fi/Bluetooth/network stacks,
   the remote-control service (`com.google.android.tv.remote.service`), system update, input methods,
   audio/TTS services, the TV's input/tuner services, or the Google assistant (`com.google.android.katniss`
   — TvHome uses it as Rafiq's ears).
4. Ask before disabling the stock launcher (`set-home.sh` does it; it is reversible).
5. Keys live only in `local.properties` (git-ignored). Never commit it, never print it, never put a key
   in source code, a URL or a log.
6. Don't change system settings the person didn't ask for (Bluetooth, network, display…).
7. Keep the computer usable: build with low priority (`nice -n 19 … --no-daemon --max-workers=1`), stop
   Gradle afterwards (`pkill -f GradleDaemon`), and don't start an emulator unless asked.
8. Report honestly: if a step failed or was skipped, say so with the error.

---

## 1 · Ask the person (one short message, multiple choice where possible)

Ask these together, offer sensible defaults, and continue with the defaults for anything they skip.

| # | Question | Default | Where it goes |
|---|---|---|---|
| 1 | TV model and its IP address (*Settings → Network*) | — | `TV=<ip>:5555` |
| 2 | City for prayer times & weather | Al Khobar, Umm al-Qura | `prayer/PrayerTimes.kt`, `dashboard/DashboardData.kt`, prompt in `assistant/AssistantEngine.kt` |
| 3 | Prayer features: adhan sound? lock the screen from adhan until iqama? Jumu'ah lock? Al-Kahf on Friday? | all on | in-app *Settings → Customize* (see §6) |
| 4 | Do you want the **Rafiq** assistant? Which AI key(s) do you have: Anthropic, Experiential Labs, Gemini? Spoken replies? | yes, voice on | `local.properties` |
| 5 | TMDB read token (free, required for films/series) | — | `local.properties` |
| 6 | YouTube channels for the *New on YouTube* row (list of @handles/ids or a Takeout `subscriptions.csv`) | none | pushed to the TV (§5) |
| 7 | Football: which leagues and clubs to follow? Reminder before matches: 15 / 30 / 60 min / off | big European leagues, 15 min | in-app settings |
| 8 | Installed on the TV: Stremio? Casper Flix? YouTube? | check with adb | — |
| 9 | Make TvHome the Home screen (disables the stock launcher, reversible)? | ask | `scripts/set-home.sh` |
| 10 | Screensaver: prayer-times dashboard (video/photo) or Aerial? | prayer dashboard | `set-home.sh` / in-app |
| 11 | Free up memory by disabling pre-installed apps they don't use? (you will show a list first) | ask | §7 |

If they don't have a key yet, give the link and wait:
TMDB <https://www.themoviedb.org/settings/api> · Anthropic <https://console.anthropic.com> ·
Gemini <https://aistudio.google.com/apikey>.

---

## 2 · Prepare the computer

Check, and install what's missing (ask before installing system-wide software):

```bash
java -version          # needs 21 (Temurin/Zulu or Android Studio's JBR)
adb version            # Android platform-tools
echo $ANDROID_HOME     # Android SDK with platform 36 + build-tools
```

- No SDK? Install Android Studio, or the command-line tools and run
  `sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"`.
- Then create `local.properties` from the example and fill in what the person gave you:

```bash
cp local.properties.example local.properties
# sdk.dir=<path to SDK>, TMDB_READ_TOKEN=…, ANTHROPIC_API_KEY=… (and/or EXPLABS_API_KEY, GEMINI_API_KEY)
```

## 3 · Adapt the city (only if not Al Khobar)

- `app/src/main/java/com/moath/tvhome/prayer/PrayerTimes.kt` → add a `PrayerConfig` with the city's
  latitude, longitude, UTC offset and method angles (Umm al-Qura: Fajr 18.5°, Isha = Maghrib + 90 min),
  and use it where `PrayerConfig.AlKhobarUmmAlQura` is the default
  (`PrayerSchedule.kt`, `PrayerAlerts.kt`, `dashboard/DashboardClock.kt`).
- `dashboard/DashboardData.kt` → `CITIES` for weather.
- `assistant/AssistantEngine.kt` → the system prompt says "مدينة الخبر"; replace with the new city, and
  `assistant/AssistantTools.kt` → `get_weather` default city and the `get_prayer_times` description.
- Run the unit tests after editing (they include prayer-time checks; update expectations only for the new city).

## 4 · Prepare the TV

Tell the person, step by step, on the TV:
1. *Settings → System → About* → press **Build** 7 times (Developer mode).
2. *Settings → System → Developer options* → **USB debugging** on (and **Network debugging** / *ADB over network* if listed).
3. Note the IP from *Settings → Network & Internet → <network>*.

```bash
adb connect <tv-ip>:5555     # the TV shows "Allow USB debugging?" → ask them to tick "Always allow" and OK
adb devices                  # must show <tv-ip>:5555  device
adb -s <tv-ip>:5555 shell pm list packages | grep -E "stremio|casper|youtube"   # what's installed (question 8)
```

## 5 · Build and install

```bash
nice -n 19 ./gradlew --no-daemon --max-workers=1 :app:testReleaseUnitTest :app:assembleRelease
pkill -f GradleDaemon
adb -s <tv-ip>:5555 install -r app/build/outputs/apk/release/app-release.apk
TV=<tv-ip>:5555 ./scripts/after-install.sh       # must print ✅
```

After approval (question 9):

```bash
TV=<tv-ip>:5555 ./scripts/set-home.sh            # Home screen + prayer screensaver
```

YouTube channels (question 6) — one channel id (`UC…`) or `@handle` per line:

```bash
adb -s <tv-ip>:5555 push youtube_channels.txt /sdcard/Android/data/com.moath.tvhome/files/youtube_channels.txt
```

Clock after power cuts (recommended on boxes without RTC):

```bash
adb -s <tv-ip>:5555 shell settings put global ntp_server 162.159.200.123
```

Local key presses (needed by Casper Flix tuning and Stremio stream picking): the app connects to the
TV's own adb at 127.0.0.1:5555. The first time one of those features runs, the TV asks to allow
debugging from the TV itself — ask the person to tick *Always allow*.

## 6 · Apply the person's choices

Open the right settings page on the TV and walk them through it (or let them use the remote):

```bash
B() { adb -s <tv-ip>:5555 shell am broadcast -p com.moath.tvhome "$@"; }
B -a com.moath.tvhome.DEV_SETTINGS --es page customize           # main "Customize" page
B -a com.moath.tvhome.DEV_SETTINGS --es page customize.prayer    # adhan, lock, iqama
B -a com.moath.tvhome.DEV_SETTINGS --es page customize.jumuah    # Friday & Al-Kahf
B -a com.moath.tvhome.DEV_SETTINGS --es page customize.leagues   # leagues   (customize.clubs for clubs)
B -a com.moath.tvhome.DEV_SETTINGS --es page customize.ai        # Rafiq: provider, voice replies
B -a com.moath.tvhome.DEV_SETTINGS --es page customize.saver.mode
B -a com.moath.tvhome.DEV_AI_PRIMARY --es provider anthropic     # or xpl / gemini (first provider to try)
```

## 7 · Optional: free up memory (only with approval)

1. List what runs and uses memory: `adb shell dumpsys meminfo | sed -n '/Total PSS by process/,/Total PSS by OOM/p' | head -40`
   and `adb shell pm list packages -s`.
2. Build a short table for the person: package · what it is · memory · what they lose if disabled.
   Leave out everything in rule 3. Mark anything you're unsure about as "keep".
3. Disable only what they approve, one by one:
   `adb shell pm disable-user --user 0 <pkg>` and append `<pkg>` to `scripts/disabled-packages.txt`.

## 8 · Verify with the person

| Check | How |
|---|---|
| Home shows content | Press Home: hero, *New on YouTube*, *Matches*, *Latest Movies / Episodes* rows load |
| Rafiq | Press the remote's assistant/mic button and say "كم الساعة؟" then "اقترح لي فيلم عائلي". Without speaking: `B -a com.moath.tvhome.DEV_PLATE --es text "اقترح لي فيلم عائلي"` |
| Stremio (if installed) | Say "شغّل آخر حلقة من ون بيس في ستريميو" — the episode opens and a 1080p stream starts |
| Prayer lock | Wait for the next prayer, or ask them if they want a test now (it plays the adhan): `B -a com.moath.tvhome.DEV_ADHAN --es prayer Maghrib`, end with `B -a com.moath.tvhome.DEV_ADHAN_END` |
| Control Center | Hold Home on the remote (injected adb keys don't trigger it) |
| Smoothness | `adb shell dumpsys gfxinfo com.moath.tvhome reset`, scroll Home, then `dumpsys gfxinfo com.moath.tvhome` — janky frames should be a few % |

## 9 · Troubleshooting

| Symptom | Fix |
|---|---|
| `after-install.sh` prints ❌ | `adb logcat -d | grep -iE "TclAppBoot|accessibility" | tail`; on TCL make sure `appops set com.moath.tvhome APP_AUTO_START allow` ran, then re-run the script |
| `No route to host` / device offline | `adb kill-server && adb start-server && adb connect <ip>:5555`; the TV may have a new IP |
| Rafiq says a key is missing / answers fail | Check `local.properties`, rebuild; `adb logcat -s Assistant` shows provider errors |
| Stremio sits on its loading logo | It happens on a cold start; TvHome re-opens the episode once after 14 s. Make sure the person's add-ons are installed in Stremio |
| Prayer card hidden | Clock not set (year < 2025): set NTP as above |
| Build is slow / computer busy | Keep `--max-workers=1 --no-daemon` and `nice -n 19`; close other heavy apps |

## 10 · Finish

Tell the person in a few lines: what's installed, which features are on, anything skipped and why, and
how to undo everything: `TV=<tv-ip>:5555 ./scripts/restore.sh` (`--uninstall` removes TvHome too).
Then stop Gradle and confirm no build process is left running.

---

© 2026 Moath Al-Hakeem
