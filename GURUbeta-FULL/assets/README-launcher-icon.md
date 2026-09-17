# How to change the launcher icon

The source of truth for the launcher icon is `assets/GURUicon.png` in this folder.

Android launcher icons are compiled into the APK at build time. They live in `core/ui/src/main/res/mipmap-{density}/` as webp files. Android reads them before the app starts, so they can't be loaded from assets at runtime like other icons in this folder. They must be physically in the mipmap folders.

## How to update

1. Replace `GURUicon.png` in this folder with your new icon. Must be 1024x1024 square PNG.
2. Run this to regenerate all 10 mipmap webp files:

```bash
cd /tmp && \
cwebp -quiet /Users/unuslumen/GURUbeta-MASTER-clean/assets/GURUicon.png -o fg.webp && \
cwebp -quiet /Users/unuslumen/GURUbeta-MASTER-clean/assets/GURUicon.png -o full.webp && \
RES=/Users/unuslumen/GURUbeta-MASTER-clean/core/ui/src/main/res && \
cp fg.webp $RES/mipmap-mdpi/ic_launcher_foreground.webp && \
cp fg.webp $RES/mipmap-hdpi/ic_launcher_foreground.webp && \
cp fg.webp $RES/mipmap-xhdpi/ic_launcher_foreground.webp && \
cp fg.webp $RES/mipmap-xxhdpi/ic_launcher_foreground.webp && \
cp fg.webp $RES/mipmap-xxxhdpi/ic_launcher_foreground.webp && \
cp full.webp $RES/mipmap-mdpi/ic_launcher.webp && \
cp full.webp $RES/mipmap-hdpi/ic_launcher.webp && \
cp full.webp $RES/mipmap-xhdpi/ic_launcher.webp && \
cp full.webp $RES/mipmap-xxhdpi/ic_launcher.webp && \
cp full.webp $RES/mipmap-xxxhdpi/ic_launcher.webp && \
rm fg.webp full.webp
```

3. Rebuild the app.

## What gets generated

10 webp files across 5 density buckets:

- `ic_launcher_foreground.webp` - the adaptive icon foreground (shows on cold start splash and launcher icon on Android 8+)
- `ic_launcher.webp` - the legacy full launcher icon (home screen icon on older Android)

All copied to:
- `core/ui/src/main/res/mipmap-mdpi/`
- `core/ui/src/main/res/mipmap-hdpi/`
- `core/ui/src/main/res/mipmap-xhdpi/`
- `core/ui/src/main/res/mipmap-xxhdpi/`
- `core/ui/src/main/res/mipmap-xxxhdpi/`

## Requirements

- `cwebp` must be installed (`brew install webp`)
- Icon must be a square PNG, 1024x1024 recommended
- No resize or crop happens during conversion, the exact image is used