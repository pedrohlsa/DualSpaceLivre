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

## Repository & remotes

The working repository is the owner's private fork,
`github.com/pedrohlsa/DualSpaceLivre`, branch `main`. A fresh clone of it needs
nothing special:

```bash
git clone https://github.com/pedrohlsa/DualSpaceLivre.git
```

**On the original Windows machine the remotes are not the usual ones.** There,
`origin` still points at the upstream project (`ALEX5402/NewBlackbox`) and the
owner's fork is a second remote named `mine`, so work is pushed with
`git push mine HEAD:main`. **Never push to `origin`** — it is someone else's
project. Check with `git remote -v` before pushing; on a clean clone the fork is
plain `origin` and `git push` is correct.

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

## Logout, caught in the act (2026-08-11) — task removal, not duplication

A persistent on-device recorder (`logcat -f /sdcard/ds_watch.log -r 16384 -n 20`,
320 MB of rotation — 2 MB rotated in *one minute* and lost the first capture)
finally caught a logout with per-space process sampling running alongside it:

```
07:04:41  esp:[ user/1 user/11 ]  DUP:[]      two guests, different spaces
07:04:55  Killing com.dualspace.livre (adj 250): remove task     ← launcher, IN USE
07:04:55  Killing :p1 (adj 700):  remove task
07:04:56  Killing :p0 (adj 1001): remove task
07:04:57  esp:[ ]                 DUP:[]      everything gone
07:05:00  init bUid = 110001, bPid = 0        app relaunched from scratch
07:05:08  1675002 "Unauthorized logged out query"
```

**46/46 samples had zero duplicates, including at the moment of the logout**, so
the duplicate-process bug — real, and fixed — is *not* what drops the accounts.
The trigger is the owner swiping the app's cards out of Recents, which is
`remove task`: Android tears down the launcher at `adj 250` (actively in use)
along with `:black` and every guest.

**The session is not lost locally.** Checked immediately afterwards: 185 prefs
files in `blackbox/data/user/1/com.instagram.android/shared_prefs`, **zero
`.xml.bak`** (so no interrupted write), and `AuthHeaderPrefs.xml` rewritten at
07:05 — that is Instagram clearing its own token *after* the server refused it.
The data survived; the server invalidated the session.

**Best remaining explanation, not proven:** Instagram rotates its auth token
during use and must persist the new one. A `SIGKILL` gives it no chance, so the
app comes back holding the previous token and the server treats the session as
revoked. It fits every constraint the owner established — only the clone (which
is SIGKILLed constantly), never the physical app, unaffected by fresh logins,
no local corruption, server-side error code.

**Zero-code experiment to settle it:** finish with the Home button and *do not*
swipe the cards out of Recents. If the logouts stop, it is confirmed. The owner
said this from the very beginning — "acontece toda vez que eu fecho todas as
abas do app" — and it took far too long to take literally.

## Logout: two guest processes on one data directory (2026-08-09)

**Observed on device, and the best explanation so far.** While a single space
was open, `ps` showed *two* live processes named `com.instagram.android` under
the virtualized uid `u11_a304`, and reading `/proc/<pid>/maps` for both put them
in **the same space**:

```
23468 com.instagram.android → blackbox/data/user/7
32149 com.instagram.android → blackbox/data/user/7
```

Two Instagram instances over one `blackbox/data/user/<id>` both run their session
manager against the same auth files, and each token refresh invalidates the
session the other is holding — the account drops "toda hora". **The physical
Instagram never does this because it only ever runs as one process**, which is
exactly why the owner sees logouts only inside a space. That observation also
rules out the network: the physical app and the clones egress through the same
WARP tunnel (see below), so the IP cannot be what separates them.

Cause in `BProcessManagerService.startProcessLocked`: a record is reused only
when `app.bActivityThread != null`. If the guest never registered — it died, or
the record went stale — the code fell through to `getUsingBPidL()`, which
deliberately returns a slot *nobody is using*, and then `bProcess.put(processName,
app)` overwrote the old record. The old OS process was never killed, just
forgotten. `restartAppProcess()` reaches the same `put` with a concrete `bpid`
and skips the reuse check altogether.

Two changes, both in `BProcessManagerService`:
- `retireStaleProcessLocked` kills and forgets a dead record before a new slot is
  taken (re-resolving the pid from `ProxyManifest.getProcessName(bpid)`, since the
  recorded one can be stale).
