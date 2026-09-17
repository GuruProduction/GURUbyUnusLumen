# GURU Portal Chat Bar Icons — Drop Zone

This folder is for drag-and-drop replacement icons. Drop replacement icon XML files here when you want to swap any of the 6 icons in the chat input bar expanded row.

## The 6 slots (left to right)

1. Attach      — paperclip         → maps to `ic_attach.xml`
2. Otio        — flying cash       → maps to `ic_otio.xml`
3. Laptop      — open laptop       → maps to `ic_laptop.xml`
4. Masks       — masks face        → maps to `ic_masks.xml`
5. Bell        — notification bell  → maps to `ic_bell.xml`
6. Send        — send button       → maps to `ic_send_message.xml`

## How to apply a replacement

1. Drop your XML icon file into this `assets/` folder.
2. Copy it to `GURUbeta-MASTER-clean/core/ui/src/main/res/drawable/` using the matching filename above (overwrite the existing file).
3. Rebuild the APK with `./gradlew assembleDebug`.

## Current icons

All six slots currently use Material Icons from `androidx.compose.material.icons.filled.*`. They are referenced directly from code via `Icons.Default.*`, not via drawable XML files. To swap them for custom drawn icons, update the code in `PortalChatBar.kt` to use `painterResource(id = R.drawable.ic_your_icon)` instead of `imageVector = Icons.Default.IconName`.