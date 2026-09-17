# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep all Koog agent classes
-keep class ai.koog.** { *; }

# Keep TensorFlow Lite classes
-keep class org.tensorflow.lite.** { *; }

# Keep all domain model classes
-keep class com.unuslumen.app.domain.** { *; }

# Keep all memory-related classes
-keep class com.unuslumen.app.data.memory.** { *; }