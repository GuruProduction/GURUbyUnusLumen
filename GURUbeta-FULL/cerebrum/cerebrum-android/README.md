# Cerebrum Android JNI bridge (cdylib target for GURU Android app)

This cross-compiles the full cerebrum brain to a shared library Android
loads inside its own app process.

## Build and publish to GURU's jniLibs
    cd /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/cerebrum
    cp target/aarch64-linux-android/release/libcerebrum_android.so \
       ../app/src/main/jniLibs/arm64-v8a/libcerebrum_android.so

The app's `CerebrumHost` object loads the lib through `System.loadLibrary`
and talks frames only as hex payload strings.