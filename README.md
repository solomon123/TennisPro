# TennisReplay

Turns a phone camera and a Galaxy Watch into a tennis match assistant: records the
match, scores it from your wrist, and — in later phases — estimates serve speed
and calls the service line.

**Phases 0-4 are complete and verified on real hardware** (a Galaxy S25 Ultra and a
Galaxy Watch8 Classic). After every recording, serves are found and measured
automatically and each gets a service-line call, tested against real-court footage;
**nothing has yet been checked against a radar gun or an independent line call** — see
[Verifying Phase 3](#verifying-phase-3) and [Verifying Phase 4](#verifying-phase-4).

Before trusting anything this app eventually says about speed or line calls, read
[docs/ACCURACY.md](docs/ACCURACY.md). Short version: serve speed lands within about
±8-15%, service-line calls within about ±5-15 cm, and the app is designed to say
"too close to call" rather than guess.

## Setup

Configured for this setup — change the assumptions in the docs if yours differs:

- **Watch:** Galaxy Watch 7/8/Ultra (Wear OS 5+). Galaxy Watch 3 and older run
  Tizen and cannot install the watch app at all.
- **Mount:** behind the baseline, 2 m or higher, centred on the centre mark.
- **Camera:** back by default; some fence/clamp mounts hold the phone screen-out,
  putting the front camera on the court instead — switch it on Home under
  "Camera facing the court" if that's your setup. Both are recorded and previewed
  correctly; see [ARCHITECTURE.md](docs/ARCHITECTURE.md#calibration) for a real
  device-specific rotation bug this surfaced and how it's handled.
- **Format:** singles first, doubles geometry kept configurable.
- **Storage:** raw footage kept in full. Delete recordings one at a time, or tap
  **Select** (or long-press one) to delete several — from Home or Replay.

Mount the phone in landscape, frame the whole court including both service boxes,
and **do not move it once recording starts** — everything downstream depends on the
camera staying put.

## Building

Requires Android Studio (Ladybug or newer). Its bundled JDK is sufficient — the
build targets Java 17 bytecode without pinning a toolchain, so any JDK 17 or
newer works and Gradle never needs to download one.

```bash
./gradlew :core:test          # protocol unit tests, no Android SDK needed
./gradlew :phone:assembleDebug
./gradlew :wear:assembleDebug
```

Both APKs must be signed with the **same key** — including in debug, where the
shared debug keystore handles it automatically. If the watch never shows up as
connected, a signing mismatch is the first thing to check. Debug builds install as
`com.tennisreplay.debug` ("TennisReplay dev"), next to a Play-installed
`com.tennisreplay`; a debug phone app only pairs with a debug watch app.

Release builds, the upload key and the Google Play steps are covered in
[docs/PLAY_RELEASE.md](docs/PLAY_RELEASE.md).

Install the phone APK to the phone and the wear APK to the watch (over ADB via
Wi-Fi debugging, or by pushing a debug build through Android Studio with the watch
selected as the target).

## Verifying Phase 0

1. Launch the app on both devices.
2. On the phone, open **Watch check**. It should find the watch.
3. **Test buzz** → the watch vibrates. **Simulate OUT** → a distinct double pulse
   and a red full-screen call.
4. Tap, double-tap and long-press the watch → each shows up under *Watch input*.
5. **Run 10 pings** → round-trip latency. Do this *on court*, not indoors: this
   number decides whether the wrist is a viable channel for live line calls.
   Under 250 ms is good; over 600 ms means the phone speaker should be the primary
   alert.
6. Back on Home, **Record a match**. Start recording, lock the phone screen, wait,
   unlock — recording should still be running.
7. Long-press the watch while recording (with no match being scored) → the moment
   is bookmarked and the watch gives a faint confirmation tick.
8. Stop. The recording appears on Home with size, duration and mark count, and can
   be deleted.
9. **From the watch**, for a phone hung out of reach: open **Record a match** and
   leave it on screen, then tap **REC** at the bottom of the watch face → one long
   buzz, and the phone's REC clock is running. Tap **STOP** twice (the first tap
   asks to confirm) → two buzzes, and the recording is saved and scanned. With the
   phone on any other screen, REC answers "Open Record on the phone" after a few
   seconds; with the phone locked, "Unlock the phone".

Step 6 is the one worth being fussy about: capture living in a foreground service
rather than the activity is what makes a two-hour match on a fence possible.

## Verifying Phase 1

Manual scoring builds and its rules are covered by `:core:test` (deuce/advantage,
tiebreaks, best-of-N, undo across game/set boundaries), but that alone doesn't prove
the watch link works. Confirmed on a Galaxy S25 Ultra + Galaxy Watch8 Classic:

1. Launch the app on both devices, open **Score match** on the phone, and start a
   best-of-3 match.
2. On the watch: single tap scores a point for you, double tap for your opponent,
   long-press undoes the last point. Each buzzes distinctly — a point is a faint
   tick, a game win two firmer pulses, a set win three, and a match win the biggest
   pattern in the app. Judge these on court for your own taste; the exact waveforms
   in `wear/Haptics.kt` are a first cut, not a measurement.
3. Force a deuce and reach 40-40/AD — confirmed correct on the phone and watch.
4. Back the watch out to its home screen mid-match, score more points on the phone,
   then reopen the watch app — the score is current immediately, no re-sync needed.
   That's the latched `DataClient` state doing its job, not a fire-and-forget message.
5. Try scoring and recording at the same time: a watch long-press undoes the last
   point and does *not* also mark the recording (the watch says "Undo"); with no
   match being scored, it marks the moment instead (the watch says "Marked").

Two real bugs turned up only once this ran on physical hardware, both now fixed:
neither `WearableListenerService` should declare
`android:permission="com.google.android.gms.permission.BIND_WEARABLE_LISTENER"` —
on this hardware it made Play Services itself fail to bind, silently dropping every
message in both directions with no error anywhere except `adb logcat`'s
`ActivityManager`/`WearableService` lines; and the New Match dialog's three format
buttons needed `Modifier.weight(1f)` to share width, since without it they could
overflow the dialog on this phone's landscape lock.

## Verifying Phase 2

Homography math (corner round-trip, inverse, degenerate-input rejection) and the
drift-difference math are covered by `:core:test`'s `CourtTest`. Confirmed on a
Galaxy S25 Ultra, back and front camera both:

1. Mount the phone per the Setup section above. Open **Calibrate court** and
   hit **Freeze frame**. The app looks for the court lines itself and places
   the four corners; the projected blue grid — outer court, net, both service
   lines — should sit on the painted lines. Pinch to zoom; drag a corner to
   adjust (a magnifier shows exactly where it lands). If the lines weren't
   found, tap the corners in order (near-left, near-right, far-left,
   far-right). Degenerate corners are refused rather than saved.
2. **Save**, then check Home shows **Calibrated**.
3. **Record a match**, and confirm no "Camera may have moved" chip appears
   (the mount hasn't moved since calibrating).
4. Open **Replay**, pick that recording. **Scrub** shows the calibration grid
   tracking the court accurately at multiple points in the video, not just the
   moment calibrated — try **Calibrate from this frame** as the other entry
   point into the same tap flow. **Play** actually watches the recording back
   at normal speed with play/pause/seek.
5. Nudge the phone's mount and reopen **Record a match** — the "Camera may have
   moved" chip should appear. The check re-detects the court and compares corners
   with the calibration (see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)'s
   Calibration section), falling back to a rough pixel-difference check when the
   court can't be found.
6. If using the front camera: switch to it on Home, confirm the live preview
   in both **Calibrate court** and **Record a match** is right-side up, then
   record a clip and confirm **Replay** plays it back right-side up too. Both
   are genuinely necessary checks, not redundant — see
   [ARCHITECTURE.md](docs/ARCHITECTURE.md#calibration) for why this device
   needed two separate fixes, one for each.

## Verifying Phase 3

Phase 3 now finds and measures serves automatically — see
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)'s "Serve detection and speed" for
the pipeline and why the first, bookmark-driven version was replaced after the
first real-court test.

`:core:test` covers it with synthetic data: `MotionBlobsTest` (three-frame
differencing), `ServeProposalsTest` (each pose rule, including the look-alikes
it rejects), `ServeFlightTest` (serves simulated in 3D with gravity and drag
through a camera matching a real calibration: launch speed recovered within
6%, net faults, clutter), and `CourtLineDetectorTest` (a synthetic court plus a
real 2026-09-08 frame).

Confirmed on a Galaxy S25 Ultra:

1. Replay → a recording made before automatic scanning → **Find serves**
   starts a background scan with a progress notification, and Replay shows
   the result: on the 57 s `2026-09-08T18-10-14` recording, both serves
   (147 and 153 km/h, ±7-8%) in 85 s. Each chip jumps the scrubber to its
   serve.
2. Off-device, the same pipeline on all four 2026-09-08 recordings found 13
   of the 14 serves counted by eye (8 measured at 132-166 km/h, 5 net
   faults), with 2 false detections in 10 minutes of rallying.

Not yet verified:

3. **Accuracy against a radar gun.** The speeds are plausible for a club
   first serve and consistent between serves, but nothing has measured the
   same serves independently.
4. **The automatic start after recording.** Record a short clip with a few
   serves, stop, and confirm the "Finding serves" notification appears and
   Replay lists the serves when it finishes.
5. **A long recording.** The pose pass runs at about real time on this phone,
   so check a full match scans to completion in the background.

## Verifying Phase 4

`:core:test` covers the call itself in `ServiceLineCallTest` — in, out long, the
wrong box, either server side, a ball touching a line, too close to call, each axis
judged against its own uncertainty, doubles-calibrated courts — and
`ServeFlightTest` checks that a simulated serve 42 cm inside the service line is not
called out.

Confirmed on a Galaxy S25 Ultra and against the 2026-09-08 footage:

1. Replay → a scanned recording → each serve's chip reads like
   `00:38 · 144 km/h · IN by 43 cm`, `OUT by 92 cm`, or `too close to call`. Net
   faults read `net` and get no call.
2. Frame by frame with the projected court lines drawn in, a serve called IN by
   46 cm (±5 cm, centre line) lands clearly inside the correct box, and a bounce
   hidden behind the server's head gets no call rather than a wrong one.

Not yet verified:

3. **Against an independent call** — a line judge, or slow-motion video from beside
   the line. From the 2026-09-08 mount height, calls on the far service line carry
   about ±50 cm and will mostly read "too close"; mount higher for real
   service-line calls.

## Where this is going

| Phase | Scope | State |
| --- | --- | --- |
| 0 | Scaffolding, recording, phone↔watch link | **Done**, proven on court |
| 1 | Manual scoring from the watch, persisted match state | **Done**, proven on hardware |
| 2 | Court calibration UI + video replay harness | **Done**, proven on hardware |
| 3 | Serve speed, found automatically after each recording | **Done**, tested on real-court footage; speed **not yet checked against a radar gun** |
| 4 | Service line in/out calls, after the match | **Done**, tested on real-court footage; live calls on the watch deferred |
| 5 | Clip export, match history, stats | Stretch |

Rally line calling and auto-scoring are **out of scope for v1** by deliberate
choice, not oversight — [docs/ACCURACY.md](docs/ACCURACY.md) explains why.

[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) covers module layout, the Data Layer
transport split, camera ownership, and what was deferred.
