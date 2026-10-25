# Android capture and privacy notes

## Current implementation

The Android manifest explicitly declares:

- SYSTEM_ALERT_WINDOW — user-visible floating control and filter layer.
- FOREGROUND_SERVICE and FOREGROUND_SERVICE_MEDIA_PROJECTION — visible capture session.
- POST_NOTIFICATIONS — visible session indicator and stop affordance where required.

The manifest merger is instructed to remove android.permission.INTERNET; CI checks the merged debug manifest. The app does not request camera, microphone, contacts, location, or storage permissions.

The native app includes ML Kit Selfie Segmentation 16.0.0-beta6 at build time. The model is bundled into the installed app rather than downloaded during a session. Google reports an approximate 4.5 MB app-size increase for this bundled API. The API is beta and its suitability for arbitrary screen screenshots has not been established.

After the user grants MediaProjection, screen frames are received through an ImageReader. They are downscaled to a 640 px longest edge, held in memory, passed through the local segmenter, and rendered as a blurred mask. The code does not intentionally save frames/masks to storage, log pixel values, or upload media. Blur intermediates and confidence masks exist in app memory while processing.

## Required before release

- Verify the merged manifest and final APK with the Android CI permission audit and a manual APK audit.
- Keep captured frames and masks in memory only; never log pixels or write screenshots to disk.
- Keep only one inference in flight, drop stale frames, and avoid unbounded frame queues.
- Preserve the persistent notification and floating stop action while capture is active.
- Fail closed with an obvious warning on inference errors or stale results; never show a guaranteed-protection claim.
- Test the overlay composition loop on real devices before relying on a screen-wide mask.
- Exclude screenshot content from crash reports and diagnostics.
- Never use Accessibility APIs to read other apps' UI or bypass secure capture restrictions.
- Do not add analytics, remote model download, or any network permission without a separate security review.
- Never infer or display a person's gender; the model is used only for a person mask.

## Known limitations

MediaProjection requires explicit user consent for each session and can be stopped by Android. FLAG_SECURE windows, DRM video, and other protected surfaces may be blacked out or unavailable to capture. The current model can miss people, particularly small or distant people in screenshots. The filter overlay may appear in subsequent projection frames depending on Android version and device vendor; temporal feedback still requires physical testing.

This is an experimental local filter, not a guaranteed safety barrier.


## Android 12+ touch pass-through opacity

Android treats TYPE_APPLICATION_OVERLAY windows as untrusted for pass-through touch. The platform documents a maximum combined obscuring opacity of 0.8. The normal full-screen filter window therefore uses alpha 0.8 so taps can reach the underlying app outside the stop panel. This is a deliberate tradeoff: about 20% of the underlying image may remain mixed with the blurred mask. Strong blur reduces detail but does not eliminate this residual signal. It must not be presented as a complete concealment guarantee.

If capture/inference fails, the filter layer switches to alpha 1.0 and draws an opaque cover. That can block touches to the underlying app until the user taps the always-on-top Stop control. The control panel is a separate window added after the filter surface so it remains above the cover.

Source: https://developer.android.com/reference/android/view/WindowManager.LayoutParams


## Full-display scope and resizing

On Android 14+, Satr passes `createConfigForDefaultDisplay()` to the consent intent. The system still requests explicit user consent, but the app requests the entire default display because single-app capture would not filter other apps. Device manufacturers can still constrain this behavior, and secure/DRM windows may remain unavailable.

The service uses the platform `onCapturedContentResize()` callback to update the bounded ImageReader surface and existing virtual display when the captured content changes size (including orientation changes). Old inference generations are discarded so a late mask from the previous size cannot replace the current session state.


For Android 8–13, the service also watches the default display's real metrics and updates the bounded capture surface after a rotation/size change. On Android 14+ the MediaProjection resize callback is used. The display listener is unregistered on teardown; screen frames and model outputs remain in memory.
