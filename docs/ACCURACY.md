# What single-camera vision can and cannot do here

This document exists so that no feature in this app ever claims more precision
than it has. Read it before building or trusting Phases 3-5.

## The reference point

Hawk-Eye uses ten or more calibrated cameras at ~340 fps and reports a mean error
around 2.6 mm. This project uses **one** phone camera at 30-120 fps from a single
viewpoint. We are three orders of magnitude away from that, and no amount of
software cleverness closes the gap. The goal is a tool that is *honest about its
own uncertainty*, not one that imitates Hawk-Eye.

## Serve speed: expect ±8-15%

On a 190 km/h serve that is roughly ±15-25 km/h.

Where the error comes from:

| Source | Effect |
| --- | --- |
| Frame quantisation | At 53 m/s the ball moves 1.75 m between frames at 30 fps, 0.88 m at 60 fps, 0.44 m at 120 fps. |
| Motion blur | At 1/250 s shutter the ball smears ~21 cm. The detected centroid is biased along the direction of travel. |
| Depth ambiguity | One camera recovers no depth. We constrain the trajectory to the vertical plane through the contact point and the bounce; any out-of-plane motion (slice, kick) leaks straight into the speed estimate. |
| Calibration | Homography error scales the whole measurement. |

**What this is good for:** tracking your own serve over weeks. "Faster than last
month" is a claim this app can support.

**What it is not good for:** quoting a number to anyone. Always show the estimate
with its band, never as a bare figure.

## Service line in/out: expect ±5-15 cm

Better at 120 fps, worse in low light and worse from a low camera.

The one genuinely favourable piece of geometry: **at the moment of the bounce the
ball is on the ground plane**, which is exactly where a court homography is exact.
So the method is:

1. Detect the ball across the frames around the landing.
2. Find the trajectory vertex — the frame where vertical image motion reverses.
3. Interpolate across the frame gap by fitting the incoming and outgoing segments
   and intersecting them. (The true contact usually falls *between* two frames;
   this recovers it rather than snapping to the nearest frame.)
4. Map that single pixel through the homography to court coordinates.
5. Compare against the service line, **with the error band attached**.

Residual error is dominated by the frame gap and by calibration quality — not by
depth ambiguity, because step 4 only ever maps a point that is genuinely on the
plane.

### The design consequence

Any call closer to the line than the error band is a coin flip. Therefore the app
must be able to say **"too close to call"** and mean it. An honest abstention is
worth more than a confident guess, because one visibly wrong call destroys trust
in every correct one.

Report calls as: `OUT by 22 cm` / `IN by 30 cm` / `TOO CLOSE — no call`.

## Setup factors, in order of how much they actually matter

1. **Camera height.** 2-3 m on a fence beats a 1.4 m tripod substantially: less
   foreshortening, better view of the bounce, less occlusion by players.
2. **Frame rate.** Each doubling roughly halves the frame-gap term in both
   measurements. This is the biggest software-side lever.
3. **Light.** A fast shutter needs photons. Bright overcast is ideal; low evening
   sun is the worst case, producing both long shadows and heavy motion blur.
4. **Background.** A plain fence or windbreak behind the court makes the ball
   separable. A car park full of moving people does not.

### On camera position (the counterintuitive one)

For **service line** calls specifically, an elevated *side* view near the net-post
line is geometrically better than one behind the baseline. The service line runs
across the court, so long/short errors displace the ball along the court's *length*
axis — precisely the axis a baseline camera foreshortens into near-zero pixel
resolution 18 m away. A side camera resolves that axis best.

A baseline camera is better for sidelines, and produces more natural-looking video.

**This project is configured for a baseline mount at 2 m+**, per the chosen setup.
That is a defensible trade — better video, better sideline geometry for later
phases — but it means the service-line error band sits toward the wider end of the
±5-15 cm range, and the far service box is the weakest region. If service-line
precision ever becomes the priority, moving the phone to the side for a session is
the single cheapest accuracy upgrade available.

## Ball detection

Off-the-shelf detectors do not work here. A tennis ball at range is 6-15 px and
motion-blurred into a streak; COCO's "sports ball" class collapses on it.

The plan is therefore **classical CV first**: the camera is fixed and the
background static, so background subtraction plus frame differencing, blob/streak
filtering and a Kalman-filtered trajectory fit exploits exactly that structure. It
needs no training data and runs an order of magnitude cheaper than a network.

A TrackNet-style heatmap model in TFLite is the fallback if the classical pipeline
proves insufficient — not the starting point. MediaPipe Pose earns its place for a
different job: detecting racket contact to know when a serve begins.

## Rally line calling

Deliberately out of scope for v1. It is not merely "harder" — it is structurally
worse: rally balls land nearest the lines exactly where players occlude them, at
25-40 m/s with heavy topspin, and the system must additionally decide *which*
bounce is the relevant one. Shipping service-line calls that are trusted is worth
more than rally calls that get ignored.
