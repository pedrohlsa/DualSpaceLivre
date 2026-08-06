# Release Notes - NewBlackbox

## Version: Per-space GSF id and serial, dead hooks removed (2026-08-05)

Continues the identifier work. A space now carries five identifiers of its own,
all persisted in `.dual-space-identity`: Advertising ID, App Set ID, ANDROID_ID,
**GSF id** and **Build serial**.

- **GSF id** (Google Services Framework) is a stable cross-app device
  identifier, read by querying `com.google.android.gsf.gservices` for
  `android_id`. Every space reported the host's single value. Intercepted in
  `ContentProviderStub`, which already wraps every non-system provider, and
  answered with a `MatrixCursor` carrying the space's own id.
- **Build serial** came from `IDeviceIdentifiersPolicyProxy#getSerialForPackage`,
  which returned `md5(host package)` — a constant, so all spaces matched. It now
  comes from the space's identity file.
- **16 registered hooks did nothing and were deleted.** Each was wired into
  `HookManager` but had `getWho()` returning null and an empty `inject()`, so it
  never hooked anything — the same shape as `AndroidIdProxy`. Removed:
  `ApkAssetsProxy`, `AudioRecordProxy`, `AuthenticationProxy`, `DeviceIdProxy`,
  `FeatureFlagUtilsProxy`, `FileSystemProxy`, `GoogleAccountManagerProxy`,
  `ISettingsProviderProxy`, `LevelDbProxy`, `MediaRecorderClassProxy`,
  `MediaRecorderProxy`, `ReLinkerProxy`, `ResourcesManagerProxy`,
  `SQLiteDatabaseProxy`, `SystemLibraryProxy`, `WorkManagerProxy`.

**Not done, and why.** The MediaDrm/Widevine device id is the strongest
identifier still shared between spaces, but `MediaDrm` is a plain Java class
that goes straight to JNI; this project can only intercept binder interfaces and
content providers, so reaching it means adding an inline-hook framework.
`Build.*` and the screen metrics — Instagram's User-Agent — were left alone on
purpose: faking them per space would make each space look like a different
phone, and a mismatch against other observable signals is itself a flag.

**Verified on the Moto G50 / Android 12 / user 11:** Instagram opens with no
crash after the hook removal, space switching and the add-apps screen still
work, and space 0's identity file holds distinct persisted values
(`android_id`, `gsf_id`, `serial`, `advertising_id`). The ANDROID_ID hook was
observed serving the guest repeatedly. The GSF hook did not fire during the
test — nothing queried gservices in that session — so it is implemented but not
yet observed live.

---

## Version: Virtualize ANDROID_ID per space (2026-08-05)

Every space was handing Instagram the **host's** ANDROID_ID, so all the cloned
accounts shared one device fingerprint and could be linked to each other.

`AndroidIdProxy` was registered in `HookManager` and read as if it solved this,
but the class was an empty stub — `getWho()` returned `null`, `inject()` was
empty, and none of its `@ProxyMethod` handlers ever ran (it never produced a
single log line). Its fallback generated a brand new random id on every call, so
had it been wired up it would have made things worse.

The real implementation now sits where the lookup actually passes through:
`SystemProviderStub#getVirtualAndroidId` answers the settings provider `call()`
with `VirtualIdentityManager.getAndroidId(userId)` — generated once per space
with `SecureRandom` and persisted alongside the advertising id, so it is
different between spaces and constant for the life of each one. The `call()`
signature moved between API levels, so the `GET_*` selector and the setting name
are matched by value rather than by position. `AndroidIdProxy` was deleted.

Verified on the Moto G50 / Android 12 / user 11: the guest is served the space's
own value (`SystemProviderStub: ANDROID_ID served for space 0`), and the value is
unchanged after a full restart of the app.

**Each account needs one more login after installing this** — Instagram sees the
device identity change once, and is stable from then on.

