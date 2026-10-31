// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.permissions

// This file previously held duplicate copies of PermissionTier, GrantMechanism and
// DeclaredPermission. They collided with the authoritative declarations in
// PermissionSpec.kt (PermissionTier, GrantMechanism, PermissionSpec), which is the
// single source the registry and orchestrator both use. GrantOutcome lives in
// GrantOutcome.kt. Nothing is declared here, and that is deliberate: two enums with
// the same name in one package is a compile error, not a design choice.
