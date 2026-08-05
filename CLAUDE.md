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
- **Instagram**: reaches the feed and passed an initial stability test on
  2026-08-02 on Moto G50 / Android 12 / physical user 11 with a 75-second cold
  launch followed by two additional cold reopens, with no fatal exception or
  ANR during that test. The working
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
- **Guest JobScheduler quota**: all virtual apps share the host Android UID, so
  their proxy jobs count against Android's 100-jobs-per-UID limit. Keep proxy
  records keyed by virtual user + process + guest job id, clean stale proxy jobs
  when the virtual job service restarts, and cap live proxy records at 64 to
  reserve capacity for host WorkManager. Exceeding the system limit crashes the
  host repeatedly and can interrupt Instagram session initialization.

## Current handoff / unresolved (2026-08-04)

- **Root cause found and fixed on 2026-08-04 (evening): task removal was tearing
  down the whole app.** `stopUser()` ran automatically on every page change and
  called `ActivityStack.clearAllTasks()`, which finished *any* host task whose id
  was registered as virtual. Guest activities are launched into the host task
  that started them, so the launcher's own task was in that set. Removing it made
  Android kill the entire application — host UI, the `:black` system process and
  every guest — with reason `remove task`, at adj 250 (in active use). Logcat:
  `Killing com.dualspace.livre/u11a304 (adj 250): remove task` together with
  `:black` and `:p0`. SIGKILL at that moment loses Instagram's pending
  SharedPreferences write, which is how the session disappeared and how in-flight
  reel uploads got stuck on "processing". Two changes: `clearAllTasks()` /
  `removeTaskLocked()` now only finish tasks whose base activity is a
  `ProxyActivity` (`ProxyManifest.PROXY_ACTIVITY_PREFIX`), and the launcher no
  longer stops the previous space on page change — stopping is an explicit
  action in the space menu ("Parar espaço"). Verified on device: with a clone
  alive, switching spaces no longer kills it and produces no `remove task` entry.
- **Still open:** an already-invalidated token cannot be recovered, so each
  affected account has to be logged in once more after this fix. Confirm over a
  few days that the session now survives.
- **Instagram sessions still disconnect intermittently (historic notes).** A live trace after the
  JobScheduler fix showed the virtual Instagram process receiving GraphQL error
  `1675002` with `Description: Unauthorized logged out query` and
  `requiresReauth: 0` during `COLD_START`, `ON_DEMAND`, and `PROFILE_SWITCH`.
  The affected token was already invalid from Instagram's point of view; the
  engine cannot restore it, so the account must be logged in again after the
  lifecycle bug is fixed.
- The JobScheduler failure is no longer the immediate trigger: the host had 33
  scheduled jobs (below the new 64 proxy-job cap) during the latest failure.
- The strongest remaining local correlation is task cleanup. Logcat repeatedly
  showed Android killing `com.dualspace.livre`, `:black`, and proxy processes
  with reason `remove task` around the failures. Automatic space switching still
  calls `BActivityManagerService.stopUser(previousUser)`, which calls
  `ActivityStack.clearAllTasks()` and `finishAndRemoveTask()`. A stale/shared task
  id can therefore remove the host task and reset engine processes. The safest
  next experiment is to disable automatic `stopUser` on page changes (leave it
  as an explicit/manual action only), reinstall with `adb install -r`, log in
  once, and monitor without clearing app data.
- One additional Instagram fatal was captured at `2026-08-03 23:43:20` on
  `BackgroundLayoutPreparer` (`AndroidRuntimeException: InvocationTargetException`).
  Pull the surrounding logcat including its nested `Caused by` before choosing a
  code fix.
- **RAM/process finding from 2026-08-04:** on the 3.75 GB Moto G50, swap reached
  about 1.65 GB while two virtual Instagram slots were alive. `p0` was the
  foreground/top activity (roughly 330-500 MB RSS), while the previous `p1` was
  a cached started-service process (roughly 260-330 MB RSS plus 184 MB swap).
  Running `am kill --user 11 com.dualspace.livre` let Android kill the safe
  cached `p1` while preserving foreground `p0`. This is a useful model for the
  eventual switch cleanup: ask Android to reclaim only background-safe proxy
  processes instead of removing tasks or force-killing the whole host UID.