**Correction to the previous entry.** The "SIGKILL loses Instagram's pending
SharedPreferences write" explanation was not confirmed. Inspecting the guest data
with a debuggable build showed zero `.xml.bak` files across all seven spaces and
intact, recently written preferences: nothing was being lost on disk. The logout
is server-side, which points at device identity. The task-removal fix in that
entry is still correct and stays — it stopped the engine from tearing itself
down — it simply was not the cause of the logouts.

---

## Version: Stop tearing down the app on space switch (2026-08-04)

Fixes the long-standing Instagram logout, and very likely the reel uploads that
hang on "processing".

**Root cause.** Switching pages in the launcher called `stopUser()` on the space
you were leaving. That runs `ActivityStack.clearAllTasks()`, which called
`finishAndRemoveTask()` on every host task registered as virtual. Guest
activities are started into whatever host task launched them, so the launcher's
own task was in that set. Removing it made Android tear down the whole
application:

```
Killing com.dualspace.livre/u11a304       (adj 250, in active use) : remove task
Killing com.dualspace.livre:black/u11a304 (adj 905)                : remove task
Killing com.dualspace.livre:p0/u11a304    (adj 250)                : remove task
```

`:black` is the engine's own system process, so the entire runtime went down
mid-flight. Instagram writes its session through `SharedPreferences`, whose disk
write is asynchronous; a SIGKILL between `apply()` and the write loses it, and
the next launch sends a stale token — the reported `1675002 Unauthorized logged
out query`. The same kill ends an in-progress upload, which is why a reel can sit
on "processing" forever.

**Fixes.**
- `ActivityStack.clearAllTasks()` and `removeTaskLocked()` only finish a task
  whose base activity is one of our proxy activities
  (`ProxyManifest.PROXY_ACTIVITY_PREFIX`). The host's own task is never removed.
- The launcher no longer stops the previous space when the page changes.
  Stopping is now explicit: space menu → "Parar espaço", with a confirmation,
  for when RAM needs freeing.

**Verified on the Moto G50 / Android 12 / user 11:** with a cloned Instagram
running, switching spaces leaves its process alive and logs no `remove task`
kill. Before the fix the same sequence killed it every time.

**Note:** a token Instagram already rejected cannot be restored by the engine, so
each affected account needs one more login after installing this.

---

## Version: Welcome screen and adaptive grid (2026-08-04)

Follow-up to the ambient pass, driven by feedback on the launch experience.
Engine untouched; only `app/` and docs change.

- **Welcome screen replaces the launch bottom sheet.** Centred greeting, then
  one card per space laid out **two per column, scrolling sideways** — a
  `GridLayout` with `rowCount=2` and `orientation="vertical"` inside a
  `HorizontalScrollView`, so the strip advances two spaces at a time. Card width
  is tuned (170dp) so two full columns fit with the next one peeking, which is
  what signals the strip scrolls.
- Each card carries the space colour as a gradient, with the initial in a
  translucent badge, the name and the app count. The ambient glow follows
  whichever pair is in front.
- **Closing is always possible**: an X in the corner, plus the back button. The
  full list with rename/colour/delete is one tap away under "Gerenciar espaços".
- **The app grid divides itself by how many apps a space has.** One app is shown
  large and centred (96dp tile) instead of stranded in the corner; each new app
  splits the room further (2 → 3 → 4 columns) with the tile and the label
  scaling down together. The icon now fills 78% of its tile — at 68% the frame
  read as an empty box.
- **"Excluir espaço" is red** in the per-space menu, so the destructive entry
  looks destructive.

**Tested on the Moto G50 / Android 12 / user 11** with `adb install -r`, no
uninstall and no data cleared: welcome layout and horizontal paging, entering a
space, the red delete entry, and the single-app grid.

---

## Version: Ambient space identity (2026-08-04)

Second pass over the UI. The first redesign was correct but generic; this one
gives the app a look of its own. Still **no engine change** — only `app/`.

- **The space colour now paints the screen, not a dot.** A radial glow in the
  space colour sits behind the top of every screen, and the hero card border,
  the colour dot, the chevron, the FAB and the halo behind each app icon all
  pick it up. Sasa is amber, Carolina is violet, and they no longer look like
  the same screen. `view/base/Ambient.kt` owns all of it.
