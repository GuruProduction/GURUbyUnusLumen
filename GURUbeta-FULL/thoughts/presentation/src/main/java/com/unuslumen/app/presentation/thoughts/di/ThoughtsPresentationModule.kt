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