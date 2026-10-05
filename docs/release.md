# Releasing Skipadoodle to Google Play

Same scheme as SignalScope: **one upload key, generated once, backed up, never replaced.**

## 1. Generate the upload key (once, by you)

Run it yourself so the password never passes through a chat log or the repo:

```bash
keytool -genkeypair -v -keystore ~/skipadoodle-upload.jks -alias skipadoodle -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
security add-generic-password -a "$USER" -s skipadoodle-release -w
cp keystore.properties.template keystore.properties   # set storeFile=/Users/<you>/skipadoodle-upload.jks
```

Use the same password for both prompts (PKCS12 has one). Back the `.jks` and its password up somewhere
that survives this laptop. With **Play App Signing** (the default) this is only the *upload* key, so a
lost one can be reset through Play support. Google holds the real app-signing key.

Check what resolved, without printing a secret: `./gradlew signingStatus`

## 2. Build

```bash
JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew :app:bundleRelease     # the AAB Play wants
```

Output: `app/build/outputs/bundle/release/app-release.aab`. R8 is on; AGP embeds the mapping file in the
bundle and Play picks it up automatically. The "native code, no debug symbols" notice is expected: the only
native libraries are prebuilt LiteRT ones.

Bump `versionCode` by one for every build handed to anyone, in the same commit.

## 3. First upload

1. Play Console, create the app. **applicationId is `com.skipadoodle` and is permanent once uploaded.**
   (`com.example.*` is rejected by Play, which is why it changed from `com.example.smartradio`.)
2. Upload to the **Internal testing** track first, not Production.
3. Fill in the items in `docs/play-store-listing.md` (listing, data safety, foreground service declaration,
   content rating, privacy policy URL).
4. Add yourself as an internal tester and install **from Play**.

## Android Auto

Install from Play, not a sideloaded APK. Android Auto hides sideloaded media apps unless
*Android Auto, Settings, tap Version 10 times, Developer settings, Unknown sources* is on. For Auto to list
the app, the Play listing must also pass Google's Android Auto review (media app category).

## Upload certificate

Every build uploaded to Play must be signed with this key. If a later build shows a different
fingerprint, something is wrong.

Certificate SHA-256: `52:58:D6:7A:0C:DF:84:4A:09:80:D9:B8:C7:E7:E8:DA:80:C8:2E:65:75:51:14:C3:C7:04:F1:F1:79:48:E5:61`
(RSA 4096, alias `skipadoodle`, first signed bundle versionCode 2, 2026-10-05)
