# CLAUDE.md — Dual Space Livre (engine)

Context for Claude Code when working in this repository.

## What this is

**Dual Space Livre** (`com.dualspace.livre`) — an Android app-virtualization
engine that runs isolated copies of other apps in "spaces" without extra Android
users. It is a fork of the open-source **BlackBox** engine
(`top.niunaijun.blackbox` / app module `top.niunaijun.blackboxa`), which is
derived from VirtualApp. The upstream remote is `ALEX5402/NewBlackbox`.

- App module: `app/` (`top.niunaijun.blackboxa`) — the launcher/UI.
- Engine core: `Bcore/` (`top.niunaijun.blackbox`) — the virtualization runtime,
  system-service hooks (`fake/service/*Proxy.java`), and guest bootstrap
  (`app/BActivityThread.java`).
- Reflection helpers: `black-reflection/`, generated `black.*` wrappers under
  `Bcore/src/main/java/black/`.
- `targetSdk 31` (Android 12), `compileSdk 35`, `minSdk 21`. Primary device:
  Moto G50 / Android 12, arm64.

## Build & install

The build **requires JDK 21** (Gradle toolchain uses `source/target 21`). The
Android Studio JBR is JDK 21; the ambient `JAVA_HOME` may be older.

```bash
# from repo root
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" \
  ./gradlew.bat :app:assembleRelease \
  -Dorg.gradle.java.home="C:\Program Files\Android\Android Studio\jbr"
```

- ABI splits are on; the Moto G50 uses `app/build/outputs/apk/release/DualSpaceLivre_0.1.0_arm64-v8a-release.apk`.
- `release` is signed with the **debug** key (`signingConfigs.debug`), so it
  installs over an existing debug/release build without uninstalling.
- `release` is **not** debuggable (no `run-as`). Build `:app:assembleDebug` for a
  debuggable APK when you need `run-as`/thread dumps.
- adb on the dev machine: `C:\Users\<user>\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
- **Git Bash rewrites `/sdcard/...` paths.** Prefix adb-shell commands that use
  on-device absolute paths with `export MSYS_NO_PATHCONV=1`.

## Important runtime context: secondary Android user

This engine is commonly run inside a **secondary Android user/profile** (e.g.
user 11), not the primary user 0. The guest virtualizes itself as user 0, but the
host process runs under user 11 (`u11a3xx`). This mismatch makes the framework
reject calls with `Permission Denial: ... asks to run as user 0 but is calling
from uid u11a3xx`. Many hooks rewrite the userId to `BlackBoxCore.getHostUserId()`
to fix this (window session, activity manager, location). When adding hooks that
pass a userId to a real system service, remember to rewrite it.

## Known working / not working

- **Advertising ID + App Set ID isolation per space**: verified working. Each
  space's clone reports a distinct Advertising ID (engine hook,
  `VirtualAdvertisingIdService`) and a distinct App Set ID (naturally, via GMS
  data isolation). See `Bcore/.../core/identity/`.
- **Instagram**: does **not** yet reach the feed. Several cross-user + multi-
  process crashes were fixed (see `RELEASE_NOTES.md`), but a remaining blocker in
  fresh proxy service processes — `InstagramAppShell.onCreate` →
  `IllegalStateException: Can't find current process's name` — is unresolved.
  Meta apps read their own process name via `getRunningAppProcesses`
  (`IActivityManagerProxy.GetRunningAppProcesses`), and the child service
  process's record isn't registered with the guest processName in time.

## Conventions

- System-service hooks live in `Bcore/.../fake/service/*Proxy.java`, registered
  as `@ProxyMethod("<binderMethodName>")` inner classes.
- Never commit `/.diagnostics/` or `diagnostics/` — they hold private device
  bugreports/ANR traces (device fingerprint, account references).
