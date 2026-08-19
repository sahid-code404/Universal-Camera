# Snapcam Auxiliary-Camera Identity Experiment

This branch is a temporary diagnostic experiment. It must not be merged into OmniCam production as-is.

## Hypothesis

Some legacy Qualcomm/Xiaomi camera provider configurations expose auxiliary cameras only to allowlisted client package identities. `org.codeaurora.snapcam` is commonly present in such vendor allowlists on affected device families.

The normal OmniCam package identity (`com.omnicam.app`) saw only two camera devices on the tested POCO M2 Pro: rear main and front.

## Experiment

This branch changes only the installed application identity to:

```text
org.codeaurora.snapcam
```

The Kotlin namespace and classes remain OmniCam's own code. The manifest uses fully-qualified component class names so changing the application ID does not change class resolution.

The diagnostics additionally record:

- actual client package name,
- Camera2 enumeration,
- Camera1 enumeration,
- logical/physical Camera2 membership,
- concurrent camera sets,
- a bounded numeric Camera2-characteristics probe for IDs 0 through 9 that are not enumerated.

The numeric probe does **not** open any camera. A readable characteristic set is not treated as proof that an unlisted ID is independently openable.

## Interpretation

If this build sees more cameras than `com.omnicam.app` on the same ROM, client package filtering is confirmed for that device/ROM.

If this build still sees only the same cameras, package-name allowlisting alone is insufficient and the auxiliary sensors may require additional OEM/system privileges or another vendor-specific path.

## Installation conflict

An already installed GCam/Snapcam build using the exact same application ID will usually conflict because it is signed with a different certificate. The user may need to temporarily uninstall that app before installing this diagnostic APK, then reinstall it afterward.
