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
- **Instagram**: reaches the feed and remains stable. Validated on 2026-08-02 on
  Moto G50 / Android 12 / physical user 11 with a 75-second cold launch followed
  by two additional cold reopens, with no fatal exception or ANR. The working
  path manually creates the host proxy services without instantiating the guest
  Application twice, reports the guest PID/process name consistently, completes
  Android 12 shortcut futures, and translates accessibility/trust user ids to
  `BlackBoxCore.getHostUserId()`. See `RELEASE_NOTES.md`.
  Follow-up interaction testing also covers search + keyboard, comments, direct
  messages, story replies, conversation photos, and camera launch. Text services,
  usage/storage stats, active notifications, and stale broadcast process records
  all require explicit secondary-user handling; do not forward virtual user 0 to
  the physical system services.
  The Instagram Post composer additionally calls PermissionManager's
  `shouldShowRequestPermissionRationale`; its user id must be translated to the
  physical host user. MediaStore access requires the host app's real storage/media
  permission as well as the guest's virtual permission, otherwise the gallery is
  empty even when the physical profile already contains indexed photos. Public
  MediaStore paths under `DCIM`, `Pictures`, `Movies`, and `Download` must bypass
  external-storage virtualization so the guest can open the original file after
  MediaStore supplies its physical `_data` path.
- **Space switching**: selecting another virtual space stops only the previously
  selected virtual user and removes its proxy activity task. Never cancel all
  host jobs/services or kill every same-UID guest process during a switch: that
  earlier strategy produced `Unable to write current user` and
  `IgSessionManager not initialized` while Instagram persisted account state.
  The destination space must be left untouched.

## App UI (launcher module, `app/`)

- **Theme/palette:** indigo/violet primary with a teal accent. Colors in
  `res/values/colors.xml`, theme in `res/values/themes.xml`. Toolbar uses the
  gradient `res/drawable/bg_toolbar_gradient.xml`; the main screen background is
  `bg_main_gradient.xml`. Keep new screens on this palette (they inherit the
  shared `layout/view_toolbar.xml`).
- **Main screen** = `view/main/MainActivity.kt` + `layout/activity_main.xml`. A
  `ViewPager2` holds one `AppsFragment` per space plus a trailing "add space"
  page. The toolbar subtitle shows the current space name.
- **Spaces:** each space is a virtual user id (`BlackBoxCore.get().users`). A
  space's display name is a per-id remark in `AppManager.mRemarkSharedPreferences`
  under key `Remark<userId>`, defaulting to `Espaço <n>`.
  - `showSpacePicker()` (toolbar grid icon, `menu_main.xml` → `main_switch_space`)
    is a custom-view dialog listing spaces with a color dot each; it jumps via
    `viewPager.setCurrentItem`. It is also shown automatically on launch (when
    there is more than one space) so the user picks a space to enter first.
    Note: ViewPager2 creates fragments lazily, so an off-screen `AppsFragment`
    still reports `userID == 0`; derive a page's real id from
    `BlackBoxCore.get().users[index].id`, not from the fragment.
  - Each space has a color (`applySpaceColor`): the toolbar gradient and status
    bar retint per space. Auto-assigned from the first unused `spacePalette`
    color (with a generated fallback), overridable via overflow → "Cor do
    espaço", stored as `Color<userId>` in the remark prefs. The picker excludes
    colors used by other spaces.
  - `showRenameDialog(userId)` renames a space; reachable from the overflow menu
    (`main_rename_space`) and by tapping the subtitle.
  - The overflow also exposes Settings; `menu_main.xml` is inflated by
    `MainActivity.onCreateOptionsMenu` (it was previously unused).
  - Long-pressing an app icon (`AppsFragment`) already offers clear data / force
    stop / remove / **create home shortcut** (opens the app in that space).
- **Add app to a space:** FAB → `ListActivity`. The installable-app list is built
  in `data/AppsRepository.kt` (filters system apps, the host package, and
  unsupported ABIs). It is cached, so newly installed host apps only appear after
  the Dual Space process restarts.

## Conventions

- System-service hooks live in `Bcore/.../fake/service/*Proxy.java`, registered
  as `@ProxyMethod("<binderMethodName>")` inner classes.
- Build with JDK 21 (see above). Every code change here has been validated with a
  build+install+on-device screenshot/logcat loop; keep that loop.
- Never commit `/.diagnostics/` or `diagnostics/` — they hold private device
  bugreports/ANR traces (device fingerprint, account references).
