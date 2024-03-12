/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.car.customization.tool.di

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.om.OverlayManager
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.LayoutInflater
import android.view.WindowManager
import com.android.car.customization.tool.R
import dagger.Module
import dagger.Provides
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier used to provide a UI valid [Context].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class UIContext

@Module
internal class ServiceModule {

    /**
     * Provides a [Context] configured for UI operations since the standard [Context] provided by
     * an [AccessibilityService] is not.
     */
    @Provides
    @UIContext
    @Singleton
    fun provideUiContext(service: AccessibilityService): Context {
        val displayManager = service.getSystemService(DisplayManager::class.java) as DisplayManager
        val primaryDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val baseContext = service.createWindowContext(
            primaryDisplay,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            /* options= */ null
        )
        return ContextThemeWrapper(baseContext, R.style.BaseTheme)
    }

    @Provides
    fun provideWindowManager(@UIContext context: Context): WindowManager =
        context.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager

    @Provides
    fun provideLayoutInflater(@UIContext context: Context): LayoutInflater =
        context.getSystemService(AccessibilityService.LAYOUT_INFLATER_SERVICE) as LayoutInflater

    @Provides
    fun providePackageManager(@UIContext context: Context): PackageManager =
        context.packageManager

    @Provides
    @Singleton
    fun provideOverlayManager(@UIContext context: Context): OverlayManager =
        context.getSystemService(android.content.om.OverlayManager::class.java) as OverlayManager
}
