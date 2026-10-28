# GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
# Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
# Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
#
# Consumer ProGuard rules for the onboarding presentation module.
#
# The onboarding screens are Compose only: no reflection, no serialisation and no
# JNI surface, so nothing here needs keeping beyond what Compose and Koin already
# declare for themselves. The ViewModel is resolved by Koin through the generated
# definition, which references the class directly rather than by name.
#
# Deliberately empty of rules rather than absent, because the module declares this
# file in its build script and a missing file fails the consumer-rules merge.