- **Hero header.** "ESPAÇO ATUAL" overline, the space name at 27sp, app count
  and position, on a glass card. It is the subject of the screen and the entry
  point to the picker.
- **Glass surfaces.** Translucent fill plus a gradient hairline border
  (`bg_glass_card`), with the border tinted by the accent where it matters. No
  blur: `RenderEffect` would cost too much on a Moto G50.
- **Motion.** Staggered entrance for the app grid and the sheet rows, a press
  scale on every tappable row (`animator/press_scale.xml`), a glow crossfade and
  a text rise when the space changes, and a slide-in for the "Adicionar N
  aplicativos" bar.
- **Space picker.** Gradient colour chips instead of dots, the active space
  outlined in its own colour with an "Atual" pill, and every row bordered in its
  space colour. Sheets now open expanded (`skipCollapsed`) and are measured
  before being attached, so the pinned footer is always visible above the
  navigation bar.
- **Add apps.** Big title, glass rows, larger checkboxes and a floating pill
  button.

Fixes found while testing on the device:
- The glow had a hard edge where the view ended; the gradient radius now matches
  the view height exactly (`Ambient.GLOW_HEIGHT_DP`).
- Foreground colour on an accent is chosen by luminance (threshold 0.45), so the
  amber FAB gets dark text instead of unreadable white.

**Tested on the Moto G50 / Android 12 / user 11** with `adb install -r`, no
uninstall and no data cleared: launch, picker, switching between an amber and a
violet space, add-apps with multi-selection, entrance animations and the
expanded sheet.

---

## Version: Full UI redesign (2026-08-04)

The launcher module was redesigned end to end. **No engine change**: the
virtualization runtime, space isolation, Advertising ID / App Set ID handling,
session management and process control were not touched. `stopUser` on page
change keeps exactly the semantics documented in the space-switching section
below.

### Design system
- Single `Theme.BlackBox` on `Theme.MaterialComponents.DayNight.NoActionBar`,
  with semantic tokens in `res/values/colors.xml` and `res/values-night/colors.xml`
  (`ds_bg`, `ds_surface*`, `ds_on_surface*`, `ds_outline`, `ds_violet`, `ds_blue`).
- Dark is the default look (graphite/near-black + violet/blue accents); light is
  fully supported. Settings → Aparência offers Escuro / Claro / Seguir o sistema,
  applied by `ThemePrefs` from `BaseActivity.onCreate` (never from
  `Application.onCreate`, which also runs inside guest processes).
- The purple toolbar gradient was dropped in favour of a flat surface app bar.
- New vector icon set under `res/drawable/ic_*_24.xml`; new shape/ripple
  drawables (`bg_surface_card`, `bg_icon_tile`, `bg_sheet`, `ripple_card`, ...).

### Main screen
- The current space is shown in a tappable card: colour dot, name, app count and
  position ("3 aplicativos · Espaço 1 de 6"). Tapping it opens the space picker.
- App grid: rounded icon tiles, span count derived from the screen width
  (4 on a phone, up to 7 on a tablet), padding that clears the FAB.
- Tapping an app shows a spinner over its tile, dims the others and ignores
  further taps until the clone opens (or a 20 s fallback expires).
- Extended FAB "Adicionar app"; redesigned empty state.
- The bottom dots indicator was removed — the space card carries that
  information now.

### Spaces
- Space picker is a bottom sheet: colour dot, name, app count, a check on the
  active space, a per-space overflow (renomear / cor / excluir) and a pinned
  "Criar novo espaço" row that jumps to the trailing page and opens the app
  picker.
- Deleting a space asks for confirmation and states that the data is lost.
- Colour picker only offers colours no other space uses. Availability is decided
  by perceptual RGB distance (< 45 counts as taken), and a one-time
  `palette_v2` migration remaps colours stored by older versions to the nearest
  free entry of the current 14-colour palette.

