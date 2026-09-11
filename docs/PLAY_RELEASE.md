# Releasing TennisReplay on Google Play

Everything needed to put the phone and watch apps on a Google Play testing track:
how the build is set up, what to back up, the order of the Play Console steps, and
suggested answers for each Console form. The store graphics are in
[`docs/play/`](play/) and the privacy policy is [`docs/PRIVACY.md`](PRIVACY.md).

## How the build is set up

| | Phone (`:phone`) | Watch (`:wear`) |
| --- | --- | --- |
| Play package | `com.tennisreplay` | `com.tennisreplay` (must match) |
| Debug package | `com.tennisreplay.debug` | `com.tennisreplay.debug` |
| `targetSdk` | 36 (required for new phone apps from 2026-08-31) | 36 (Play accepts 35+ for Wear OS) |
| `versionCode` | `1000000 + appVersionCode` | `2000000 + appVersionCode` |

- **Package name is permanent** once the first bundle is uploaded.
- **Versions** live in `gradle.properties` (`appVersionCode`, `appVersionName`).
  Bump `appVersionCode` before every upload; phone and watch share one Play
  listing, where every bundle needs a unique version code, so each module offsets it.
- **Debug builds** get the `.debug` suffix and the name "TennisReplay dev", so a
  development build and the Play build install side by side. They can never update
  each other anyway — they're signed with different keys.
- **Release builds** run R8 (code and resource shrinking), and **break in ways debug
  builds never show — always test a release build on the phone and watch before
  uploading.** Two such bugs were found this way before the first upload:
  - Resource shrinking deleted `android_wear_capabilities` (read by Play services by
    name, never by our code), so phone and watch stopped finding each other.
    `res/raw/keep.xml` in both modules keeps it.
  - MediaPipe's logging library, Flogger, finds its caller by class name on the
    stack; renamed by R8, every serve scan crashed. `phone/proguard-rules.pro` keeps
    Flogger, MediaPipe and protobuf, plus our serializers, and strips `Log.v`/`Log.d`.
- **No INTERNET permission.** MediaPipe Tasks uploads usage stats to Google through
  `com.google.android.datatransport`, with no off switch, and that library is the
  only thing that adds INTERNET. The phone manifest removes the permission, so
  nothing can leave the device — which the privacy policy and the Data safety
  answers below depend on. If a future feature needs the network, revisit both.

## The upload key — back this up

Release builds are signed with an **upload key**:

- keystore: `~/.tennisreplay/upload-keystore.jks` (alias `upload`)
- passwords: `~/.tennisreplay/keystore.properties`, copied to `keystore.properties`
  in the repo root, where the build reads it. Both `*.jks` and `keystore.properties`
  are git-ignored — never commit them.

**Copy both files somewhere safe outside this machine** (a password manager's file
attachment is ideal). With Play App Signing, Google holds the key that signs what
users install; this upload key only proves uploads come from you. If it's lost,
Play support can register a new one, but that takes days. Without
`keystore.properties`, release builds still build — unsigned, which Play rejects.

## Build the bundles

```bash
./gradlew :core:test :phone:bundleRelease :wear:bundleRelease
```

- phone: `phone/build/outputs/bundle/release/phone-release.aab` (about 31 MB)
- watch: `wear/build/outputs/bundle/release/wear-release.aab` (about 2 MB)

The phone's R8 step takes over ten minutes and a lot of memory. If the build is
killed for low memory, run the phone and watch bundles as separate commands with
`--no-parallel`.

**Before uploading, sideload the release APKs** (`:phone:assembleRelease`,
`:wear:assembleRelease`) on a phone and watch and check, at minimum: Home shows
"Watch connected"; Replay → **Scan again** finishes with serves; Watch check's
pings all return; a match scored from the watch shows the same score on both.
All four passed on the S25 Ultra and Watch8 Classic for version 0.1.0.

A sideloaded release build is signed with the upload key, while Play installs
builds signed with Google's app signing key — so **uninstall any sideloaded
`com.tennisreplay` before installing from Play**, or Play will refuse to update it.

## Play Console, in order

1. **Developer account** — play.google.com/console: one-time $25 fee and identity
   verification.
2. **Create app** — name `TennisReplay`, type App, Free. Accept the declarations.
3. **Test and release → Internal testing** → create a tester list (up to 100 Google
   account emails) → **Create new release** → accept **Play App Signing**
   (let Google manage the app signing key) → upload `phone-release.aab`.
