# Changelog

## 0.14 — 2026-08-29

### Fact leaderboards

- Added `gui_leaderboard`, a reusable definition ranking players, worlds or Typewriter groups on
  one or more `ReadableFactEntry` values, and the `leaderboard` layout that renders it into any
  `open_gui` pool. Rows accept `{rank}`, `{name}`, `{score}`, `{group}`, `{world}` and
  `{score_<fact_id>}`; pagination is driven by the reserved rectangle and two optional buttons.
- Added `gui_leaderboard_population`, an artifact holding the last known values of players who
  are offline. The official engine deliberately exposes no offline fact snapshot, so the
  extension refreshes this one while players are online and once more on quit, then merges it
  with live values when rendering. Without it a leaderboard only ever ranks who is connected.

### Storage

- Added `dropOnClose` on storage slots: remaining contents are dropped at the player's feet on a
  real close, on Escape and on disconnect. Independent from `temporary` and from the click
  bindings.
- Fixed temporary storage slots never being cleared. `collectStorageSlots` returned an empty list
  for `StorageLayout` — the very layout that owns those slots — so `temporary` was inert and
  `temporaryTriggers` never fired for a slot declared through a storage layout.
- Fixed a deposit being swallowed by the take binding. `SHIFT_RIGHT` was resolved as take-stack
  before place-all, so shift-right-clicking with a matching item on the cursor took instead of
  depositing. With an item on the cursor it now deposits; with an empty cursor it keeps the
  take-stack behavior.

### Fixes

- A blank permission is no longer a permission. The web editor serializes an unset
  `viewPermission`/`clickPermission` as `""`, which Bukkit resolves through
  `Permission.DEFAULT_PERMISSION` — that is, OP: the slot silently disappeared for every
  non-operator while operators kept seeing it. The same fix covers `MenuViewData.viewPermission`,
  where it hid a whole tab. Blank now means "no gate", as the editor validation already assumed.
- Startup validation knows the `leaderboard` layout. It was absent from `KNOWN_CASES`, so a valid
  menu was reported as an error while the runtime drew it correctly. It now also reports a rank
  area falling outside the inventory and a layout referencing no `gui_leaderboard`.

## 0.12 — 2026-08-12

- Added `extendToPlayerInventory` on `open_gui`: the player's own 36 slots become four extra
  menu rows, giving a 10-row canvas. The projection is client-side only — the real inventory
  is never written to and is restored when the menu closes. Clicks in the bottom band are
  menu controls only.
- A root layout whose id ends with `_extended` now enables that projection on its own, so a
  shared shell can ship a normal and an extended variant without every menu repeating the flag.
- Added `DynamicMarkers`, the indexing pass that turns repeated content markers into indexed
  ones (`QUEST_SLOT#0`, `QUEST_SLOT#1`, …) before the layout is parsed, so extensions stop
  each keeping a private copy of those semantics.
- Slot repetition is now bounded and reported: positions falling outside the grid are dropped
  **and logged** with the layout id and the rejected coordinates. The editor warns when
  `count`/`gap`/`repeatY` are set without a `direction`, and errors on an unknown `direction`.
- Documented that `gap` is a **step** (1 = adjacent) and that `repeatY` repeats on the axis
  **perpendicular** to `direction`.
- Fixed inheritance of `mainLayoutId`: a blank value on the child used to beat the inherited
  one, so a page inheriting its whole chassis resolved a non-existent layout and rendered
  empty — with no button left to receive a click.

### Startup validation — now reports only what actually fails

The startup pass used to flag the *absence* of optional things, which in a template-based
architecture describes the design rather than a defect. It now reports the failure of a written
intent, and nothing else.

- Removed `frame.view.unresolved` / `frame.view.unresolvedAll`: views come from the shared shell
  while the layouts filling them live in child pages, so "no view fills this frame" is normal.
  Replaced by `view.frame.layoutMissing` — a view that *names* a layout the pool does not have.
- Removed `slot.outOfFrame`: a frame is a viewport, not a bound. `FrameLayout` offsets without
  cropping, and extensions declare marker grids larger than the frame to page or scroll through
  them. A harmful overflow is still caught by the overlap check.
- Removed `layout.unreferenced`: a template keeps variants in reserve for its children, and
  extensions consume layouts from their own code. Broken references are still caught by
  `menu.mainLayout.unresolved` and the frame `layoutId` check.
- `slot.buttonType.unknown` is only raised when the type's *namespace* is known: declaring button
  types is optional, so an undeclared prefix now means "cannot judge", not "unknown".
- The pass mirrors the runtime it validates: inherited views are merged, the `<root>_extended`
  promotion is replayed, and an extended menu is measured against ten rows.
- Overlap messages now give absolute coordinates — two occupants of one cell usually live in
  different frames, where local `(0,0)` means different places.
- Warnings are spelled out when a menu has three or fewer, summarized to codes beyond that.
- Slot collisions ignore `flex` layouts, whose positions are computed rather than authored.
