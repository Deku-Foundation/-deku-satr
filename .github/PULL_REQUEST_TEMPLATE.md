## Summary

Describe the user-facing change and why it is needed.

## Validation

- [ ] Android unit tests and debug APK build pass.
- [ ] Web tests and build pass when browser files are changed.
- [ ] Merged Android manifest was audited; no unreviewed permission was introduced.
- [ ] New source/model artifacts have provenance and license notes.
- [ ] Memory, network, and permission impacts are documented.

## Screen-filter changes

- [ ] UI never implies that capture alone means the screen is protected.
- [ ] Inference errors/stale output fail closed and remain stoppable.
- [ ] Old frames/results cannot overwrite a newer session/orientation.
- [ ] Input frame and mask dimensions are bounded before allocations.
- [ ] Frame images and model outputs are not saved or uploaded.
- [ ] Touch pass-through opacity and residual-image trade-offs are documented.

## Physical-device evidence

- [ ] Tested device/Android version and screen dimensions recorded.
- [ ] Overlay feedback/ghosting, rotation, stop, and permission revocation tested.
- [ ] Secure windows and DRM are reported as unsupported if they cannot be captured.

If a physical-device test has not been run, state that explicitly and keep the PR in draft.
