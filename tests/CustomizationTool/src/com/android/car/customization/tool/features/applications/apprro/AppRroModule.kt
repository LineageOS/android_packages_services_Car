/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.car.customization.tool.features.applications.apprro

import android.content.om.OverlayManager
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import com.android.car.customization.tool.R
import com.android.car.customization.tool.di.PanelReducerKey
import com.android.car.customization.tool.domain.menu.MenuItem
import com.android.car.customization.tool.domain.panel.OpenPanelAction
import com.android.car.customization.tool.domain.panel.PanelActionReducer
import com.android.car.customization.tool.features.applications.submenu.ApplicationsMenu
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet

/**
 * Unbundled Apps RRO List.
 *
 * Adds one [MenuItem.PanelLauncher] for each unbundled app (Dialer, Media, Messenger) to
 * the menu. Each item opens a Panel, where the user can view all of the RROs for that app and if
 * they are dynamic RROs they can also toggle them.
 * The Module provides also the [PanelActionReducer] for this feature.
 */
@Module
internal class AppRroModule {

    @Provides
    @ApplicationsMenu
    @IntoSet
    fun provideDialerRROPanelLauncher(
        packageManager: PackageManager,
    ): MenuItem = createUnbundledPanelLauncher(
        displayTextRes = R.string.menu_applications_dialer_panel,
        appPackage = "com.android.car.dialer",
        panelTitle = "Dialer",
        packageManager,
    )

    @Provides
    @ApplicationsMenu
    @IntoSet
    fun provideMediaRroPanelLauncher(
        packageManager: PackageManager,
    ): MenuItem = createUnbundledPanelLauncher(
        displayTextRes = R.string.menu_applications_media_panel,
        appPackage = "com.android.car.media",
        panelTitle = "Media",
        packageManager,
    )

    @Provides
    @ApplicationsMenu
    @IntoSet
    fun provideMessengerRroPanelLauncher(
        packageManager: PackageManager,
    ): MenuItem = createUnbundledPanelLauncher(
        displayTextRes = R.string.menu_applications_messenger_panel,
        appPackage = "com.android.car.messenger",
        panelTitle = "Messenger",
        packageManager,
    )

    @Provides
    @IntoMap
    @PanelReducerKey(AppRroPanelReducer::class)
    fun provideUnbundledRroPanel(
        overlayManager: OverlayManager,
    ): PanelActionReducer = AppRroPanelReducer(overlayManager)
}

private fun createUnbundledPanelLauncher(
    @StringRes displayTextRes: Int,
    appPackage: String,
    panelTitle: String,
    packageManager: PackageManager,
): MenuItem {
    val isEnabled = try {
        packageManager.getPackageInfo(appPackage, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
    return MenuItem.PanelLauncher(
        displayTextRes = displayTextRes,
        isEnabled = isEnabled,
        action = OpenPanelAction(
            AppRroPanelReducer::class,
            mapOf(
                AppRroPanelReducer.BUNDLE_APP_PACKAGE to appPackage,
                AppRroPanelReducer.BUNDLE_PANEL_TITLE to panelTitle
            )
        )
    )
}
