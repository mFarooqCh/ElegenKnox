# Quickstart

## Prerequisites

- Android Studio (latest stable)
- JDK 11
- Android SDK with API 29+ and build tools
- A physical device matching the validation matrix (Pixel 7, Galaxy A52, Moto G Power)

## Build and run (Windows)

1) Connect a device with USB debugging enabled.
2) From repo root, install the debug build:

```powershell
.\gradlew.bat :app:installDebug
```

3) Launch the app on the device.

## Manual validation

- Enter "1234" and tap Unlock -> success message
- Enter any other value -> error message
- Leave input empty -> "Empty input" message
- Try leading/trailing whitespace -> trimmed before compare
- Attempt 9th character -> extra input blocked
- Rotate device -> layout stays centered
- Toggle offline/online -> behavior unchanged
- Force stop app and relaunch -> lock screen appears first

## Tests

Unit tests:

```powershell
.\gradlew.bat testDebugUnitTest
```

Instrumented tests:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```
