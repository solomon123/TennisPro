# TennisPro

Turns a phone camera and a Galaxy Watch into a tennis match assistant: records the
match, scores it from your wrist, and — in later phases — estimates serve speed
and calls the service line.

**Phase 0 is complete and proven on court.** **Phase 1 (manual scoring) is code**
**complete but not yet verified on hardware** — see [Verifying Phase 1](#verifying-phase-1)
below before trusting it in a real match. No computer vision yet.

Before trusting anything this app eventually says about speed or line calls, read
[docs/ACCURACY.md](docs/ACCURACY.md). Short version: serve speed lands within about
±8-15%, service-line calls within about ±5-15 cm, and the app is designed to say
"too close to call" rather than guess.

## Setup

Configured for this setup — change the assumptions in the docs if yours differs:

- **Watch:** Galaxy Watch 7/8/Ultra (Wear OS 5+). Galaxy Watch 3 and older run
  Tizen and cannot install the watch app at all.
- **Mount:** behind the baseline, 2 m or higher, centred on the centre mark.
- **Format:** singles first, doubles geometry kept configurable.
- **Storage:** raw footage kept in full, deletable per-recording or all at once
  from the home screen.

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
connected, a signing mismatch is the first thing to check.

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
7. Long-press the watch while recording → the moment is bookmarked and the watch
   gives a faint confirmation tick.
8. Stop. The recording appears on Home with size, duration and mark count, and can
   be deleted.

Step 6 is the one worth being fussy about: capture living in a foreground service
rather than the activity is what makes a two-hour match on a fence possible.

## Verifying Phase 1

Manual scoring builds and its rules are covered by `:core:test` (deuce/advantage,
tiebreaks, best-of-N, undo across game/set boundaries), but none of that proves the
watch link actually works. Check it for real:

1. Launch the app on both devices, open **Score match** on the phone, and start a
   best-of-3 match.
2. On the watch: single tap scores a point for you, double tap for your opponent,
   long-press undoes the last point. Confirm each buzzes distinctly — a point should
   feel like a faint tick, a game win two firmer pulses, a set win three, and a
   match win the biggest pattern in the app. Judge these on court; the exact
   waveforms in `wear/Haptics.kt` are a first cut, not a measurement.
3. Force a deuce, a tiebreak (score to 6-6), and a set win, checking the phone and
   watch always show the same score.
4. Kill and reopen the watch app mid-match — the score should reappear from the
   latched `DataClient` state without the phone doing anything, which is the point
   of not using a fire-and-forget message for this.
5. Force-stop the phone app mid-match and relaunch it — the score should be
   restored from disk (`ScoreStorage`), not reset to 0-0.
6. Try scoring and recording at the same time. Read the "Known gap" note on
   `MatchController` first — right now a watch long-press does *both* undo a point
   and mark a video bookmark, which is expected for Phase 1, not a bug.

## Where this is going

| Phase | Scope | State |
| --- | --- | --- |
| 0 | Scaffolding, recording, phone↔watch link | **Done**, proven on court |
| 1 | Manual scoring from the watch, persisted match state | **Code complete**, unverified on hardware |
| 2 | Court calibration UI + video replay harness | Planned |
| 3 | Serve speed | Planned |
| 4 | Service line in/out calls | Planned |
| 5 | Clip export, match history, stats | Stretch |

Rally line calling and auto-scoring are **out of scope for v1** by deliberate
choice, not oversight — [docs/ACCURACY.md](docs/ACCURACY.md) explains why.

[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) covers module layout, the Data Layer
transport split, camera ownership, and what was deferred.