- While a virtual Instagram was active, the physical profile-11 installation
  (`u11_a334`) repeatedly started as well and consumed another 300-350 MB. A
  test with `pm disable-user --user 11 com.instagram.android` immediately closed
  the clone and returned to `MainActivity`; re-enabling the physical package
  restored the required host state. Therefore the current engine still depends
  on the enabled source installation. Investigate why the real-UID Instagram
  process starts alongside the virtual host-UID process; do not solve it by
  disabling/uninstalling the source package.
- The physical user 0 and work user 11 also run duplicate Play Store/GMS and
  vendor processes. Device-only cleanup disabled Google Search/Assistant,
  Motorola Game Mode, Time/Weather widget, App Forecast, and Motorola
  Personalize **only for physical user 11**. These changes are reversible and
  are not part of the repository. Physical Instagram was re-enabled, no app data
  was cleared, and the final measured free RAM was about 1.34 GB with no clone
  open.
- Do **not** uninstall the host or clear its data while diagnosing this issue;
  that would erase every virtual space and invalidate the session test.

## App UI (launcher module, `app/`)

Redesigned on 2026-08-04. The UI layer must never reach into engine behaviour:
space isolation, identifiers, sessions and process control stay as documented
above.

- **Ambient identity (`view/base/Ambient.kt`).** The current space's colour is
  the app's accent: a radial glow behind the top of each screen, the hero/card
  borders, the dot, the chevron, the FAB and the halo behind app icons. Every
  drawable there is a plain `GradientDrawable` — no blur, no bitmaps, because a
  Moto G50 has to draw it. Rules: glow views are always `Ambient.GLOW_HEIGHT_DP`
  tall (the gradient radius matches the height, otherwise the light gets a hard
  edge); foreground over an accent comes from `Ambient.onColor` (luminance
  threshold 0.45); `AppsFragment.setAccentColor` propagates the colour into the
  grid.
- **Motion:** `anim/layout_rise` (grid) and `anim/layout_slide_in` (lists/sheet)
  are applied in code and cleared after the first run, so the
  `notifyDataSetChanged()` of the launching indicator does not replay them.
  `animator/press_scale.xml` is set as `stateListAnimator` on tappable rows —
  never as a touch listener, which would fight the adapter's click handling.
- **Bottom sheets** must call `expand()` (`skipCollapsed` + `STATE_EXPANDED`) and
  `fitSheet()`, which measures the content *before* `setContentView` and shrinks
  the scroll area by the overflow. Left alone, the sheet opens collapsed and
  hides rows behind a drag nobody discovers.
- **Welcome screen** (`welcomeOverlay` in `activity_main.xml`, driven by
  `showWelcome()`): shown on launch when there is more than one space. Cards go
  two per column in a `GridLayout` (`rowCount=2`, `orientation="vertical"`)
  inside a `HorizontalScrollView` — that combination is what fills column-first.
  Card width must keep two full columns visible with the next peeking, otherwise
  nothing tells the user it scrolls. Always keep a way out (the X plus
  `onBackPressed`).
- **The app grid is adaptive** (`AppsFragment.applyGridFor`): span and tile size
  come from the app count, not just the screen width, so one app renders large
  and centred. `centerSparseGrid` pads the top while the content is short.
  `AppsAdapter.tileIconDp` / `labelSp` carry the sizing into the view holder.
- **Design tokens / theme:** semantic colors (`ds_bg`, `ds_surface`,
  `ds_surface_2`, `ds_on_surface`, `ds_on_surface_muted`, `ds_outline`,
  `ds_violet`, `ds_blue`, `ds_danger`) in `res/values/colors.xml` with the dark
  counterpart in `res/values-night/colors.xml`. Always use the tokens in new
  layouts; the legacy names at the bottom of `colors.xml` only exist for old
  screens. `Theme.BlackBox` is a single `DayNight` theme (`values/themes.xml` +
  `values-night/themes.xml`).
