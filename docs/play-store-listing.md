# Play Store listing and declarations

## Listing
- **App name:** Skipadoodle
- **Short description (80):** Streams your favourite stations and skips the ads and talk, automatically.
- **Full description:**
  Skipadoodle plays your preferred internet radio stations in the order you choose, and listens to the audio
  on your phone to tell music from talk. When a station runs into a sustained stretch of ads or chatter, it
  hops to the next station on your list. If a full lap finds no music it stops instead of burning data.
  - Search a free community directory of thousands of stations, or add any stream URL.
  - Set your preference order; the app tries stations top to bottom.
  - Live track titles where the station provides them.
  - Works with Android Auto and car Bluetooth controls.
  - The music-vs-talk check runs entirely on your device. Your audio is never recorded or uploaded.
  Stations play through each broadcaster's internet stream, so no FM/DAB tuner hardware is needed.
- **Category:** Music & Audio. **Contact email / website:** set in Console (website: bonufied app page).
- **Graphics:** `art/play-store/icon-512.png`, `art/play-store/feature-graphic-1024x500.png`; phone screenshots
  `art/play-store/screenshot-*.png` (1080x2066, three captured from the R8 release build; Play needs 2+).

## Permissions (Console justifications)
| Permission | Why |
|---|---|
| INTERNET, ACCESS_NETWORK_STATE | Stream radio, search the station directory |
| FOREGROUND_SERVICE + FOREGROUND_SERVICE_MEDIA_PLAYBACK | Keep playing with the screen off / in the car |
| POST_NOTIFICATIONS | Media playback notification and controls |
| WAKE_LOCK | Held by the media player during playback |
| ACCESS_COARSE_LOCATION | Optional, only when the user taps "Near you": picks the user's country to list popular local stations |

**Foreground service declaration:** type `mediaPlayback`. Demo video: start a station, lock the screen, audio
continues with the notification controls.

## Data safety (draft, verify against the code before submitting)
- **Approximate location:** collected (processed on device, only the resulting country code is used), not
  shared, optional, used for app functionality. Note: Android's Geocoder may contact Google to resolve a
  country; disclose it. Nothing is stored.
- **No** accounts, ads, analytics, crash SDKs, or personal identifiers collected by the app.
- Network requests: station directory (radio-browser.info: search text and optional country code),
  station logos and audio streams from broadcasters' own servers (they see the device IP, as with any player).
- Data encrypted in transit: **No** for some streams. Many broadcasters only offer http, so the app allows
  cleartext. Answer honestly.
- Deletion: nothing is sent to a developer server. Station list is local and removed on uninstall.

## Content rating / other
- Target audience: 13+ (general). No user-generated content in-app; station names come from a public directory.
- Ads: none. News/COVID/government: no.
- Android Auto: opt in to the media category in Console.
