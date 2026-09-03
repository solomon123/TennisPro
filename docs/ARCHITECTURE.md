# Architecture

## Modules

```
:core    Pure Kotlin/JVM. Wire protocol today; match state and scoring in Phase 1.
         Android-free on purpose, so it unit-tests on the JVM in milliseconds.
:phone   Android app. CameraX capture, storage, UI, phone side of the Data Layer.
:wear    Wear OS app. Haptics, tap input, watch side of the Data Layer.
```

`:phone` and `:wear` share one `applicationId` (`com.tennispro`) and **must be
signed with the same key**. The Wearable Data Layer pairs the two APKs on package
name plus signing certificate; a mismatch fails silently — the watch simply never
appears as a reachable node, with no error anywhere.

## Phone ↔ watch transport

Two transports, chosen for different jobs:

- **`MessageClient`** (`/tennispro/p2w`, `/tennispro/w2p`) for events: out-calls,
  score taps, pings. Fire-and-forget and never stored — an out-call that arrives
  four seconds late is worse than one that never arrives.
- **`DataClient`** (`/tennispro/match_state`) for latched state: the current score.
  DataItems replay on reconnect, so the watch recovers the right score after a
  Bluetooth dropout without the phone having to notice. Wired up in Phase 1.

Node discovery goes through `CapabilityClient`, not `NodeClient`: "every connected
node" can include a paired tablet or a second watch that has never had the app
installed.

Payloads are JSON (`WearCodec`). At a few dozen bytes against a 100 KB limit the
encoding cost is irrelevant, and being able to read a payload out of a logcat dump
during on-court debugging is worth more than the bytes. `ignoreUnknownKeys` is on
because the two APKs are installed separately and will routinely be different
versions on the same wrist.

### Clock discipline

Latency is measured entirely on the **phone's** `elapsedRealtime()`. The phone
stamps a `Ping`, the watch echoes that value back untouched in the `Pong`, and the
phone subtracts. The two devices' monotonic clocks share no epoch, so any direct
phone-minus-watch subtraction would be meaningless. Do not "improve" this by
having the watch report its own timestamp.

## Camera ownership

CameraX is bound by `RecordingService` (a `LifecycleService`), not by the activity.
On a fence mount the phone records for two hours with its screen off; use cases
bound to an activity lifecycle would stop capture the moment the screen blanked.

The activity attaches and detaches only the preview *surface*. Rebinding use cases
would finalize an in-progress recording, so the binding itself is never touched
once recording has started.

From Android 14 a `camera`-typed foreground service may only be *started* while the
app is visible, which is why `MainActivity.startRecording()` promotes the service
before asking it to record.

## Storage

`Android/data/com.tennispro/files/Movies/sessions/<id>/`

```
match.mp4         raw continuous recording
session.json      id, start time, duration, resolution, frame rate
bookmarks.jsonl   one JSON object per line, append-only
```

App-specific external storage means no storage permission on any supported API
level, while the files stay reachable over USB for pulling footage onto a laptop —
which is how Phases 3-4 will get their test data. The cost is that recordings do
not appear in the gallery; exporting marked clips to MediaStore is deferred.

Bookmarks are append-only because they are written mid-recording, sometimes from a
watch message: a torn rewrite of a whole file loses the session's marks, a torn
append loses one line.

## Frame rate

`RecordingService` asks for 60 fps and falls back to the device default. Frame rate
is the single biggest lever on both serve-speed and bounce-location error (see
[ACCURACY.md](ACCURACY.md)), so it is worth a retry rather than silently accepting
30.

**Known limitation:** 120/240 fps is not reachable through the CameraX video path
used here. High-speed capture arrived late in `camera-video`, and Samsung's
high-speed modes are resolution-capped and inconsistently exposed. Reaching 120 fps
will likely need Camera2 interop, and that work belongs in Phase 3 where the frame
rate actually pays for itself. The `tryBind` fallback structure is where it slots in.

## Deliberately deferred

- **Real-time inference during capture.** Serve speed is computed from the buffered
  clip a second or two after contact; nobody needs it inside 100 ms, and running
  inference alongside high-frame-rate capture will thermally throttle a phone
  inside a set. Only line calling genuinely needs low latency.
- **A replay harness.** Piping recorded files through the same vision pipeline
  off-court is the single biggest productivity lever for Phases 3-4. Build it
  alongside calibration, before any detector work.
- **Calibration drift detection.** A fence mount gets bumped. Silently wrong
  calibration is worse than none, so periodic line re-detection and a "camera
  moved, recalibrate" warning are part of Phase 2, not an afterthought.
