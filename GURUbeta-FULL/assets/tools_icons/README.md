# GURU Tool Call Icons — Drop Zone

This folder holds per-tool-category PNG icons. The portal canvas loads one icon per tool call inside a grouped tool card, based on the tool's category.

## Convention

One PNG per category. The filename must match the category name exactly.

When you drop a replacement file in here and rebuild the APK, the new icon automatically appears in the next render of the grouped tool cards.

## How It Works

The Kotlin code in `ToolCallFormatter.kt` maps each tool function name to a category string (e.g. `searchNotes` → `Notes`, `webSearch` → `Web`, `spotifyPlay` → `Media`). The portal canvas WebView looks up `file:///android_asset/tools_icons/{category}.png` when rendering a tool call row.

If a PNG for a category exists in this folder, it renders. If no PNG exists, no icon renders for that category's slot.

## How To Apply A Replacement

1. Drop a PNG file into this folder using the exact category name from the list below.
2. Rebuild the APK with `./gradlew assembleDebug`.
3. The PNG gets packaged into the APK's assets directory and is loaded on next render.

## Full Category List

```
Automation    Clipboard     Network       Skills        Shell
Termux        Files         ADB           System        Apps
Screen        Keyboard      Approvals     SSH           Bookmarks
Alarms        Voice         Theme         Settings      Contacts
Communication Jobs          Notes         Diary         Tools
Media         Shortcuts     Util          Email         Security
Memory        SmartHome     Encryption    Plans         Hooks
Calendar      Thoughts      Database      HTTP          Web
Location      Camera        Productivity  Tasks         Notifications
Weather       Places        RSS           URLs          QR
Prompts       Projects      Sound         Portal        Canvas
Misc
```

## Notes

- PNG files only. No SVG, no XML.
- Filenames are case-sensitive. Match the category string exactly (e.g. `Notes.png`, not `notes.png`).
- Recommended icon size: 64x64px or 128x128px. They render at small size inside the card.
- No icon files ship by default. The folder starts empty. Drop your own in to populate.