# Architecture

## Modules

```
:core    Pure Kotlin/JVM. Wire protocol, the tennis scoring engine, the court
         calibration homography, and the serve-speed vision math. Android-free
         on purpose, so it unit-tests on the JVM in milliseconds.
:phone   Android app. CameraX capture, storage, UI, phone side of the Data Layer.
:wear    Wear OS app. Haptics, tap input, watch side of the Data Layer.
```

`:phone` and `:wear` share one `applicationId` (`com.tennisreplay`; debug builds
add `.debug` to both, so they install beside the Play build) and **must be
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

`Android/data/com.tennisreplay/files/Movies/sessions/<id>/`

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

### Calibration lives in the recording's pixel space

Found in the first real-court test (Galaxy S25 Ultra, 2026-09-08): a live
"Freeze frame" calibration was saved in the wrong coordinate space, twice
over. `PreviewView.getBitmap()` is the whole *view* — 2340x1080 with
FIT_CENTER's letterbox bars baked in — and CameraX's default `Preview` stream
was 4:3 while the FHD recording is 16:9, so the recording is a vertical crop
of what the preview showed (the near baseline sat at 84% of the preview's
height but 95% of the video's). Every tapped corner landed somewhere else on
the frames the serve analysis measured, and nothing failed loudly.

The fix has three parts: `Preview` now asks for 16:9 like the recording;
`PreviewFrames` converts a snapshot into the recorded video's pixel space
(computed from the real stream sizes, not assumed, since CameraX may fall
back to another aspect ratio) before anything is tapped on it — the drift
check's live frame too, so it compares like with like; and
`CalibrationPoints.scaledTo` refuses to rescale a calibration onto a frame of
a different shape rather than silently applying it.

### Finding the court automatically

The same test's first feedback was that the frozen frame was too small to tap
corners on. The frame now fills the screen beside a narrow control panel, with
pinch-zoom, a magnifier loupe while placing or dragging a corner, and live
grid re-projection while dragging (`CalibrationCanvas`). But first,
`core/vision/CourtLineDetector` usually finds the court on its own: a
"brighter than both sides" line-pixel test, a Hough transform with vote
removal, then every pairing of detected lines against the real court model
scored by how many of the model's line samples land on line pixels, refined at
full resolution by fitting each line's brightness ridge. Lessons from the real
frames, each now a comment where it applies:

- Score each image pixel once. Uniform court-space samples let a hypothesis
  squeezed toward the vanishing point count every collapsed sample as a hit.
- Refine the best few hypotheses, then choose. A fit built from the service
  line and far baseline out-scored the right one by 421 to 419 before
  refinement, with its near baseline 45 px off.
- Choose refined fits by the detected lines they explain, not the pixel score
  alone. With a player standing on the near baseline, a fit that pushed that
  baseline off-frame dodged the misses the player's body caused — and left the
  strongest painted line in the image unexplained.
- Find each line's centre from its two half-height edges against *each side's*
  background. A single threshold drifted ~2 px toward sunlit green, moving a
  corner ~3 px along the sideline.
- Report corners where the painted lines cross, not from the least-squares
  homography, which spreads lens distortion across the court: the user checks
  corners against the paint.
- From a low mount the net tape can sit almost on the far baseline; far
  corners are the least certain part of any calibration from there.

**Drift detection re-detects the court.** `DriftDetector` runs
`CourtLineDetector` on the live frame (in the recording's pixel space, like the
saved calibration) and compares corners: `cornerShift` is the largest move of
any corner inside the frame — an off-frame corner is extrapolated and jitters
with the line fit — and more than 0.8% of the frame width (~15 px on 1920-wide
video) flags "Camera may have moved". Detection's own jitter on the 2026-09-08
footage was a few pixels; the phone's mid-recording bumps moved corners
20-40 px. When the court can't be found (a dark evening, a covered lens) it
falls back to the original first cut: `frameDifference`, a mean absolute
difference over a small grayscale grid against the saved reference frame,
which false-positives on lighting changes and misses small nudges. It runs
once, in `RecordScreen`, off the main thread (re-detection takes about a
second) when the live preview comes up — not on Home, which does not bind a
camera and only shows whether *some* calibration exists.

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

## Serve detection and speed

Serves are found and measured with no marking by the user — the second
field-test request (2026-09-10): nobody can touch the phone mid-match.
`phone/vision/ServeScanner` does the work; `ServeScanService` runs it in the
background as soon as `RecordingService` finalizes a recording (a foreground
service, type `mediaProcessing` on Android 15+ and `dataSync` below, since a
long match takes minutes to scan), and Replay's **Find serves** runs it for
older recordings. Results are written to `serves.json` beside the video and
shown in Replay as one chip per serve.

### Why Phase 3's pipeline was replaced, not tuned

Phase 3 analyzed a bookmarked moment: two-frame differencing for ball
candidates, a constant-velocity Kalman tracker started on the largest blob,
contact at the sharpest speed jump, and speed from pixel displacement scaled
by the ground-plane homography's local Jacobian. The first real-court footage
(2026-09-08, four recordings) broke every stage:

- Two-frame differencing marks the ball twice and the whole moving server
  besides — 100-480 blobs a frame. The tracker started on the server's body
  and never left it.
- The ground-plane Jacobian has no geometric meaning for a ball 2.6 m in the
  air.
- Two serves read **58 and 10 km/h**; the analysis below puts them near
  150 km/h.
- None of the four recordings had a single bookmark, because the user couldn't
  make one while playing — the actual feedback.

The replacement was prototyped in Python/OpenCV against those recordings
(frames, pose, and hand-checked serve times), then ported to `:core`.

### The pipeline

1. **Court, per recording and per serve.** `CourtLineDetector` on a frame of
   the recording itself, and again at each serve: the phone moved twice inside
   one 2026-09-08 recording. The saved calibration is only a fallback.
2. **Pose proposes.** MediaPipe Pose (lite, `RunningMode.VIDEO`, up to 3
   people) on ~10 frames a second across the whole recording, on
   half-resolution *colour* frames from `FrameSequenceSource.decodeSampledColor`
   — on grayscale the model lost the server for most of each serve.
   `ServeProposals` then looks for a serve's shape, and each rule is there
   because a looser version fired on something real:
   - feet on the court foreground at or behind the near baseline (MediaPipe
     hallucinated poses in the trees above the court);
   - back to the camera before the toss (someone walking toward the camera
     with an arm raised);
   - one wrist above the head for 3+ samples, *then* the other (a
     groundstroke follow-through raises one arm);
   - feet still from the toss until the racket rises — only until then, since
     a server moves off straight after, and measuring stillness over the whole
     window lost a real serve.
3. **The ball flight confirms.** Every frame from 0.7 s before the racket
   rises to 2 s after, full-resolution grayscale, through `MotionBlobs`: a
   pixel counts as moving only if it differs from *both* neighbouring frames,
   which leaves just the ball's current position, and the server's body (from
   pose) is masked out. `ServeFlight` then chains candidates frame to frame
   and judges every chain on the full measurement it would produce:
   - **A serve needs a toss** — a short track falling nearly straight down
     above the head just before the flight — and a flight that starts above
     the head. Pose alone proposed groundstrokes and overheads on a rally
     recording.
   - **Contact** is midway between the toss's last visible frame and the
     flight's first point; the racket hides the ball in between.
   - **The bounce** is the sharpest kink in on-screen vertical motion from the
     track's lowest on-screen point onward. Not the low point itself: seen
     from behind, a ball flying away climbs the screen through perspective
     faster than it falls, so its on-screen low point comes before it lands
     (a simulated 162 km/h serve read 183). Not the sharpest kink anywhere:
     near the camera, perspective alone kinks a real track more than the
     bounce does far down the court.
   - **A net fault** is a track whose bounce lands at the net or on the
     server's side of it — confirmed frame by frame on one real serve, where
     the ball rolled back toward the camera. No speed is claimed for it.
   - The winner is the best *complete* chain — a plausible measured serve,
     then a net fault, then length. Longest-chain-wins alone picked the slow
     player on the next court, the rising toss, and a short junk track that
     happened to end in the service box.
4. **Speed from contact to bounce.** The bounce point is on the ground, so the
   homography places it exactly; the flight time comes from frame timestamps;
   contact is taken as 2.6 m above a point 0.4 m in front of the server's
   feet. Straight distance over time is the average speed, and the quadratic
   drag equation (`s = ln(1 + k v0 t) / k`, k = 0.0202 /m for a tennis ball)
   turns that into the launch speed a radar gun reports. A full 3D fit of the
   flight was tried first and rejected: it needs the camera's focal length,
   and plausible estimates (the device's lens calibration scaled to the video
   crop, and two homography-based estimates: 1318-1606 px) moved the answer
   by ~20%. The error band is ±1 frame at each end of the flight plus fixed
   allowances for contact position, bounce position, and drag, in quadrature
   — about ±7-9% at 60 fps.

### How it was checked

- **Synthetic, in `:core:test`.** `ServeFlightTest` simulates serves in 3D
  (gravity, drag, a bounce) through a camera consistent with the real
  2026-09-08 calibration, adds random and static clutter, and recovers
  162 km/h and 108 km/h launches within 6%, a net fault, and nothing from
  clutter alone. `ServeProposalsTest` covers each pose rule above.
- **Real footage, off-device.** Pose and blob dumps from the four 2026-09-08
  recordings through the Kotlin pipeline: 13 of the 14 serves counted by eye
  were found — 8 measured at 132-166 km/h and 5 net faults — with 2 false
  detections in the 10-minute rally recording (most likely overheads, where a
  falling ball above the head looks like a toss). There is no radar reference
  yet: the speeds are plausible, not verified.
- **On-device** (Galaxy S25 Ultra): the 57 s `18-10-14` recording scanned in
  85 s — ~70 s of pose, ~8 s per proposed serve — finding both serves at 147
  and 153 km/h, within 3% of the off-device run on the same footage (on-device
  pose put racket-up up to half a second differently; contact and bounce
  landed within 15 ms). See the README's "Verifying Phase 3".

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

Serve detection added a second output: `decodeSampledColor`, roughly one
frame per interval as a half-resolution ARGB bitmap for pose. Decoding still
visits every frame (H.264 can't skip), but only sampled frames pay for the
YUV-to-RGB conversion, done straight from the chroma planes at their native
half resolution.

## Service-line calls (Phase 4, after the match)

Every measured serve gets a call — `core/court/ServiceLineCall.kt`, following
[ACCURACY.md](ACCURACY.md)'s method: never a bare IN/OUT, and "too close to
call" whenever the margin is inside the error band.

1. **The bounce, between frames.** `ServeFlight` fits the track's last three
   points before the bounce and first three after as straight lines in time
   and takes where their vertical positions meet: the ball touches down
   between frames almost every time. At the bounce the ball is on the ground
   plane, so the homography maps that point exactly.
2. **The target box** is diagonally opposite the server's feet (pose, through
   the homography). Its edges are widened by what still counts as touching a
   line: court dimensions already run to the lines' outer edges, the 5 cm
   centre service line belongs to both boxes, and a bouncing ball's contact
   patch reaches ~2 cm past the measured point.
3. **The margin** is the distance to the edge that decides the call — the
   nearest line when inside, the distance to the box when outside — positive
   in, negative out.
4. **The error band** is worked out at the bounce, per axis: ~2.5 px of pixel
   uncertainty (blob centroid plus calibration corners) times how much court
   one pixel covers there, plus 3 cm for the between-frames fit. Each line is
   judged against its own axis, because from behind the baseline a pixel far
   down the court covers far more court lengthwise than across. On the
   2026-09-08 footage that meant **±5-6 cm on the centre line and sidelines,
   but ±43-52 cm on the far service line**: from that low mount the far service
   box is a few pixels deep. Most close service-line calls from there will be
   "too close" — honestly — and a higher mount is the fix, not the code.

**A bounce hidden behind the server is not called.** The server's head was
first masked only up to just above the nose; on one serve the ball landed
behind the server's head from the camera's view, the tracker followed the
head's own movement, and the serve was called OUT by 1.72 m. The mask now
covers the head — a narrow box sized from the nose-to-shoulder distance, since
masking the full body width that high swallowed another serve's toss and read
it ~20 km/h slow — and a bounce inside it gets no speed and no call.

Checked against the footage frame by frame, with the projected court lines
drawn in: a serve called IN by 46 cm (±5 cm, centre line) lands clearly in the
correct box. Live calls on the watch are deferred — see below.

## Deliberately deferred

- **Real-time inference during capture.** Serve speed is computed from the buffered
  clip a second or two after contact; nobody needs it inside 100 ms, and running
  inference alongside high-frame-rate capture will thermally throttle a phone
  inside a set. Only line calling genuinely needs low latency.
- **Live service-line calls on the watch.** Calls are made after the match,
  from the recording. Live calls need detection running during capture — see
  "Real-time inference" above.
- **Serve detection's known gaps.** Overheads can pass for serves (2 false
  detections in 10 minutes of rallying). Only the player at the camera's end
  is measured: the far server is too small for pose and serves toward the
  camera. Scanning speed on a Galaxy S25 Ultra: the 57 s `18-10-14` recording
  took 66 s — 45 s of pose pass (24 s of it MediaPipe on the GPU, the rest
  decoding; on CPU with per-pixel buffer reads it was 69 s) and 7-13 s per
  proposed serve. So a two-hour match still takes on the order of an hour or
  two to scan in the background; a lower pose rate for long recordings is the
  next lever.
- **120/240 fps.** The single biggest lever on serve-speed error (see
  Frame rate above), still unreachable through CameraX's video path.
