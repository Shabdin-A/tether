# Android development integration

The branch integrates the Android development worktree whose HEAD was d40ed2ba847bc89fae6375e728384fad3e6dbae4 into upstream v1.2.6 (133053c1f59e3760142d8f078abce16f5ca43a27). The development worktree also had uncommitted source and resources. The original installed APKs cannot be reconstructed from that HEAD alone.

## Build and debug

Use JDK 17, Android SDK Platform 36 and the repository Gradle wrapper. Set the SDK location using the IDE or the local Android SDK environment. From the android directory on Windows:

    .\gradlew.bat :app:assembleDebug :client:assembleDebug
    .\gradlew.bat :app:testDebugUnitTest :client:testDebugUnitTest :core:testDebugUnitTest :helper:testDebugUnitTest

The outputs are app/build/outputs/apk/debug/app-debug.apk (Host) and client/build/outputs/apk/debug/client-debug.apk (Client). The build uses the normal local debug key. To update an existing installation, use the same signing identity as that installation. Debug Host and Client together with Android Studio. Compare the in-app report journals from the same call or pairing attempt. Use synthetic contacts and messages for testing.

## Scope

- Client dark/cyan Link (Network, Pairing and Diagnostics), shared navigation, Messages delivery and search views, New Message, Thread, Contacts list/details/default-number picker/editor, Keypad, Recents and local/remote call UI.
- Host Setup role feedback, Wireless Debugging pairing and post-pairing verification, local TCP reachability diagnostics and Audio Helper readiness reporting.
- Negotiated safe contact updates and row deletion, including request correlation and stale-row protection. Older applications without these operations need explicit compatibility testing.
- SMS provider and cursor safety, incoming synchronization and strict paged sync.ids validation. Verify inbound, outbound, multipart and reconnect flows with real devices.
- Call diagnostics, Client uplink tracking and Host-local fallback when a bridge path is unavailable. A helper reporting Running or opening an audio route is not proof of modem uplink or audible downlink.

## Integration considerations

The custom Messages, Thread and Recents layouts retain development actions. Some newer upstream layout features, including upstream bulk selection and pinned day/unread markers, are not exposed by the custom screens. The integrated branch restores the upstream USSD entry and result path, Starred access, answer cue and Phone app actions. Samsung-specific repeated lowering of Host call volume was not carried into the conflicting fallback implementation. Verify routing and reconnect behavior before considering either vendor's audio behavior resolved.

Cellular PCM capture failed on the tested Redmi Note 8 Pro Android 11 firmware in prior runs. Actual paired audio, Helper/TLS behavior, SMS delivery and contacts writes must be verified again on physical devices for this commit. No standalone capture-diag command existed in the Android source snapshot. The custom palette intentionally retains design guardrail findings.

Related reviews include amirwmr/tether#31, #32 and #33 and Shabdin-A/tether#1 through #9. They remain scoped reviews. This branch is the combined Android development review and does not replace those branches.
