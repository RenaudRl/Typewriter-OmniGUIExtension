# Typewriter-OmniGUIExtension

> Advanced menu system for Typewriter — 8 layout engines, 23 inventory types, persistent storage, and full MiniMessage support.

[![Paper](https://img.shields.io/badge/Paper-1.21.4-brightgreen)](https://papermc.io)

## Overview

**OmniGUIExtension** is the central menu system for the Typewriter ecosystem. It provides:

- **11 layout engines** — Simple, Scrollable, Flex, Frame, Paginated, Composite, Book, Merchant, Storage, Leaderboard, Vanilla GUIs
- **23 inventory types** — Chest, Anvil, Enchanting, Smithing, Merchant, Book, and more
- **Persistent item storage** — Per-player or group-based with accumulation/progress tracking
- **Scrollable multi-frame dashboards** — Independent scrollable zones with custom navigation buttons
- **Full MiniMessage formatting** — Colors, gradients, hover/click events in titles, names, and lore
- **Storage placeholders** — `{stored_name}`, `{stored_amount}`, `{stored_max}` for dynamic slot display
- **Configurable click actions** — 9 click types (LEFT, RIGHT, SHIFT, DOUBLE, DROP, SWAP_OFFHAND...) per storage action
- **Grid pattern system** — `count`/`direction`/`gap`/`repeatY` for efficient slot positioning

The current release also provides reusable addressable views: declare `views`, use
`@view` frame placeholders, and tag tabs with `view:<id>`. View changes are handled
without closing the inventory and can optionally participate in the history stack.

## Installation

1. Download the latest JAR from [Releases](https://github.com/RenaudRl/Typewriter-OmniGUIExtension/releases)
2. Place it in `plugins/Typewriter/extensions/`
3. Restart your server

### Requirements

| Dependency | Required | Notes |
|:---|:---|:---|
| [Typewriter](https://github.com/gabber235/Typewriter) | ✓ | Engine 1.21.4-26.1.2+ |

## Quick Start

Create an `open_gui` entry in your Typewriter page:

```json
{
  "type": "open_gui",
  "guiType": "CUSTOM",
  "size": "SIZE_27",
  "title": "<gold>My Menu",
  "mainLayoutId": "main",
  "layoutPool": [
    {
      "case": "simple",
      "value": {
        "id": "main",
        "items": [
          {
            "x": 4, "y": 1,
            "item": { "case": "minecraft_item", "value": { "material": "DIAMOND" } },
            "displayName": "<aqua>Click Me",
            "lore": ["<gray>This is a button"],
            "interactionList": [
              { "type": "LEFT", "commands": ["give %player% diamond 1"] }
            ]
          }
        ]
      }
    }
  ]
}
```

## Layouts

| Layout | Description | Best For |
|:---|:---|:---|
| **Simple** | Fixed (x, y) grid with `count`/`direction`/`repeatY` | Static menus, backgrounds |
| **Scrollable** | Viewport with UP/DOWN/LEFT/RIGHT buttons | Long item lists |
| **Frame** | Multiple independent zones with per-zone scroll | Dashboards, sidebars |
| **Paginated** | Multi-page browsing with prev/next | Large collections |
| **Flex** | Automatic row wrapping and alignment | Responsive slot groups |
| **Composite** | Z-order layer stacking | Overlays |
| **Book** | Written book with MiniMessage pages | Lore, guides |
| **Merchant** | Villager trades with custom items | Shops |
| **Storage** | Persistent slots backed by a `gui_storage` artifact | Deposits, machine inputs |
| **Leaderboard** | Fact-backed ranking rows with pagination | Scoreboards, ladders |

## Slot repetition

Any item can place several slots at once instead of being copy-pasted:

```json
{ "x": 0, "y": 0, "count": 9, "direction": "right",
  "item": { "material": "GRAY_STAINED_GLASS_PANE" }, "displayName": " " }
```

| Field | Meaning |
|:---|:---|
| `direction` | `right`, `left`, `down`, `up` — **required**; without it `count`, `gap` and `repeatY` are ignored and a single slot is placed |
| `count` | Number of slots along `direction` |
| `gap` | **Step**, not a spacing: `1` = adjacent (default), `2` = one empty slot between each. Applies to both axes |
| `repeatY` | Repeats the whole line on the axis **perpendicular** to `direction` — downwards for `right`/`left`, to the right for `down`/`up` |

Slots landing outside the grid are dropped and reported in the server console with the
layout id and the rejected coordinates. The editor warns when `count`/`gap`/`repeatY`
are set without a `direction`, and errors on an unknown `direction` value.

## Slot permissions

`viewPermission` hides a slot from anyone without the node; `clickPermission` renders it but makes
it inert. Both are opt-in, and **a blank value is not a gate**: the panel serializes an unset field
as `""`, and Bukkit resolves an unregistered node — `""` included — as operator-only, which would
hide the slot from every ordinary player. Leave them empty to gate nothing. The same rule applies
to a view's `viewPermission`.

## Extended inventory

Set `extendToPlayerInventory` on an `open_gui` entry to turn the player's own 36 slots into four
extra menu rows, giving a 10-row canvas (`y` 0..9) instead of 6.

```json
{ "type": "open_gui", "guiType": "CUSTOM", "extendToPlayerInventory": true }
```

The projection is **client-side only**: the real inventory is never written to, and its contents
are restored the moment the menu closes. Clicks in the bottom band are treated as menu controls —
items can never be picked up there, whatever `allowPickup` says.

Requirements and caveats:

- `guiType` must be `CUSTOM`, with a 6-row (54-slot) size — the projected rows are added on top.
- PacketEvents must be present at runtime (Typewriter already requires it).
- A root layout whose id ends with `_extended` enables this on its own, so a shared shell can ship
  a normal and an extended variant without every menu repeating the flag.

## Storage

```json
{
  "x": 2, "y": 1,
  "item": { "case": "minecraft_item", "value": { "material": "CHEST" } },
  "displayName": "<green>✦ <white>{stored_name}</white> ✦",
  "lore": ["<aqua>Stored: <white>{stored_amount}/{stored_max}</white>"],
  "storage": {
    "entry": "my_storage",
    "maxAmount": 64,
    "forceStorage": true
  }
}
```

### Storage Placeholders

| Placeholder | Description |
|:---|:---|
| `{stored_name}` | Display name of the stored item |
| `{stored_amount}` | Current count (0 if empty) |
| `{stored_max}` | Max capacity (`∞` if unlimited) |

### Click Configuration

| Action | Default Click | Configurable |
|:---|:---|:---|
| Place one | `LEFT` | `gui_settings.placeOneClick` |
| Place all | `SHIFT_LEFT` | `gui_settings.placeAllClick` |
| Take one | `RIGHT` | `gui_settings.takeOneClick` |
| Take all | `LEFT` | `gui_settings.takeAllClick` |
| Take stack | `SHIFT_RIGHT` (cursor empty) | `gui_settings.takeStackClick` |
| Fill from inv | `SWAP_OFFHAND` | `gui_settings.fillFromInvClick` |
| Drop all | `DROP` | `gui_settings.dropAllClick` |

`dropOnClose` is independent from these bindings. When enabled on a storage slot, any content still
stored is dropped at the player's location on Escape, a real close, or disconnect. Menu-to-menu
transitions do not trigger the drop. A `SHIFT_RIGHT` with an item on the cursor deposits the
compatible stack; with an empty cursor it keeps the take-stack behavior.

## Fact leaderboards

Create one `gui_leaderboard` entry and reference it from a `leaderboard` layout in an `open_gui`
`layoutPool`. The entry accepts several `ReadableFactEntry` references and can rank by `PLAYER`,
`WORLD`, or a selected Typewriter `GROUP`. Set the optional `population` reference to a
`gui_leaderboard_population` Artifact to retain the last known values of offline players. The
official engine is not modified: the extension refreshes this snapshot while players are online
and on quit, then merges it with live values when rendering.

The `group` field is a native Typewriter `Ref<GroupEntry>`. The optional `worlds` field is a list of
native `Var<Position>` selectors, so several worlds can be selected through variables; an empty
list means every world. Positions are retained in the snapshot, while `WORLD` aggregation uses
their native `World` value. Useful row tokens are `{rank}`, `{name}`, `{score}`, `{group}`,
`{world}` and `{score_<fact_id>}`. Set `autoRefreshTicks` on the `open_gui` entry when the facts
must refresh while the menu is open.

## Vanilla GUIs

Supports Minecraft's built-in GUI types: `ANVIL`, `ENCHANTING_TABLE`, `SMITHING`, `STONECUTTER`, `GRINDSTONE`, `LOOM`, `CARTOGRAPHY`, `MERCHANT`, `BOOK`.

## Documentation

Full documentation available at [BTC Studio Docs](https://docs.borntocraftstudio.net/extensions/free/gui/).

## Integration

OmniGUIExtension is a **required dependency** for Typewriter extensions that use menus. Extensions reference GUI entries by ID:

```kotlin
// In your extension's entry:
val guiEntry = entryDB.get<OpenGuiActionEntry>("my_menu_id")
MenuSessionService.open(player, guiEntry)
```

## Building from Source

```bash
git clone https://github.com/RenaudRl/Typewriter-OmniGUIExtension.git
cd Typewriter-OmniGUIExtension
./gradlew build
```

Output JAR: `build/libs/Typewriter-OmniGUIExtension-{version}.jar`

---

## 📜 Licence

**GNU General Public License v3.0 or later** — [LICENSE](LICENSE) — with a
**linking exception** for the Typewriter engine — [LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md).

| | |
|---|---|
| You may | Run it anywhere, **including on a monetised server**. Study it, modify it, use it as a base, and redistribute it — **even for a fee**. GPLv3 §4 explicitly allows charging for a copy. |
| You must | Publish the complete corresponding source of your version under GPLv3, preserve the copyright notices, and **state that you modified it and when** (§5(a)). |
| You may not | Ship a closed-source or proprietary version, relicense under stricter terms, or strip the attribution and present this work as your own — §8 terminates your rights automatically. |
| Marks | **"Born To Craft"** and **"BTC Studio"** are **not** covered by the GPL. Fork it freely, sell your fork if you like — but **rebrand it**. |

> Reselling this code is legally allowed and practically pointless: whoever buys a
> copy from you receives, under the GPL, the right to redistribute it for free.
> That is the protection — not a clause forbidding sale, which the GPL does not
> permit us to add.

### About Typewriter

This is a **third-party extension**. It uses the public extension API of the
[Typewriter](https://github.com/gabber235/Typewriter) engine by gabber235 and
contains none of its source. Born To Craft Studio is not affiliated with or
endorsed by the Typewriter project.

The engine itself is **not** free software — its licence forbids redistributing
it. **Get it from the Typewriter project, and never redistribute it**, including
inside a fork of this repository.

Full attribution, the statement of modifications required by §5(a), and the
trademark reservation are in **[NOTICE.md](NOTICE.md)**. Read it before
redistributing.

© 2026 Born To Craft Studio.