- `retireDuplicatesLocked`, called after every `bProcess.put`, sweeps
  `mPidsSelfLocked` — which still remembers overwritten records — and kills any
  other live process with the same `buid` + `processName` in a different slot.
  The invariant to preserve: **one live process per (space, process name)**.
  Multi-process guests are unaffected, their `processName` differs.

**REPRODUCED 2026-08-09, and the real cause is a server restart.** Reopening the
launcher took the guest count from two to four — *two processes per space*:

```
antes:        [15487=user/0] [23329=user/1]
apos reabrir: [15487=user/0] [23329=user/1] [27738=user/1] [27819=user/0]
```

`mProcessMap` and `mPidsSelfLocked` live only in memory, inside `:black`. Guests
run in their own `:pN` processes, so when `:black` is restarted — memory pressure
on a 3.7 GB phone does it routinely — **the bookkeeping is wiped while every
guest keeps running**. The server then believes nothing is started,
`getUsingBPidL()` truthfully reports those slots as free, and the next launch
puts a second process on the same `blackbox/data/user/<id>`.

This is also why `retireDuplicatesLocked` never fires: it walks
`mPidsSelfLocked`, which was wiped along with everything else, so the orphans are
invisible to it. The running-process list is not. `systemReady()` now calls
`killOrphanedGuestProcesses()`, which kills every live `:pN` at server start — at
that moment the server holds no records, so any such process is by definition
unmanaged and can only corrupt the space it still has open.

Three separate defects fed this, all now fixed in `BProcessManagerService`:
1. **Orphans across a server restart** — `killOrphanedGuestProcesses()` in
   `systemReady()`. This is the big one.
2. **`retireDuplicatesLocked` compared the wrong field.** The local `buid` is
   `BUserHandle.getUid(userId, appId)` (510001 for space 5) while
   `ProcessRecord.buid` only ever stores the bare app id (10001), so the guard
   `record.buid != buid` was always true and the sweep silently did nothing. It
   now matches on `(userId, appId)`.
3. **`killAllByUserId` looked the map up with that same wrong key**, so stopping
   a space killed the process but left its record in `mProcessMap`. The zombie
   record then pushed the next launch into allocating a fresh slot.

Two more defects surfaced while verifying, both fixed:

4. **`app.initLock.block()` had no timeout.** A guest that died before
   registering parked the server thread forever, which Android reported as
   `Killing com.dualspace.livre:black (adj 905): bg anr`. Losing `:black` is what
   strands the guests in the first place, so the unbounded wait was feeding the
   very bug above. Now bounded by `PROCESS_INIT_TIMEOUT_MS` (10 s).
5. **The startup sweep closed the app the user was in.** `:black` restarts while
   a guest is on screen; killing it there sent the user back to the launcher.
   `killOrphanedGuestProcesses` now skips anything at
   `IMPORTANCE_VISIBLE` or better — but that alone let the duplicate live for
   minutes, so it is not sufficient on its own (see below).

**The fix that actually closed it: an on-disk owner tag.** `createProc` already
wrote a `cmdline` file per slot, and `systemReady()` deleted the whole proc dir —
discarding the only record of who owns a still-running slot at exactly the moment
it mattered. It now writes an `owner` file (`userId:processName`), `systemReady()`
only prunes entries whose process is gone, and `retireStrandedSlotsForGuest`
kills a surviving slot for that guest just before a new one is allocated. That is
the one moment when killing is unambiguously correct — the user is reopening the
guest, so it was about to be replaced anyway. No foreground app is closed, and no
duplicate survives.

**Verified on device 2026-08-10**, four monitoring windows sampling each guest's
space every 15 s:

| window | duplicate samples | 1675002 | `:black` bg anr |
|---|---|---|---|
| 1 | 2 / 46 | 53 | 1 |
| 3 | 7 / 46 | 15 | 0 |
| 4 (all fixes) | **0 / 46** | 8, all one pid in a 63 s burst | 0 |

`killing stranded guest com.instagram.android (user 5) still holding bPid 0`
fired once in window 4 and the duplicate never appeared again. The remaining
logouts are one session discovering it was already dead; sessions invalidated
before these fixes cannot be recovered, so **the honest test is a fresh login
followed by normal use**. `:black` still restarts (4× in window 4) — that is
memory pressure, 662–1214 MB free on a 3.7 GB phone, and it is why the on-disk
tag matters more than any in-memory sweep.

**Likely trigger, worth reducing:** the Moto G50 has 3.7 GB and runs two cloned
Instagrams plus the physical one. Memory pressure kills a proxy process, the
record goes stale, and the next launch allocates a fresh slot — which is exactly
the path above. The extra Recents entries below make that worse.

