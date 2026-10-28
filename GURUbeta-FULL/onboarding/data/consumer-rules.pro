# GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
# Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
# Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
#
# Consumer ProGuard rules for the onboarding data module.
#
# The profile row is a plain Room entity reached through a DAO; Room generates its
# own keep rules and the repository is bound in Koin by direct reference. There is
# no reflection or serialisation contract to protect here, so no custom rules are
# needed.
#
# Deliberately empty of rules rather than absent, because the module declares this
# file in its build script and a missing file fails the consumer-rules merge.
