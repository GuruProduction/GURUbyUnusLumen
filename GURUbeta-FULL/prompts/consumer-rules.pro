# Consumer ProGuard rules for the prompts room.
#
# The room is Kotlin and Compose with no reflection of its own, and it keeps no
# serialised models that R8 could strip by name, so there is nothing to pin here.
# The file exists because the module declares it in defaultConfig, and a declared
# consumer rules file that is absent fails the release build outright:
#
#   Supplied consumer proguard configuration does not exist:
#   GURUbeta-FULL/prompts/consumer-rules.pro
#
# That failure only appears on assembleRelease, which is why it reached a release
# build before it reached anyone's attention.
