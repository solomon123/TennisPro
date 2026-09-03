# TennisPro

Turns a phone camera and a Galaxy Watch into a tennis match assistant: records the
match, marks moments from your wrist, and — in later phases — estimates serve speed
and calls the service line.

**Phase 0 is complete.** That means scaffolding, recording, and a *proven* phone↔watch
link. No computer vision yet.

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

Requires Android Studio (Ladybug or newer) and a JDK 17+ toolchain.

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

## Where this is going

| Phase | Scope | State |
| --- | --- | --- |
| 0 | Scaffolding, recording, phone↔watch link | **Done** |
| 1 | Manual scoring from the watch, persisted match state | Next |
| 2 | Court calibration UI + video replay harness | Planned |
| 3 | Serve speed | Planned |
| 4 | Service line in/out calls | Planned |
| 5 | Clip export, match history, stats | Stretch |

Rally line calling and auto-scoring are **out of scope for v1** by deliberate
choice, not oversight — [docs/ACCURACY.md](docs/ACCURACY.md) explains why.

[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) covers module layout, the Data Layer
transport split, camera ownership, and what was deferred.
