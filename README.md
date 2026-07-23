# Typewriter-OmniGUIExtension

> Advanced menu system for Typewriter — 8 layout engines, 23 inventory types, persistent storage, and full MiniMessage support.

[![Paper](https://img.shields.io/badge/Paper-1.21.4-brightgreen)](https://papermc.io)

## Overview

**OmniGUIExtension** is the central menu system for the Typewriter ecosystem. It provides:

- **8 layout engines** — Simple, Scrollable, Frame, Paginated, Composite, Book, Merchant, Vanilla GUIs
- **23 inventory types** — Chest, Anvil, Enchanting, Smithing, Merchant, Book, and more
- **Persistent item storage** — Per-player or group-based with accumulation/progress tracking
- **Scrollable multi-frame dashboards** — Independent scrollable zones with custom navigation buttons
- **Full MiniMessage formatting** — Colors, gradients, hover/click events in titles, names, and lore
- **Storage placeholders** — `{stored_name}`, `{stored_amount}`, `{stored_max}` for dynamic slot display
- **Configurable click actions** — 9 click types (LEFT, RIGHT, SHIFT, DOUBLE, DROP, SWAP_OFFHAND...) per storage action
- **Grid pattern system** — `count`/`direction`/`gap`/`repeatY` for efficient slot positioning

> **Coming soon**: Web-based WYSIWYG editor with real-time preview and Flexbox auto-layout.

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
| **Composite** | Z-order layer stacking | Overlays |
| **Book** | Written book with MiniMessage pages | Lore, guides |
| **Merchant** | Villager trades with custom items | Shops |

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
| Take all | `SHIFT_RIGHT` | `gui_settings.takeAllClick` |
| Take stack | `SWAP_OFFHAND` | `gui_settings.takeStackClick` |
| Fill from inv | `DOUBLE_CLICK` | `gui_settings.fillFromInvClick` |
| Drop all | `DROP` | `gui_settings.dropAllClick` |

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
