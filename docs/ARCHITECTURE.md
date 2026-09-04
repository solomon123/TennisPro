# Architecture

## Modules

```
:core    Pure Kotlin/JVM. Wire protocol, the tennis scoring engine, the court
         calibration homography, and the serve-speed vision math. Android-free
         on purpose, so it unit-tests on the JVM in milliseconds.
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
  Bluetooth dropout without the phone having to notice.

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

## Scoring

`core/scoring/Scoring.kt` is a pure fold: `MatchState` is just rules
(`MatchConfig`) plus a point-by-point log (`history: List<Side>`), and every
displayable field — game score, set score, whose serve it is, who has won —
is recomputed from scratch by folding over that log (`project`). Undo is
therefore "drop the last point and refold," never a hand-written inverse of a
deuce/tiebreak/set-boundary transition, which is the class of bug this
approach avoids entirely — see `ScoringTest` for the boundary cases (deuce,
no-ad, tiebreak entry and win, best-of-N, undo across a set boundary).

The watch never sees `MatchState`. `MatchController` (in `:phone`) is the only
place that mutates it; the watch is pushed a `MatchProjection` — the display
snapshot, with no history — over the `MATCH_STATE` DataItem. This mirrors the
phone/watch asymmetry everywhere else in this codebase: the phone decides
what things mean, the watch displays and generates input.

A watch gesture and a phone button both resolve through `MatchController.pointFor`
/ `.undo`, so there is exactly one place deciding whether a point also counts
as a game/set/match win worth a distinct haptic (`AlertKind.GAME_WON` /
`SET_WON` / `MATCH_WON`, sent as an ordinary fire-and-forget `Alert` — not
inferred by the watch diffing the DataItem, which would misfire on a
reconnect replay of old state).

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

## Calibration

`core/court/Court.kt` follows the same "store the log, derive the projection"
split as scoring: `CalibrationPoints` — the four tapped corners, in a fixed
near-left/near-right/far-left/far-right order — is the only thing persisted or
sent between layers. `Homography` is always recomputed from those four points
on demand via `Homography.fromCalibration`, never itself stored.

The homography is a standard 4-point Direct Linear Transform, solved as an
8-equation/8-unknown linear system with the homogeneous scale fixed at `h9 = 1`.
That fix is only safe because a phone photographing a physical, finite court
plane can never send a real point to infinity — the one case where `h9 = 1`
would be wrong — which is also why `Homography` only ever takes exactly four
correspondences rather than a general least-squares fit over more: the
assumption stops being obviously safe once the point set isn't guaranteed to
come from a real camera view of a real plane.

`CalibrationStorage` (in `:phone`) is a single device-level slot, not
per-recording — mirrors `ScoreStorage`'s reasoning: one camera stays mounted in
one spot across many recordings (see the README's Setup section), so
calibration is a property of the mount, not of any one session.

**Drift detection is a first cut, not real detection.** `frameDifference` is a
mean-absolute-difference over a small downscaled grayscale grid, compared
against a saved reference frame. It is not line/edge-based re-detection —
that is classical-CV work that belongs alongside Phase 3/4's ball-detection
pipeline (see docs/ACCURACY.md's "classical CV first" plan for the ball),
which this project has no CV dependency for yet. This check will false-positive
on a big lighting change and miss a small nudge; it exists to catch the case
that actually matters — the mount got bumped and the frame looks nothing like
the reference — the same "first cut, not a measurement" spirit as
`wear/Haptics.kt`'s waveforms. It runs once, in `RecordScreen`, when the live
preview comes up — not on Home, which does not bind a camera at all and only
shows whether *some* calibration exists.

The video replay harness (`phone/replay/VideoFrameSource.kt`) is deliberately
built on `MediaMetadataRetriever.getFrameAtTime`, not a sequential
`MediaExtractor`/`MediaCodec` decoder: frame-accurate but not fast, which is
the right trade for a scrub-through-frames UI. A faster sequential decoder is
worth building once Phase 3/4's detector actually needs that throughput.
`ReplayScreen` also has a separate **Play** mode (`android.widget.VideoView`)
for actually watching a recording back at normal speed — no new dependency,
since the built-in widget is enough for that job.

### Camera facing

Some fence/clamp mounts hold the phone screen-out for monitoring, which puts
the *front* camera on the court instead of the back one — a real mounting
constraint a user of this app hit, not a hypothetical. `CameraFacing` /
`CameraPreferences` (in `:phone/camera`) persist which physical camera to use,
device-level like calibration, and `RecordingService.switchCamera` rebinds
using the same `tryBind` retry structure the frame-rate fallback already uses
— no new mechanism, just a different `CameraSelector`.

**Two independent, real front-camera bugs turned up getting this right on a
Galaxy S25 Ultra, and they needed two different fixes:**

1. **The live preview came out upside down.** `PreviewView`'s default
   `ImplementationMode.PERFORMANCE` (a `SurfaceView`) was not correctly
   applying CameraX's own computed rotation for the front camera on this
   device — confirmed via `adb logcat`'s `PreviewView`/`PreviewTransform`
   lines, which showed CameraX computing `TransformationInfo{getRotationDegrees=0}`
   for a configuration that was visibly rotated 180 degrees. Switching to
   `ImplementationMode.COMPATIBLE` (a `TextureView`, which CameraX can
   transform directly) fixed it in combination with feeding the front camera
   the *opposite* `Surface.ROTATION_*` constant from what the back camera
   uses — see the `tryBind`/`CameraPreview.kt` comments for the exact
   reasoning and how it was verified in logs before being applied blind.
2. **The recorded file came out upside down — a separate bug from #1, not the
   same one.** Verified with `ffprobe` (not present on this project's build
   machine by default, but essential for diagnosing this class of bug):
   `VideoCapture.setTargetRotation` does not control the front camera's
   recorded-file rotation on this hardware at all. Front and back recordings
   came out of `tryBind` with the *identical* container rotation (`-180`,
   an MP4 `tkhd` matrix) regardless of what was requested at capture time —
   correct for the back camera, exactly 180 degrees wrong for the front.
   There is no capture-time knob that reaches this, so `Mp4Rotation.kt` fixes
   it after the fact: a direct, lossless rewrite of the `tkhd` box's rotation
   matrix to identity, run once per front-camera recording right after
   `VideoRecordEvent.Finalize`. It patches the file in place via
   `RandomAccessFile` rather than reading it into memory, since a real match
   recording can run to gigabytes.

The lesson worth keeping: **a fix verified against the live preview does not
verify the recorded file, and vice versa — they are separate CameraX
pipelines that can (and here, did) disagree.** Anything touching capture
orientation needs both checked independently, which is why the Phase 2
verification steps in the README check them as two separate items.

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

## Serve speed

`core/vision/` follows the same "hand-rolled, pure, unit-tested" style as
scoring and calibration — no OpenCV, no trained model, per the decisions
made going into Phase 3 (see `docs/ACCURACY.md`'s "classical CV first" plan).
The pipeline, end to end:

1. **A rough window.** The user drops a bookmark near the serve (the
   existing bookmark feature, unchanged); analysis searches
   `[bookmark - 1s, bookmark + 3s]`.
2. **`PoseSwingWindow` narrows that window** using MediaPipe Tasks'
   `PoseLandmarker` (`RunningMode.VIDEO`), tracking the right-wrist landmark's
   vertical motion to find roughly when the swing happens, ±700ms. This is
   deliberately **not** used to pinpoint the contact frame — a body-joint
   pose model has no way to know the exact instant of racket-ball contact.
   Its only job is making sure the ball tracker isn't searching a whole
   multi-second window blindly, which might contain other motion (a
   returning opponent, a ball boy). If pose detection is unavailable or
   inconclusive, it falls back to the unnarrowed window rather than failing.
3. **`BallDetector` finds ball candidates per frame**: consecutive-frame
   differencing on the grayscale Y-plane, thresholded, connected-component
   blob labeling, filtered by plausible size.
4. **`KalmanTracker2D`** (a hand-rolled constant-velocity 2D filter) tracks
   the ball across frames, predicting through missed detections and gating
   which candidate to accept each frame by distance from the prediction.
5. **`Trajectory.findContactIndex`** finds the contact frame as the sharpest
   frame-to-frame speed increase in the tracked path — the toss's
   near-stationary apex giving way to the racket's acceleration — rather
   than trusting the pose window to be frame-precise, which it isn't.
   **`Trajectory.findBounceIndex`** finds the bounce the same way Phase 4's
   line-calling will need to: the trajectory vertex, where vertical image
   motion reverses. Written once here, reusable there.
6. **`ServeSpeed.estimate`** converts the pixel displacement between
   post-contact frames into meters using the *local scale* of Phase 2's
   `Homography` — a finite-difference Jacobian at the ball's image position,
   not a direct `mapToCourt` of an above-ground point, which would place the
   ball at a nonsensical on-court coordinate. Divides by elapsed frame time
   to get speed, and always returns an **error band** alongside it
   (`100 / frames-of-baseline-lock + 8`, an 8% floor that is never claimed
   to be beaten) — never a bare number, per `docs/ACCURACY.md`.

**Calibration must be against the real scene the camera is pointed at.**
The homography measures real-world distance across whatever plane was
tapped during calibration — if that plane is a TV playing a broadcast match
rather than the actual court, the pipeline still runs and still reports a
number with an error band, but the number is meaningless: it has no
geometric relationship to the broadcast camera's own separate filming of
the match. This is exactly what happened testing this phase indoors (see
below) — worth remembering before treating any indoor/screen test as an
accuracy signal.

### `FrameSequenceSource`: why it doesn't use `ImageReader`

Phase 2's `VideoFrameSource` re-seeks from the nearest keyframe on every
`getFrameAtTime` call — fine for one frame at a time in a scrub UI, too slow
to walk every frame across a serve. `FrameSequenceSource` is the sequential
decoder that was deferred at the time.

The first implementation paired `MediaCodec` with a `Surface`-backed
`ImageReader` (the standard pattern) and crashed on-device with a native
abort: `JNI DETECTED ERROR IN APPLICATION: non-zero capacity for nullptr
pointer: 1 in call to NewDirectByteBuffer from
android.media.ImageReader$SurfaceImage.nativeCreatePlanes`. Adding proper
`OnImageAvailableListener` synchronization (a dedicated `HandlerThread` +
`Semaphore`, since `releaseOutputBuffer(index, render=true)` delivers frames
to the `Surface` asynchronously) did **not** fix it — confirmed by
reproducing the identical crash a second time on real hardware. Root cause:
a hardware decoder can write to a `Surface` in an opaque, GPU-private buffer
format that `ImageReader` cannot safely expose as CPU-readable
`YUV_420_888` planes, regardless of synchronization — an architectural
mismatch, not a timing bug.

The fix was to drop `Surface`/`ImageReader` entirely: configure the codec
with no output surface (`configure(format, null, null, 0)`) and read each
frame directly via `MediaCodec.getOutputImage(outputIndex)`. This reads
straight from the codec's own output buffer and has no such failure mode.
Verified via `javap` against the SDK's `android.jar` before writing the
replacement — the same discipline that caught wrong CameraX API assumptions
twice in Phase 2, applied here to a new API surface (`MediaCodec`'s
image-output path, and separately MediaPipe Tasks) before spending an
on-device iteration on it.

## Deliberately deferred

- **Real-time inference during capture.** Serve speed is computed from the buffered
  clip a second or two after contact; nobody needs it inside 100 ms, and running
  inference alongside high-frame-rate capture will thermally throttle a phone
  inside a set. Only line calling genuinely needs low latency.
- **Real line/edge-based drift re-detection.** Phase 2's drift check is a rough
  pixel-difference heuristic (see the Calibration section above) — Phase 3
  added the classical-CV building blocks (`BallDetector`'s frame differencing
  and blob labeling) this would reuse, but the drift check itself hasn't
  been upgraded to use them yet.
- **A constant-acceleration/gravity-aware trajectory model.** `KalmanTracker2D`
  is constant-velocity, a first cut in the same spirit as `wear/Haptics.kt`'s
  waveforms. Worth revisiting if tracking quality against real-court footage
  proves it insufficient.
- **Gesture arbitration between recording and scoring.** `RecordScreen`'s
  bookmark long-press and `MatchController`'s undo long-press listen to the
  same `WearEventBus` independently. Scoring and recording at once means one
  long-press does both. Fine while the two are used one at a time; worth
  fixing once a phase actually needs them running together.
