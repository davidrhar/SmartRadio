# Smart Radio

Android app (Kotlin + Jetpack Compose + Media3/ExoPlayer) that:
- Streams a shortlist of FM-simulcast and digital radio stations, in a user-set preference order (drag to reorder).
- Classifies the live audio on-device (music vs. speech/ads) using Google's YAMNet model via TensorFlow Lite.
- Auto-skips to the next preferred station after a sustained run of non-music (default: ~8s), to avoid flipping on a brief pause.
- If a full lap of the shortlist turns up no music, stops hopping and mutes in place, un-muting automatically the moment that station's audio turns back to music.

## Why there's no real "FM tuner" here
Pixels and virtually all modern Android phones have no FM radio chip exposed to apps. "FM" stations in this app are played via each broadcaster's internet simulcast stream URL — same for "digital" stations (DAB+ also has no phone hardware access, so those are internet stream URLs too). Practically, both station types are just entries with a name + stream URL; you'll need to find/enter real stream URLs for the stations you want (most broadcasters publish an MP3/AAC/HLS stream link on their website, or you can use a station directory like the Radio Browser API).

## The YAMNet model — now fetched automatically
A Gradle task (`downloadYamnetAssets` in `app/build.gradle.kts`) fetches `yamnet.tflite` and derives `yamnet_label_list.txt` into `app/src/main/assets/` the first time you build — your build machine (unlike the sandbox that generated this project) has network access, so this runs without you doing anything. It only downloads if those files aren't already present. If it ever fails (source URLs moved, etc.), do it manually:

1. `yamnet.tflite` — https://tfhub.dev/google/lite-model/yamnet/classification/tflite/1
2. `yamnet_label_list.txt` — the 521-line AudioSet class label list, derived from https://raw.githubusercontent.com/tensorflow/models/master/research/audioset/yamnet/yamnet_class_map.csv (last CSV column, one per line)

Drop both into `app/src/main/assets/`. Worth a sanity check after the first build: `wc -l app/src/main/assets/yamnet_label_list.txt` should read 521.

## Gradle wrapper note
`gradlew` / `gradlew.bat` / `gradle-wrapper.properties` are included, but not the one piece that has to be a compiled binary fetched over the network: `gradle/wrapper/gradle-wrapper.jar`. Three ways to get it:
- **Android Studio**: open the project — Studio detects the missing jar and offers to fetch it automatically.
- **GitHub Actions**: not needed — `build-apk.yml` installs Gradle directly (`gradle/actions/setup-gradle`) and runs `gradle assembleDebug`, bypassing the wrapper entirely.
- **Command line with Gradle already installed**: run `gradle wrapper --gradle-version 8.7` once to generate the jar.

## Finding stations (no manual URL-hunting required)
Tapping "+" now opens a search box first, backed by the free [Radio Browser](https://www.radio-browser.info) directory (no API key needed) — type a station name, tap a result, done. "Add manually" is still there as a fallback for stations that aren't in that directory. Note: Radio Browser doesn't cleanly separate "FM simulcast" from "pure digital" stations, so the FM/Digital tag on search results is a best-effort guess from the station's tags — you can still add manually with an explicit choice if that matters to you.

## Building the APK
You asked for a cloud build rather than local Android Studio — the easiest zero-install route is GitHub Actions, and this repo already includes `.github/workflows/build-apk.yml` for it:

1. Create a new GitHub repo and push this project to it.
2. The workflow runs automatically on push to `main` (or trigger it manually from the Actions tab → "Build debug APK" → "Run workflow"). The YAMNet assets download automatically as part of the build — no manual step.
3. When it finishes, open the workflow run → **Artifacts** → download `smart-radio-debug-apk`. That zip contains `app-debug.apk`, installable directly on a phone (enable "install unknown apps" for whatever app you transfer it with).

This produces a **debug**-signed APK, fine for sideloading on your own device. For a Play Store release build you'd add a release keystore and signing config — ask if you want that added.

## Tuning the auto-switch behavior
- `MusicDetectionEngine(sustainedWindowsToSwitch = 8)` in `RadioPlaybackService.kt` — raise/lower this to make switching slower/faster to trigger (each window ≈ 1s).
- `YamnetClassifier.MUSIC_CONFIDENCE_THRESHOLD` — raise to require more confidence before calling something "music".

## Known rough edges / next steps
- No real starter station list is included (`StationRepository.defaultStations()` returns empty) — add your market's stations via the in-app "+" button, or hardcode some for testing.
- No release signing config yet.
- Classifier runs continuously while playing; on very old/low-end devices you may want to throttle inference (e.g. classify every 2nd window instead of every window).