- **Dark is the default.** `view/setting/ThemePrefs` reads the `app_theme`
  preference (`dark` | `light` | `system`) and calls
  `AppCompatDelegate.setDefaultNightMode` from `BaseActivity.onCreate`. Do **not**
  move this to `Application.onCreate`: that also runs inside guest processes and
  would change a cloned app's night mode.
- **Space presentation lives in `view/main/SpaceUi.kt`** (name, colour, app
  count, next free id). Name and colour are per-id preferences in
  `AppManager.mRemarkSharedPreferences` (`Remark<id>` / `Color<id>`).
  - Two spaces may never share a colour. Availability uses perceptual RGB
    distance (`MIN_COLOR_DISTANCE = 45`), not equality, and `migrateLegacyColors`
    remaps pre-redesign colours once (guarded by the `palette_v2` flag).
- **Main screen** = `view/main/MainActivity.kt` + `layout/activity_main.xml`.
  A `ViewPager2` still holds one `AppsFragment` per space plus a trailing
  "new space" page — keep it, because `onPageSelected` is where the engine's
  `stopUser(previousUser)` runs.
  - The space card (`spaceHeader`) shows dot + name + "N aplicativos · Espaço X
    de Y" and opens `showSpacePicker()`.
  - `showSpacePicker()` is a `BottomSheetDialog` (`sheet_spaces.xml` +
    `item_space.xml`), also shown on launch when there is more than one space.
    "Criar novo espaço" is pinned in `spacesFooter`, outside the scroll view.
  - ViewPager2 creates fragments lazily, so an off-screen `AppsFragment` still
    reports `userID == 0`; derive a page's real id from `SpaceUi.sortedUsers()`,
    never from the fragment.
  - Bottom sheets: `applyNavigationBarPadding` + `shrinkScrollToFit`. The
    platform `navigation_bar_height` resource under-reports the real inset on
    this device (70 px vs 138 px), so the value comes from
    `ViewCompat.getRootWindowInsets` on the activity root.
  - Deleting a space (`confirmDeleteSpace`) asks first, then `deleteUser` +
    prefs cleanup + `recreate()` — rebuilding the pager in place leaves stale
    fragments behind.
  - Long-pressing an app icon offers create shortcut / force stop / clear data /
    remove, each with its own confirm label (`action_remove`, `action_clear`,
    `action_stop`).
  - Tapping an app sets `AppsAdapter.launchingPackage`: the tile shows a spinner,
    the others dim, and taps are ignored until the clone opens or a 20 s fallback
    fires.
- **Add app to a space:** FAB → `ListActivity`. Multi-selection with a fixed
  "Adicionar N aplicativos" bar; the result is returned as the `sources` string
  array extra (`source` is kept for compatibility) and installed in one pass by
  `AppsRepository.installApks`.
  - The search field is a plain `EditText` + `TextWatcher`. **Do not go back to
    `SimpleSearchView`**: it echoed composing text into the field and duplicated
    dead-key accents (ã, ç, é). `FakeManagerActivity` shares this layout.
  - `StateView` must sit *above* the `RecyclerView` inside a `FrameLayout`. The
    old vertical `LinearLayout` with two `match_parent` children pushed the list
    off screen.
  - The installable-app list is built in `data/AppsRepository.kt` (filters system
    apps, the host package, unsupported ABIs) and is cached, so newly installed
    host apps only appear after the Dual Space process restarts.
- **Settings** (`res/xml/setting.xml`) is grouped into Aparência / Espaços e
  Google / Privacidade e isolamento / Sobre. "Sobre o MultiSpace" opens
  `view/setting/AboutActivity`. Never add ads, analytics or trackers here.
- **Loading**: `LoadingActivity` shows a themed dialog (`dialog_loading.xml`),
  not the old third-party animation.

## Conventions

- System-service hooks live in `Bcore/.../fake/service/*Proxy.java`, registered
  as `@ProxyMethod("<binderMethodName>")` inner classes.
- Build with JDK 21 (see above). Every code change here has been validated with a
  build+install+on-device screenshot/logcat loop; keep that loop.
- Never commit `/.diagnostics/` or `diagnostics/` — they hold private device
  bugreports/ANR traces (device fingerprint, account references).
