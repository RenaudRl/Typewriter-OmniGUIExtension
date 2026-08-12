# Changelog

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
