# Play Store listing and declarations

## Listing
- **App name:** Skipadoodle
- **Short description (80):** Streams your stations and skips the ads and talk, automatically.
- **Full description:** the final text was submitted from the upload folder (`full-description.txt`):
  how it works (directory search, preference order, skips after sustained talk, stops after several
  empty laps, live track titles), "private by design" (on-device analysis, no account/ads/analytics,
  optional location only for "Near you"), and "good to know" (internet streams, mobile data, some
  unencrypted streams). It makes no Android Auto claim until that is verified in a car.
- **Category:** Music & Audio. **Contact email:** apps@bonufied.com. **Privacy policy:** https://bonufied.com/apps/skipadoodle/privacy/
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
- Data encrypted in transit: **Yes**. The only user data sent (search text, optional country code) goes to the
  directory over https. Some audio streams are plain http, but they carry no user data.
- Deletion: nothing is sent to a developer server. Station list is local and removed on uninstall.

## Content rating / other
- Target audience: 13+ (general). No user-generated content in-app; station names come from a public directory.
- Ads: none. News/COVID/government: no.
- Android Auto: opt in to the media category in Console.