### Add apps
- The `SimpleSearchView` was replaced by a plain `EditText` + `TextWatcher`.
  The old widget echoed composing text back into the field, which duplicated
  characters typed with a dead-key accent (ã, ç, é).
- Multi-selection with a fixed "Adicionar N aplicativos" bar; apps already in the
  space show a badge and cannot be selected.
- `AppsRepository.installApks` installs a batch and reports a single result.
- Fixed a real layout bug: `StateView` and `RecyclerView` were both
  `match_parent` inside a vertical `LinearLayout`, which pushed the list off the
  screen. Both screens now stack them in a `FrameLayout`.

### Settings
- Grouped into Aparência / Espaços e Google / Privacidade e isolamento / Sobre,
  with plain-language descriptions.
- New "Sobre o MultiSpace" screen (`AboutActivity`): version, how it works and a
  privacy statement. No ads, analytics or trackers were added.

### Other
- `LoadingActivity` no longer uses the third-party "cat loading" animation; it
  shows a themed dialog instead.
- `FakeManagerActivity` was ported off `SimpleSearchView` since it shares
  `activity_list.xml`.
- `GmsManagerActivity` uses `CompoundButton` instead of `Switch` (the row switch
  is now a `SwitchCompat`).

**Tested on the Moto G50 / Android 12 / physical user 11** with `adb install -r`
over the existing install, without uninstalling or clearing data: launch and
space picker, switching spaces (header name/colour/position update), batch add
of 2 apps, launching indicator, long-press menu, removing apps, rename, colour
picker filtering, Settings, About, light theme and back to dark, empty state.

---

## Version: Bounded proxy job scheduling (2026-08-03)

Fixed a host crash loop caused by guest apps accumulating more than Android's
100 jobs-per-UID limit. The repeated host restarts interrupted Instagram session
initialization and could surface as accounts being disconnected.

- Proxy job records now include the virtual user in their key.
- The engine accepts at most 64 live proxy job records, leaving capacity for
  host WorkManager jobs; additional guest schedules return `RESULT_FAILURE`.
- Starting the virtual job service removes stale `ProxyJobService` jobs that no
  longer have an in-memory record, without clearing app data or account files.
- Virtual `cancel` and `cancelAll` now remove their corresponding records and
  cancel the matching Android job.

On the Moto G50, the accumulated host queue was reset once from 100 jobs. After
installing the bounded scheduler and launching the already-disconnected Sasa
space, the queue stabilized at 13 jobs with the Instagram process alive and no
`JobScheduler 100 job limit exceeded`, fatal exception or engine ANR.

**Core files:** `BJobManagerService.java` and `IJobServiceProxy.java`.

---

## Version: Safe space switching and stale-session cleanup (2026-08-03)

Switching spaces now stops only the virtual user that was previously selected.
The destination space, its jobs and its services are left untouched so
Instagram can finish loading and persist its current account safely.

### Fixes

- The launcher maps ViewPager pages to real virtual-user ids, so the toolbar
  always shows the selected space name even after returning from a guest app.
- Space colors are assigned uniquely. Colors already used by another space are
  excluded from the picker, while the current color remains selectable.
- Changing spaces stops the previous virtual user's process and removes that
  user's proxy activity task from Android recents. It no longer cancels global
  host jobs, stops every proxy service or kills every same-UID guest process.
- Dead task records verify the guest binder before being reused. Tapping an app
  icon now discards a dead Android task and performs a fresh launch.
- Guest broadcasts are delivered outside the central service's binder call.
  Their timeout uses a dedicated executor, and exceptions always finish the
  broadcast, preventing the delayed `ProxyBroadcastReceiver` ANR that made
  login and search appear frozen.

The original global cleanup was rolled back after device logs showed
`Unable to write current user` and `IgSessionManager not initialized`, consistent
with Instagram being terminated while persisting its session. The corrected APK
was installed over the existing app without clearing storage. Sasa's existing
server token was already rejected by Instagram as `Unauthorized logged out
query`; the app cannot restore a token that Instagram has already invalidated.

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