## Extra Recents entries ("12 mil abas") — FIXED 2026-08-10

`startActivityInNewTaskLocked` added `FLAG_ACTIVITY_MULTIPLE_TASK`, which means
*never reuse a task, always mint a new one*. Every launch left another Recents
card behind, all rooted at the same `ProxyActivity`, each retaining its own
activity state — four cards for one slot was the measured norm.

`FLAG_ACTIVITY_NEW_DOCUMENT` on its own already does the right thing: Android
looks for an existing task whose base intent matches by **component and data**
and reuses it. The proxy component alone is not specific enough, since guests
share slots over time, so the shadow intent now carries the task's real identity
as data:

```java
shadow.setData(Uri.parse("dualspace://space/" + userId + "/" + activityInfo.packageName));
shadow.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
shadow.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);   // MULTIPLE_TASK deliberately gone
```

Verified on device both ways: opening the same guest four times in a row kept the
count at one card, and opening a second space produced its own, with the tasks
tagged `dualspace://space/0/com.instagram.android` and
`dualspace://space/1/com.instagram.android`. **Do not re-add `MULTIPLE_TASK`** —
without the data uri it is the only thing keeping guests apart, but with it the
flag only duplicates cards.

## Older note on the same symptom (superseded)

The owner reports the clone scattering itself across many Recents cards, "one on
the conversation, another on reels". `startActivityInNewTaskLocked` sets

```java
shadow.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
shadow.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
shadow.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
```

`MULTIPLE_TASK | NEW_DOCUMENT` means *never reuse a task, always mint a new one*,
so every trip through that branch is another card — where real Android, given
`NEW_TASK` alone, would return to the existing task with a matching affinity.
The branch is reached whenever `findTaskRecordByTaskAffinityLocked` misses, and
Instagram declares **15 per-activity `taskAffinity` values** — Direct and Clips
among them, matching "conversation" and "reels" precisely. `ComponentUtils
.getTaskAffinity` lets a per-activity affinity win unconditionally, while Android
consults `taskAffinity` only when `FLAG_ACTIVITY_NEW_TASK` is present.

**Left alone deliberately.** A `getAppTasks()` rewrite of `synchronizeTasks()`
was written and reverted the same day after the owner reported a breakage right
after install, and the symptom has never been reproduced here (clean runs show 2
tasks). Reproduce the extra card first, then fix the flags — not the reverse.

## Network: WARP is active, but it is NOT the logout cause (2026-08-09)

**A live capture caught the logout happening and ruled out a local teardown.**
The "Sua conta foi desconectada" dialog appeared *after* the feed had already
rendered, and the surrounding logcat shows **no process kill, no `remove task`,
no crash, and no ANR** for `com.instagram.android` or `com.dualspace.livre` —
only one unrelated `android.process.acore` kill. `ActivityStack` created exactly
one activity. So the session was terminated server-side. The old SIGKILL/prefs
theory stays refuted, and the task-removal fix, while still correct, is not the
logout cause. What *does* explain a server-side drop with no local crash is the
duplicate-process bug above: the second instance's token refresh invalidates the
first one's session.

**The same capture found `com.cloudflare.onedotonedotonedotone` (Cloudflare WARP,
1.1.1.1) installed and CONNECTED on user 11. It was wrongly blamed for the
logouts — do not repeat that.** The owner pointed out the decisive control: the
*physical* Instagram never logs out, and it egresses through the very same
tunnel (uid `1110334`, inside the VPN's uid ranges, verified with
`ip route get 157.240.1.1 uid …` resolving to `tun0`). Same IP, same ASN,
different behaviour — so the IP is not the differentiator. Keep the facts below
for the upload/MTU question only.

```
NetworkAgentInfo{network{103} ni{VPN CONNECTED} lp{InterfaceName: tun0 ...}
  OwnerUid: 1110333   Uids: <{1100000-1110182, 1110184-1110205,
                             1110207-1110216, 1110218-1199999}>
```

The host uid is `1110304` (`u11_a304`), which falls inside `1110218-1199999`, so
**every space's traffic egresses through WARP**. This matters more than any
device identifier:

- All seven spaces leave through the **same WARP exit IP**, so the VPN provides
  zero de-linking benefit — it links the accounts exactly as a shared
  residential IP would.
