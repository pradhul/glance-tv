# Launcher review (v35)

Ranked findings from a full pass over `launcher/app` (`com.minimaltv.home`). Severity is about
reliability on the owner's Xiaomi Mi TV and how easily the next change will break something else.

## Critical

### C1. All apps D-pad selection died in v34 (fixed)

**What broke.** In All apps the remote could no longer move between tiles, press OK to open an app,
or long-press / Menu for “Hide from All apps”. Home still worked.

**Why.** v34 moved the 104 dp brand glow to a sibling of the icon, started a scale+fade animation on
that view, and set `android:clipChildren="false"` on the `GridView` (and both parents) so the glow
could paint outside the cell. That combination is poison for `GridView` on Android TV:

- `GridView` owns D-pad through *selection*, not child focus. Unclipped, transformed children change
  hit-rects and let `FocusFinder` leave the grid. Once focus is on a glow/icon, `getSelectedItem()`
  is empty: OK and Menu do nothing.
- There was no `setItemsCanFocus(false)` / `setSelection(0)` after the async adapter bind, so a
  focus/animation race could leave the grid focused with `INVALID_POSITION`.
- `getView` stored the bind position in the untyped `View.setTag(Object)`. AdapterViews have used
  that slot; a collision drops selection.

**Fix (this change).** The glow is designed to sit inside the cell (`paddingTop 22dp` + icon 64 dp,
glow 104 dp → 2 dp top margin). The grid clips again. Each cell `blocksDescendants`. Glow/icon/label
are not focusable or clickable. After bind: `setItemsCanFocus(false)`, `setSelection(0)`,
`requestFocus()`. Bind position uses `R.id.tag_glow_pos` / `R.id.tag_cell_pos`. Visual fade (450 ms
in, 250 ms out, scale 0.5→1) is unchanged.

Covered by `V35DpadTest` (All apps move / OK / Menu / pick-mode, plus home row RIGHT / OK / long-press).

## High

### H1. `MainActivity` is a 2.7k-line god object

One class owns three layouts, dock construction, wallpaper/Bing, weather/forecast, holidays, BT,
Wi-Fi, HDMI auto-switch, ⋯ menu, dialogs, and debug extras. Every vN feature was appended. The
v34 regression is the predictable result: All apps glow was edited without a D-pad test that
actually moved *selection*.

Reflection tests (`DpadNavigationTest.field`) freeze many field names (`morePopup`, `rain`,
`btRow`, …). This review extracted shared pieces (`Looks`, `FocusGlow`, `AppIcons`, `HttpIo`)
without renaming those fields. Further splits (dock vs chrome vs wallpaper) should keep the same
test hooks.

### H2. Unbounded `new Thread()` for every network and decode

| Caller | Thread | Bound? | UI guard |
|---|---|---|---|
| `AllAppsActivity` list + wallpaper | anonymous | no | `isFinishing()` |
| `MainActivity.loadWallpaper` / `swapWallpaperBlur` | anonymous | no | `isFinishing()` |
| `Bing.refresh` / `Holidays.refresh` / `Weather.fetch` / `VpnCountry.lookup` | anonymous | no | mixed |

Two wallpaper loads plus a Bing refresh can run together and both call `runOnUiThread` → flicker or
a stale photo winning the race (`wallpaperBusy` is a boolean, not a generation token). A single
single-thread executor (or `HandlerThread`) plus a generation counter would serialise decode and
make “latest wins” explicit. `UNDER_TEST` already blocks Bing/Nager from Robolectric; keep that.

### H3. Duplicated wallpaper / blur / HTTP (partially extracted)

Before this change:

- All apps reimplemented home’s wallpaper dim (`0x59000000`) and decode path.
- Home floating halo and All apps glow were copy-pasted `GradientDrawable` ovals.
- `Bing.download`, `Weather.get`, `Holidays.get`, `VpnCountry.fetchCode` each opened
  `HttpURLConnection` with their own timeouts and stream loops.

Now: `Looks.WALLPAPER_DIM`, `FocusGlow`, `HttpIo`. Bing still logs HTTP codes and sends a User-Agent
(needed to get the list). Remaining duplication: `Wallpapers.background` vs `loadBing` vs
`MainActivity.loadWallpaper` decision tree (Bing vs weather set vs forced vs bundled). That tree
should become one `WallpaperPicker` with a unit-tested decision table — it is how v31 refresh-vs-
weather bugs happened.

## Medium

### M1. Magic numbers and paired constants

Night window `22`/`6` lived in `nightHours()` and `isNight()`. Glow timings were literals next to
tests that asserted `>= 400`. Dock sizes (`GLASS_SQ = 150`, poster `80`/`280`/`240`, paddings) are
still inline. `Looks` now owns night hours, wallpaper dim, and glow timings. Further numbers should
move there when touched.

### M2. `trackPosition` OnPreDraw listeners are never removed

`MainActivity.trackPosition` adds a `ViewTreeObserver.OnPreDrawListener` per frosted tile and never
removes it. `buildDock()` drops the views (so the observer should die with them) but a leaked
observer on a detached view is a known source of “observer is dead” races. Pair add/remove with
attach state.

### M3. Prefs are a stringly-typed bag

One `"dock"` file holds apps, hidden apps, wallpaper file, Bing ids, weather JSON, holiday caches,
style, night, auto-HDMI, … Keys are scattered (`MainActivity.KEY_*`, `Bing.KEY_*`, `Apps.KEY_HIDDEN`,
`MediaListener.KEY_*`). A typo is a silent empty dock. `Looks.PREFS` is the one file name; a
`Prefs` wrapper with typed getters would be the next step.

### M4. Icon policy is still split by screen

All apps always uses Arcticons when `Glyphs.has(pkg)` (ignores the Simple icons setting). Home
honours `simple_icons`. That is current product behaviour — do not “fix” it in a maintainability
pass — but it should be one `AppIcons` policy object so the next icon tweak cannot drift again.
`AppIcons.bindAllApps` / `picture` is the single call site now.

### M5. `AllAppsActivity` / wallpaper threads and `noHistory`

All apps is `android:noHistory="true"`. A slow `Apps.list` (icons on a 1 GB box) can finish after
the activity is gone; the `isFinishing()` guard is correct. Still no way to cancel the decode. Same
for wallpaper.

## Low

### L1. Tests pin internals

`DpadNavigationTest` reaches into private fields. That is why this refactor left `MainActivity`
field names alone. Prefer package-visible accessors when adding new chrome.

### L2. `V33Test` D-pad step was not a D-pad test

It `setSelection()` then fired one `ACTION_DOWN` and assumed Netflix and “Other” were neighbours.
v34 shipped with no test that OK or Menu used `getSelectedItem()`. `V35DpadTest` does.

### L3. Arcticons headers

`res/drawable/ic_app_arc_*` are CC BY-SA 4.0. Keep the XML headers and README credit. Do not run
formatters that strip the license comment.

### L4. ProGuard

`proguard-rules.pro` is empty. Release minifies. Tests use reflection on debug; the sideload APK
is release. If a future test-only field is read from production (it is not today), it would strip.
Keep public Activity entry points; they are manifest-referenced.

## What this change did not do

- Did not restyle All apps or home. Glow timings, Arcticons, wallpaper dim, three styles, Bing
  button states, weather, holidays, BT colours, Wi-Fi icon, HDMI auto-switch, hidden apps: same.
- Did not split `MainActivity` into multiple Activities or add a DI framework.
- Did not create a GitHub release or install on the TV.
