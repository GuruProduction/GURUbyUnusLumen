// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.thoughts.di

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

/**
 * ThoughtsPresentationModule — component scan for the thoughts screens.
 *
 * Koin's KSP only picks up @KoinViewModel / @Single annotated classes inside
 * the package the @ComponentScan names from the SAME compilation unit. The
 * thoughts screens and their ViewModel live in their own module now, so a
 * scan that mirrors the NotePresentationModule pattern is what makes Koin
 * generate their definitions. Without this, resolving ThoughtsViewModel
 * throws "No definition found" at inflate time on device.
 */
@Module
@ComponentScan("com.unuslumen.app.presentation.thoughts")
class ThoughtsPresentationModule