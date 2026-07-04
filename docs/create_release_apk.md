# Release APK — build & share

## Setup (already done)
- Keystore: `app/release-key.jks` (gitignored, never commit).
- Secrets: `app/keystore.properties` (gitignored) — storeFile/storePassword/keyAlias/keyPassword.
- `app/build.gradle.kts` → `signingConfigs.release` reads `keystore.properties`, applied to `buildTypes.release`.

## Build
```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew assembleRelease
```
Output: `app/build/outputs/apk/release/app-release.apk`

## Verify signed
```bash
"/c/Users/hp/AppData/Local/Android/Sdk/build-tools/37.0.0/apksigner.bat" verify --verbose app-release.apk
```
Expect `Verified using v2 scheme: true`.

## Don't
- Don't share debug apk cross-device — signed release only.
- Don't send raw `.apk` via WhatsApp — corrupts file, causes "package appears invalid".
- Don't trust `jarsigner -verify` on this apk — checks JAR/v1 scheme only, gives false "unsigned" on v2/v3-signed apks. Use `apksigner verify`.
- Don't commit `release-key.jks` or `keystore.properties`.
- Don't ship next update without bumping `versionCode`/`versionName` (currently 1 / "1.0") — same version won't install over old one.
- Don't install on device below Android 10 (minSdk 29).
