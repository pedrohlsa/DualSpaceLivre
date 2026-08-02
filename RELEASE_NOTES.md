# Release Notes - NewBlackbox

## Version: UI refresh + space switcher (2026-08-02)

### New Features

#### Space switcher & discoverable rename
- A "switch space" action icon in the toolbar opens a picker listing every space
  (custom name or `Espaço N`, plus `＋ Novo espaço`) and jumps to it.
- The overflow menu now exposes **Renomear espaço** and **Configurações**.
  `menu_main.xml` was previously never inflated, so Settings was unreachable from
  the main screen; `MainActivity.onCreateOptionsMenu` now inflates it.
- The rename dialog was refactored into a reusable `showRenameDialog(userId)`.

**Files:** `view/main/MainActivity.kt`, `res/menu/menu_main.xml`,
`res/drawable/ic_spaces.xml`, `res/values/strings.xml`

#### Visual refresh
Replaced the flat gray/white look with an indigo/violet palette and a teal
accent: gradient toolbar with elevation and bold title, soft lavender content
background, teal FAB, app labels as rounded chips with larger icons, matching
status/navigation bars, and a redesigned empty-space state.

**Files:** `res/values/colors.xml`, `res/values/themes.xml`,
`res/layout/view_toolbar.xml`, `res/layout/activity_main.xml`,
`res/layout/item_app.xml`, `res/layout/base_empty.xml`,
`res/drawable/bg_toolbar_gradient.xml`, `res/drawable/bg_main_gradient.xml`,
`res/drawable/bg_app_label.xml`

---

## Version: Cross-User (secondary profile) fixes (2026-07-30)

Fixes for running Dual Space inside a **secondary Android user** (e.g. user 11),
where the guest virtualizes itself as user 0 but the host process runs under a
non-zero Android user. This mismatch made the framework reject many calls with
`Permission Denial: ... asks to run as user 0 but is calling from uid u11a3xx`.
Validated on Moto G50 / Android 12, user 11, with Instagram.

---

### Bug Fixes

#### Location check crash on secondary profiles
**Problem:** Instagram (and other apps) crashed on resume with
`SecurityException: isLocationEnabledForUser asks to run as user 0 but is calling
from uid u11a304`.

**Root Cause:** `ILocationManagerProxy` did not intercept
`isLocationEnabledForUser`, so the guest's userId (0) reached the real
LocationManager while the caller ran under user 11.

**Solution:** Added an `isLocationEnabledForUser` hook that rewrites the userId
argument to `BlackBoxCore.getHostUserId()` (same pattern as the window/AM hooks),
with a safe `true` fallback on `SecurityException`.

**Files Changed:** `Bcore/.../fake/service/ILocationManagerProxy.java`

#### bindService/bindIsolatedService cross-user crash
**Problem:** Binding a guest service crashed with
`SecurityException: service from com.dualspace.livre asks to run as user 0 but is
calling from uid u11a304`.

**Root Cause:** In `IActivityManagerProxy.BindServiceCommon`, the
`resolveInfo != null` path rewrote the calling package to the host but never
rewrote the userId before invoking the real bind.

**Solution:** Added `replaceLastUserId(args)` in that path before
`method.invoke`.

**Files Changed:** `Bcore/.../fake/service/IActivityManagerProxy.java`

#### Proxy service re-instantiated the guest Application (NetworkSecurityConfig / InitializationProvider crash)
**Problem:** Starting a guest background service crashed with either
`Found multiple conflicting per-domain rules` or
`androidx.startup.InitializationProvider ... NullPointerException`.

**Root Cause:** `HCallbackProxy.handleCreateService` pointed the proxy service at
the guest `applicationInfo`, but `ActivityThread.getPackageInfo()` rebuilds a
fresh `LoadedApk` (no cached Application) whenever the applicationInfo's user id
differs from the calling user. On a secondary profile it therefore
re-instantiated the guest Application without its ContentProviders.

**Solution:** Copy the guest `ApplicationInfo` and set
`guestInfo.uid = Process.myUid()` so the framework reuses the already-created
`LoadedApk` and its Application instead of rebuilding one.

**Files Changed:** `Bcore/.../fake/service/HCallbackProxy.java`

---

