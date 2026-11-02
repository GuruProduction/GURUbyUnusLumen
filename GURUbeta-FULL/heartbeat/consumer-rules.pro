# Consumer ProGuard rules for the heartbeat room.
#
# The module declares this file in build.gradle.kts, and Android's build refuses to
# merge a proguard file that does not exist. Ottio and Agents both got an empty one
# when they were created; this one was missed, which is why the release failed here
# rather than in a compile.
#
# Nothing needs keeping. The room holds a view model and a screen, both referenced
# directly from the app rather than reflectively, so R8 can shrink them normally.
