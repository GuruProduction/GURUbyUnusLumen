// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.ottio

import org.koin.dsl.module

/**
 * Ottio's own bindings.
 *
 * THE ROOM OWNS ITS VIEW MODEL. This module was carved out of portal:presentation,
 * where the market shared a package with the agents room and a Koin module with
 * everything else portal injects. A room that lives inside another room's module
 * cannot be moved, tested or reasoned about on its own, and the marketplace is a
 * place in its own right rather than a sub-screen of the agents list.
 *
 * Only the view model is declared here. The repository and the catalogue client it
 * needs come from portal:domain and portal:data, because those are the app's shared
 * stores rather than this room's private property; a shop does not get its own copy
 * of the stock room.
 */
val ottioModule = module {
    // Koin's KSP pass registers @KoinViewModel classes in whatever module includes
    // this one; declaring it here keeps the room's wiring beside the room.
}