### Known Issue (unresolved)

**Instagram still does not reach the feed inside a space.** After the fixes above,
a *fresh* proxy service process reaches `InstagramAppShell.onCreate()` and then
throws `IllegalStateException: Can't find current process's name` (Meta apps read
their own process name via `getRunningAppProcesses`; the child service process's
record is not yet registered with the guest processName when Instagram reads it).
A second crash in `com.instagram.android:fbns` shares the same root cause. This is
a deeper multi-process bootstrap issue and needs dedicated work — see the project
memory notes for the exact next step.

---

## Version: Latest Build (2026-01-31)

---

### New Features

#### VPN Network Mode Toggle
Added a new setting to choose between VPN and normal network mode for sandboxed apps.

- **Location:** Settings → Others → Use VPN Network
- **Default:** OFF (normal network mode)
- When enabled, traffic is routed through BlackBox's VPN service
- Requires app restart to take effect

**Files Changed:**
- `app/src/main/java/top/niunaijun/blackboxa/view/main/BlackBoxLoader.kt`
- `app/src/main/java/top/niunaijun/blackboxa/view/setting/SettingFragment.kt`
- `app/src/main/res/xml/setting.xml`
- `app/src/main/res/values/strings.xml`
- `Bcore/src/main/java/top/niunaijun/blackbox/app/configuration/ClientConfiguration.java`
- `Bcore/src/main/java/top/niunaijun/blackbox/BlackBoxCore.java`

#### Device Information Logging
Added comprehensive device info header in logcat for easier debugging:
- Android version, SDK level, security patch
- Device manufacturer, brand, model, hardware
- Supported CPU/ABIs (32-bit and 64-bit)
- Memory info (heap usage)
- App version and package info
- Build fingerprint and timestamps

---

### Bug Fixes

#### VPN Permission Fix
**Problem:** VPN service failed to establish interface (`builder.establish()` returned null).

**Root Cause:** Android requires `VpnService.prepare()` to be called from an Activity before VPN can be established.

**Solution:** Added VPN permission request to `MainActivity.kt` on app launch.

**Files Changed:**
- `app/src/main/java/top/niunaijun/blackboxa/view/main/MainActivity.kt`

---

#### Android 10 Black Screen Fix
**Problem:** Apps would show a black screen and timeout on Android 10 (API 29).

**Root Cause:** 
- `BRAttributionSource.getRealClass()` returns `null` on Android < 31
- `SystemProviderStub.invoke()` crashed calling `.getName()` on null class
- `ClassInvocationStub.injectHook()` crashed when `getWho()` returned null

**Solution:**
- Added null checks in `SystemProviderStub.java` for API version checks
- Added null check in `ClassInvocationStub.java` to skip hooks when services don't exist

**Files Changed:**
- `Bcore/src/main/java/top/niunaijun/blackbox/fake/service/context/providers/SystemProviderStub.java`
- `Bcore/src/main/java/top/niunaijun/blackbox/fake/hook/ClassInvocationStub.java`

---

### Removed Features

#### Xposed Framework Support
- Removed `BXposedManagerService` and related AIDL interfaces
- Removed "Install Xposed Module" UI and Settings entries
- Cleaned up Xposed-related flags and package checks

---

### Stability Improvements

#### Anti-Detection Native Hook Stability
- Removed `LOGD` calls from critical native hooks to prevent infinite recursion
- Fixed syntax errors in hook implementations
- Hooks now silently return `ENOENT` for blocked paths

---

### Known Issues

#### Oppo/ColorOS Thermal Stats Error
On Oppo/ColorOS devices, you may see errors like:
```
OppoThermalStats: PackageManager$NameNotFoundException: top.niunaijun.blackboxa:p0
```
**This is harmless** - it's an Oppo system bug where their thermal management incorrectly uses process names (with `:p0` suffix) instead of package names. The app works normally.

---

### Compatibility

| Android Version | Status |
|-----------------|--------|
| Android 10 (Q)  | ✅ Fixed |
| Android 11 (R)  | ✅ Supported |
| Android 12 (S)  | ✅ Supported |
| Android 13 (T)  | ✅ Supported |
| Android 14 (U)  | ✅ Supported |
| Android 15+     | ✅ Supported |