- WARP exit addresses are **Cloudflare datacenter ranges**, which Instagram
  treats as proxy/VPN traffic. A shared *datacenter* IP is a stronger negative
  signal than the user's ordinary residential IP.
- WARP re-negotiates and can change egress address on network transitions, so a
  live session appears to hop IPs mid-flight — a classic session-invalidation
  trigger.

**This is configuration, not code: the engine cannot fix it.** Decide with the
owner whether WARP stays on. If per-space IPs are actually wanted, that needs a
real per-space proxy (the desktop manager's remit), not a single device-wide VPN.
Change one thing at a time: turn WARP off, log in once, and observe before
touching anything else.

**Unexplained, logged for later:** `CompanionDeviceManagerService:
onPackageModified(packageName = com.dualspace.livre)` fires ~28 times per minute
while a space is open, each one triggering a system-wide `DefaultDialerCache`
refresh for both users. The guest's own `setComponentEnabledSetting` is already
stubbed to a no-op in `IPackageManagerProxy`, so the churn comes from somewhere
else. No evidence it causes the logout; it is pure waste and worth tracking down.

## Next steps (2026-08-05)

The identifier work is in, but **nothing is confirmed to have fixed the
logouts yet**. Do these in order, and do not stack changes — the point is to
know which one worked.

1. **Log into each account once more and use it normally for a few days.**
   Instagram sees the device identity change once (new ANDROID_ID, GSF id and
   serial), then it should be stable. An already-rejected token cannot be
   recovered by the engine.
2. **If it still logs out**, capture logcat *while it happens* — the buffer
   rolls over fast. Look for the GraphQL error `1675002` /
   `Unauthorized logged out query`, and for any `Killing ... : remove task`.
3. **One suspect remains, still shared across spaces:**
   - **`Build.*` + screen metrics** (Instagram's User-Agent) — cheap to fake,
     but a model that disagrees with the GL renderer, ABI or sensor list is
     itself a signal. Treat as a last resort.
   - **MediaDrm/Widevine device id — now virtualized (2026-08-06), unverified
     on device.** See the section below; it did *not* need a new hooking
     library after all.

**GSF id hook is inert by architecture (proven on device 2026-08-06).** The
`ContentProviderStub#getVirtualGsfId` hook never fires, and it never can for
Instagram. A live trace with diagnostics in `getContentProvider`/
`ContentProviderDelegate.update` showed the guest (`com.instagram.android`,
host uid `u11_a304`) acquiring the gservices provider **zero** times while it
read `Settings.Secure.ANDROID_ID` ten times in the same window. The reason is
structural: only three processes run under the virtualized uid `u11_a304` — the
launcher, the `:black` engine process, and the guest — there is **no
virtualized Google Play Services / gservices inside the sandbox**. Every
`com.google.android.gms`, `com.google.process.gservices` and
`com.google.process.gapps` runs under the real Google uid `u11_a165`, outside
BlackBox. The guest talks to the **real, out-of-sandbox GmsCore over binder**;
any GSF read happens there, where the provider hook has no visibility.
Compounding it, a guest without the `READ_GSERVICES` permission (Instagram has
none) cannot query `content://com.google.android.gsf.gservices` at all — which
is exactly why the count is zero. **Conclusion: the GSF id is not a linkage
vector Instagram can even reach through this path; do not spend more time trying
to make the provider hook fire.** The hook is left in place (harmless, and
correct for the theoretical case of a guest that *does* hold READ_GSERVICES and
queries directly), but it is not part of the effective identifier set. The
ANDROID_ID hook, by contrast, is the real lever and was seen serving the guest
dozens of times. Intercepting a GmsCore-mediated GSF id would require hooking
the GmsCore binder (the same mechanism `VirtualAdvertisingIdService` already
uses for the ad id), not the content provider — only worth doing if evidence
ever shows IG using the GSF id to link accounts.

**Also unresolved:** the physical Instagram (`u11_a334`) still starts alongside
the clone and eats 300-350 MB. Not a logout cause, but wasteful; do not "fix" it
by disabling or uninstalling the source package, which the engine still depends
on.

## Current handoff / unresolved (2026-08-04)

- **Identifier audit (2026-08-05).** Per space and persisted in
  `.dual-space-identity`: Advertising ID, App Set ID, ANDROID_ID, GSF id and
  Build serial. Hook points: ANDROID_ID in `SystemProviderStub` (settings
  provider `call()`), GSF id in `ContentProviderStub` (query on
  `com.google.android.gsf.gservices`), serial in
  `IDeviceIdentifiersPolicyProxy#getSerialForPackage` (used to return a constant
  `md5(hostPkg)`, identical in every space).
  **MediaDrm/Widevine device id — virtualized on 2026-08-06, not yet verified
  on device.** The earlier audit here said this was unreachable without adding
  an inline-hook library. That was wrong: the engine already ships a native JNI
  hook (`Bcore/src/main/cpp/JniHook/JniHook.cpp`, `HookJniFun`) used by
  `BinderHook` and `VMClassLoaderHook`, and `MediaDrm.getPropertyByteArray` is a
  `native` method, so it is a valid target for exactly that mechanism — no new
  dependency. New `Hook/MediaDrmHook.cpp` intercepts `getPropertyByteArray` and,
  only for `deviceUniqueId`/`provisioningUniqueId`, replaces the bytes with a
  per-space value (`NativeCore.getWidevineDeviceId` → `BoxCore` bridge →
  `VirtualIdentityManager.getWidevineDeviceId`, seed `widevine_seed` in
  `.dual-space-identity`, SHA-256 counter derivation preserving the original
  length). All other MediaDrm properties (securityLevel, hdcpLevel, vendor,
  provisioning) pass through untouched, so DRM playback is unaffected. Reset is
  covered by the existing `resetVirtualIdentity`. Validate on device with the
  `appsettest` module (shows the value) or `adb logcat -s NativeCore` while a
  clone reads it: expect distinct, stable values in space 1 vs space 2.
  **Note on `ClassInvocationStub`:** only interfaces are hookable that way
  (`injectHook()` bails when `getWho()` is null) — the native JNI hook is the
  only path for concrete-class `native` methods like this one.
  **Deliberately left alone:** `Build.*` and the screen metrics, which make up
  Instagram's User-Agent. Faking them per space would make each space look like
  a different phone, but any mismatch with other observable signals (GL
  renderer, ABI, sensors) is itself a flag. Revisit only after confirming the
  identifier work was not enough.
  **Low value on Android 12:** IMEI/IMSI/SIM serial (need privileged
  permissions), MAC (`02:00:00:00:00:00` since Android 6).
- **16 registered hooks were inert and have been deleted (2026-08-05):**
  `ApkAssetsProxy`, `AudioRecordProxy`, `AuthenticationProxy`, `DeviceIdProxy`,
  `FeatureFlagUtilsProxy`, `FileSystemProxy`, `GoogleAccountManagerProxy`,
  `ISettingsProviderProxy`, `LevelDbProxy`, `MediaRecorderClassProxy`,
  `MediaRecorderProxy`, `ReLinkerProxy`, `ResourcesManagerProxy`,
  `SQLiteDatabaseProxy`, `SystemLibraryProxy`, `WorkManagerProxy`. Each was
  registered in `HookManager` but had `getWho()` returning null *and* an empty
  `inject()`, so it hooked nothing — the same trap `AndroidIdProxy` set.
  **When auditing this fork, never trust a proxy class by its name:** check that
  `getWho()` returns a real binder and that the hook actually logs.
- **ANDROID_ID was never virtualized (found and fixed 2026-08-05).**
  `AndroidIdProxy` was registered in `HookManager` and looked like it handled
  this, but the class was an empty stub: `getWho()` returned null and `inject()`
  did nothing, so none of its `@ProxyMethod` handlers ever ran (confirmed — it
  never logged once). Its fallback also minted a fresh random id per call, so had
  it worked it would have been worse. Every space therefore reported the host's
  single ANDROID_ID, letting Instagram tie all the cloned accounts to one device.
  Real implementation now lives in
  `fake/service/context/providers/SystemProviderStub#getVirtualAndroidId`, which
  answers the settings provider `call()` with a value from
  `VirtualIdentityManager.getAndroidId(userId)` — generated once per space and
  persisted next to the advertising id. The `call()` signature moves between API
  levels, so the selector (`GET_*`) and the setting name are matched by value,
  not position. `AndroidIdProxy` was deleted. Verified on device: the guest is
  served the space value (`SystemProviderStub: ANDROID_ID served for space 0`)
  and it survives a restart.
  **Expect one more login per account after this lands** — Instagram sees the
  device identity change once, then it is stable.
- **Correction to the 2026-08-04 note below:** the "SIGKILL loses the pending
  SharedPreferences write" explanation was *not* confirmed. Inspecting the guest
  data with a debuggable build found zero `.xml.bak` files across all seven
  spaces and intact, recently-written prefs, so the session was not being lost
  locally. The logout is server-side (`1675002`), which points at device
  identity rather than at data loss. The task-removal fix below is still correct
  and worth keeping (it stopped the engine tearing itself down), it just was not
  the logout cause.
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

## Clipboard inside a space (added 2026-08-09)

Copy/paste silently did nothing inside cloned apps because **there was no
clipboard hook at all** — `black.android.content.IClipboard` existed as a bare
reflection stub and nothing was registered in `HookManager`. Every
`IClipboard` method carries the caller's package name, and since Android 10 the
service also requires the caller to hold input focus; a guest passes
`com.instagram.android` while running under the host uid, so
`AppOpsManager.checkPackage` rejects it.

`fake/service/IClipboardProxy.java` now rewrites, by argument type rather than
by index (signatures move between API levels):

- first `String` → the host package, which is genuinely the focused window,
  because the guest draws inside a `ProxyActivity`;
- any later `String` → `null`, since that is `attributionTag` on API 30+ and the
  host never declared the guest's tag;
- trailing `int` → `BlackBoxCore.getHostUserId()`, as in every other hook here.

Covers `getPrimaryClip`, `setPrimaryClip`, `clearPrimaryClip`, `hasPrimaryClip`,
`getPrimaryClipDescription`, `hasClipboardText`, `getPrimaryClipSource`,
`add/removePrimaryClipChangedListener`. `setPrimaryClipAsPackage` is left alone —
it needs a privileged permission. Verified only that the guest still boots and
Instagram renders with the hook installed; **the paste path itself is not yet
confirmed on device.**

## Posting killed the guest: notification URI grant (fixed 2026-08-09)

**Symptom:** tapping share closed the clone and returned to the launcher. The
crash was caught in full:

```
FATAL EXCEPTION: IgExecutorV2 #26   Process: com.instagram.android
java.lang.SecurityException: UID 1110304 does not have permission to
  content://com.instagram.fileprovider/cache/images/notification_thumbnail….png
  at INotificationManagerProxy$EnqueueNotificationWithTag.hook
```

Uploading builds a progress notification whose preview is a **guest**
FileProvider URI. The host is the process that reaches the framework, and it
holds no grant for that URI, so the framework refuses. `EnqueueNotificationWithTag`
called `BNotificationManager.enqueueNotificationWithTag` with **no try/catch**,
and Instagram posts from a background executor with no handler, so the
`SecurityException` killed the whole guest process.

`enqueueNotificationWithTag` now tries the notification as sent, and on any
failure retries once with `stripInaccessibleMedia` (clears `sound`, the
`EXTRA_LARGE_ICON`/`EXTRA_LARGE_ICON_BIG`/`EXTRA_PICTURE` extras and the
`mLargeIcon`/`largeIcon` fields), then gives up silently. `cancelNotificationWithTag`
got the same guard. **A notification must never be able to take the process
down** — that is the rule to keep here; the proper fix would be granting the host
a read permission on the guest URI, which nobody has implemented yet.

## Task bookkeeping: investigated, left alone (2026-08-09)

`ActivityStack.synchronizeTasks()` rebuilds `mTasks` from
`ActivityManager.getRecentTasks(100, 0)`, deprecated since API 21 and documented
to give a third-party caller only "a small subset" of the list. Any task missing
from that subset is purged, and since `findActivityRecordByToken` walks `mTasks`,
losing a task also loses the *source activity* of the next launch;
`startActivityLocked` then falls through to `startActivityInNewTaskLocked`, which
adds `FLAG_ACTIVITY_NEW_DOCUMENT | FLAG_ACTIVITY_MULTIPLE_TASK` and opens the
guest in a brand new Recents entry — a plausible mechanism for "abre uma aba
nova".

**A switch to `getAppTasks()` was written and then reverted the same day.** The
symptom was never reproduced (a clean run showed 2 tasks and 1 activity), the
owner reported a breakage right afterwards, and changing task bookkeeping on a
theory is exactly how this engine broke before. `synchronizeTasks()` is
unchanged. If it is attempted again, reproduce the extra Recents entry *first*.

Related and still unfixed: Instagram declares 15 per-activity `taskAffinity`
values (Direct, RTC call, Clips PiP, the share handlers), and
`ComponentUtils.getTaskAffinity` lets a per-activity affinity win
unconditionally — whereas Android consults `taskAffinity` only when
`FLAG_ACTIVITY_NEW_TASK` is present. Leading hypothesis, not addressed.

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
