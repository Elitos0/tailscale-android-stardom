# Stardom Android Release Guide

## Versioning Contract for In-App Auto-Updater

The Stardom Android client uses monotonic integer comparison on `BuildConfig.VERSION_CODE` (`manifest.version_code > installed.version_code`) to determine update availability.

To ensure the auto-updater discovers and offers each new release:
1. **`stardom.versionCode`**: Must be a strictly positive integer, monotonically greater than any previously published release.
   - Base legacy version code: `110102910`
   - First updater release version code: `110102911`
   - Next releases: `110102912`, `110102913`, etc.
2. **`stardom.versionName`**: Human-readable version string for UI display.
   - First updater release: `1.101.291-stardom.1`

When assembling a release APK (`assembleRelease`, `bundleRelease`, `packageRelease`, `signReleaseBundle`), Gradle validation strictly enforces that both `-Pstardom.versionCode` and `-Pstardom.versionName` are provided. For debug and unit test builds, sensible defaults are used.

## Release Build Command (First Updater Release)

```bash
cd android

./gradlew assembleRelease \
  -Pstardom.headscaleControlUrl=https://headscale.elitoswork.ru \
  -Pstardom.policyApiBaseUrl=https://api.elitoswork.ru \
  -Pstardom.authentikIssuerUrl=https://auth.elitoswork.ru/application/o/policy-api-android-mvp/ \
  -Pstardom.policyApiOidcClientId=policy-api-android-mvp \
  -Pstardom.dashboardBaseUrl=https://dashboard.elitoswork.ru \
  -Pstardom.versionCode=110102911 \
  -Pstardom.versionName=1.101.291-stardom.1 \
  --console=plain
```

Sign the output APK with the Stardom production release keystore:

```bash
apksigner sign --ks /path/to/stardom.keystore \
  --out ../../stardom.apk \
  build/outputs/apk/release/android-release-unsigned.apk
```
