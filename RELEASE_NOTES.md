# Release Notes - NewBlackbox

## Version: Space switching and stale-session cleanup (2026-08-02)

Switching spaces now leaves only the selected space available for the next
launch. Running guest apps, proxy services, scheduled jobs and Android recent
tasks from the previous space are stopped without clearing app data or logins.

### Fixes

- The launcher maps ViewPager pages to real virtual-user ids, so the toolbar
  always shows the selected space name even after returning from a guest app.
- Space colors are assigned uniquely. Colors already used by another space are
  excluded from the picker, while the current color remains selectable.
- Changing spaces stops all virtual-user processes, cancels host proxy jobs,
  stops proxy services, removes proxy tasks from Android recents and cleans up
  orphan same-UID guest processes left behind by a restarted engine service.
- Dead task records verify the guest binder before being reused. Tapping an app
  icon now discards a dead Android task and performs a fresh launch.
- Guest broadcasts are delivered outside the central service's binder call.
  Their timeout uses a dedicated executor, and exceptions always finish the
  broadcast, preventing the delayed `ProxyBroadcastReceiver` ANR that made
  login and search appear frozen.

Validated on Moto G50 / Android 12 / physical user 11 with Sasa → Carolina and
Carolina → Leticia switches. After each switch only the host and central service
remained: no Instagram process, `:p0` proxy service or `ProxyActivity` recent
task. A subsequent Instagram launch remained alive for 70 seconds with no fatal
exception, ANR or broadcast timeout.

**Core files:** `MainActivity.kt`, `ActivityStack.java`, `TaskRecord.java`,
`BActivityManagerService.java`, `BProcessManagerService.java`,
`BroadcastManager.java`, `BActivityThread.java`, `ProxyBroadcastReceiver.java`
and the activity-manager AIDL/framework wrappers.

---

## Version: Instagram public-media access (2026-08-02)

Instagram can now open the original photo or video after displaying its
MediaStore thumbnail. Previously, public files in `Download` and other shared
media folders were redirected into the current space's empty external-storage
directory, causing an intermittent "cannot access media" message.

### Fixes

- The host app requests the physical profile's real storage/media permission in
  addition to granting the guest's virtual permission.
- Permission-rationale calls translate virtual user 0 to the physical Android
  user, preventing the Post composer from crashing on secondary profiles.
- Public media paths in `DCIM`, `Pictures`, `Movies`, and `Download` bypass the
  per-space external-storage redirect while private app data remains isolated.
- Fixed the no-redirect rule so it preserves the complete file path instead of
  returning only the matched directory prefix.

Validated on Moto G50 / Android 12 / physical user 11 by opening the Instagram
Post composer and switching among eight gallery items. Every full-size preview
loaded without `FileNotFoundException`, `ENOENT`, fatal exception, or media alert.

**Core files:** `IOCore.java`, `IPermissionManagerProxy.java`, and
`MainActivity.kt`.

---

## Version: Instagram interaction stability (2026-08-02)

Fixed the crashes that occurred after the feed had already opened: entering
search, comments, direct messages or story replies, opening conversation media,
and launching the camera. Validated interactively on Moto G50 / Android 12 /
physical user 11, followed by a 75-second idle observation with no fatal
exception or ANR.

### Fixes

- Added a text-services proxy so spell-checker calls use the physical host user.
  This was the common cause behind every text-field crash.
- Added an isolated usage-stats response for Instagram's standby-bucket check.
- Rewrote storage-stat UID and user arguments to the host UID/user, preventing
  background telemetry threads from terminating the app.
- Active notification queries now return the space-local empty result instead of
  attempting a forbidden physical cross-user query.
- Broadcast delivery skips stale process records whose guest thread has already
  disconnected, preventing server and follow-up `bindApplication` crashes.
- Stopped stale Dual Space processes during diagnosis, releasing roughly 150 MB
  before the corrected build was installed. Camera/secondary activity processes
  may still temporarily increase Instagram's own memory use and are reclaimed
  when Dual Space is force-stopped or upgraded.

**Core files:** `ITextServicesManagerProxy.java`, `IUsageStatsManagerProxy.java`,
`IStorageStatsManagerProxy.java`, `INotificationManagerProxy.java`,
`BActivityManagerService.java`, `HookManager.java` and reflection wrappers.

---

## Version: Instagram startup stability (2026-08-02)

Instagram now opens its feed reliably inside a space on Moto G50 / Android 12,
including when Dual Space runs in physical Android user 11. The final build was
validated with one 75-second cold launch and two consecutive cold reopens, with
the guest process alive and no fatal exception or ANR.

### Fixes

- Proxy services are created with the existing guest `Application`, preventing
  Instagram's `InstagramAppShell` from being instantiated twice. The service is
  registered in both ActivityThread service maps and its creation is acknowledged
  to ActivityManager, avoiding both service-argument crashes and false service ANRs.
- `getRunningAppProcesses` always exposes the current guest PID with the virtual
  process name, fixing Meta's early process-name validation.
- Android 12 shortcut calls now return completed `AndroidFuture` values with the
  expected payload type instead of `null` or an incompatible list.
- The synthesized AndroidX Startup provider now has a non-null empty metadata
  bundle, satisfying Instagram's provider bootstrap without double initialization.
- Accessibility, shortcut and trust/keyguard calls translate virtual user 0 to
  the physical host user, fixing cross-user crashes in toast cleanup,
  `isDeviceLocked`, and shortcut maintenance.

**Core files:** `HCallbackProxy.java`, `IActivityManagerProxy.java`,
`IShortcutManagerProxy.java`, `IAccessibilityManagerProxy.java`,
`ITrustManagerProxy.java`, `IPackageManagerProxy.java` and reflection wrappers.

---

## Version: UI refresh + space switcher (2026-08-02)

### New Features

#### Space switcher, launch selector & per-space color
- A "switch space" action icon in the toolbar opens a picker listing every space
  (custom name or `Espaço N`, a color dot each, plus `＋ Novo espaço`) and jumps
  to it. The picker is also shown **automatically on launch** (when there is more
  than one space) so you choose which space to enter first.
- **Per-space color**: each space has its own color; the toolbar gradient and
  status bar retint when switching, so different accounts are recognizable at a
  glance. Auto-assigned, overridable via overflow → **Cor do espaço**.
- The overflow menu now exposes **Renomear espaço**, **Cor do espaço** and
  **Configurações**. `menu_main.xml` was previously never inflated, so Settings
  was unreachable from the main screen; `MainActivity.onCreateOptionsMenu` now
  inflates it.
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

### Instagram follow-up

The remaining Instagram process-name/bootstrap issue described by this release
was resolved in **Instagram startup stability (2026-08-02)** above.

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