4. **Wear OS** — Test and release → Advanced settings → **Form factors** → add
   Wear OS → **opt in and agree to the Wear OS review policy**. Then create a release
   on the Wear OS internal testing track and upload `wear-release.aab`. Wear OS
   releases are reviewed against the
   [Wear OS app quality guidelines](https://developer.android.com/docs/quality-guidelines/wear-app-quality).
5. **App content** (Policy → App content) — see the answers below.
6. **Store listing** — see below.
7. **Send testers the opt-in link** from the internal testing page. Testers install
   the phone app from Play on the phone, and the watch app from the Play Store on
   the watch (it is also offered on the watch once the phone app is installed).

A development build and a Play build won't talk to each other: the watch only
pairs with a phone app of the same package **and** signing key. Use Play builds on
both devices, or debug builds on both.

## App content answers

**Privacy policy URL**: the rendered
[`docs/PRIVACY.md`](https://github.com/solomon123/TennisPro/blob/claude/tennis-match-monitor-63q3fy/docs/PRIVACY.md)
on GitHub (or the same file on the default branch once merged).

**Ads**: No ads.

**App access**: All functionality is available without an account. Note for
reviewers: scoring from the wrist needs a paired Wear OS watch with the watch app;
recording, replay and serve analysis work on the phone alone.

**Content rating** (IARC questionnaire): category *Utility, Productivity,
Communication, or Other* → no violence, sexual content, profanity, drugs, gambling,
user-to-user communication or sharing of location → rated for everyone.

**Target audience**: 13 and over (keeps the app out of the Families program; it is
not designed for children).

**Data safety**:
- Does the app collect or share any of the required user data types? **No.**
  Video, audio, calibration and results are processed and stored only on the
  device, which Play does not count as collection. Score and tap messages go only
  between the user's own phone and watch via Google Play services; the developer
  never receives them.
- Re-check this if analytics, crash reporting, cloud backup or clip sharing is ever
  added.

**Foreground services** (required for apps targeting Android 14+). Each needs a
description, its user impact if deferred, and a video link — one short screen
recording covers all four: tap Record, turn the screen off, turn it back on, stop,
and show the "Finding serves" notification running.

| Type | Use | Why it can't wait |
| --- | --- | --- |
| `camera` | Records the match the user started with **Record**, while the screen is off or the app is in the background. | Stopping it stops the recording mid-match. |
| `microphone` | Records the match's audio with the video, same session. | Same recording as above. |
| `mediaProcessing` (Android 15+) | After a recording ends, analyses the video on the device to find serves, their speed and line calls. Shows a progress notification. | The user expects results when they open Replay; deferring it leaves the recording unanalysed. |
| `dataSync` (Android 11-14) | The same analysis on older Android versions, which have no `mediaProcessing` type. Android's own documentation lists *local file processing* under `dataSync`. | Same as above. |

## Store listing

**App name** (30 max): `TennisReplay`

**Short description** (80 max):
`Record tennis, score from your watch, and replay every serve with its speed.`

**Full description**:

```
TennisReplay turns your phone and Wear OS watch into a tennis match assistant.

RECORD
Mount your phone behind the baseline and record the whole match. Recording keeps
going with the screen off.

SCORE FROM YOUR WRIST
Tap your watch to win a point, double-tap for your opponent, hold to undo. Deuce,
advantage, tiebreaks and best-of-3 or 5 are handled for you, and the watch buzzes
on every game and set.

REPLAY EVERY SERVE
After each recording, TennisReplay finds your serves on its own and estimates
their speed. Each serve gets a service-line call — in, out, or honestly "too
close to call" when the camera can't be sure. Jump straight to any serve in
Replay.

EASY COURT SETUP
Freeze a frame and the app finds the court lines for you. Pinch to zoom and drag
a corner if it needs a nudge.

PRIVATE BY DESIGN
Everything is analysed on your phone. No account, no ads, no uploads.

GOOD TO KNOW
• Speeds and calls are estimates from a single camera — speed is typically
  within about 8-15%. Accuracy depends on how high and steady the phone is mounted.
• Scoring from the wrist needs a Wear OS 3+ watch (Galaxy Watch 4 or newer).
• Recordings use a lot of storage; delete old ones from the app.
```

**Category**: Sports. **Contact email** (required, shown publicly): `ssoollit@gmail.com`,
the same address as the privacy policy.

**Graphics** (in [`docs/play/`](play/)):
- App icon: `icon-512.png` (512×512)
- Feature graphic: `feature-graphic-1024x500.png`
- Phone screenshots: `phone-*.png`, 2160×1080 (Play allows at most 2:1, so the
  S25 Ultra's 2340×1080 screen is cropped)
- Wear OS screenshots: `watch-*.png`, square, from the watch

## After internal testing: production

Personal developer accounts created after **2023-11-13** must first run
a **closed test with at least 12 testers opted in for 14 consecutive days** (Google
checks they actually used it), then apply for production access — review usually
takes under a week. Organization accounts skip this.

## Moving existing recordings

Recordings made with an earlier build live in that build's own storage. With the
phone connected over adb they can be copied into another install — for example
from the old `com.tennispro` development build into `com.tennisreplay`:

```bash
adb shell 'mkdir -p /sdcard/Android/data/com.tennisreplay/files/Movies/sessions && \
  cp -r /sdcard/Android/data/com.tennispro/files/Movies/sessions/. \
        /sdcard/Android/data/com.tennisreplay/files/Movies/sessions/'
```

Open the new app once first so Android creates its folder. Calibration is quickest
to redo from Home → Calibrate court.

## Known limits worth knowing before wider testing

- On Android 16, tablets and unfolded foldables ignore the app's landscape lock, so
  it can appear in portrait there. It's built and tested for phones in landscape.
