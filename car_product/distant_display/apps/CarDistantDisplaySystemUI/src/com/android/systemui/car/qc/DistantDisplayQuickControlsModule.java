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

package com.android.systemui.car.qc;

import com.android.car.qc.provider.BaseLocalQCProvider;
import com.android.systemui.car.statusicon.ui.DistantDisplayStatusIconController;
import com.android.systemui.car.statusicon.ui.DistantDisplayStatusIconPanelController;
import com.android.systemui.car.systembar.element.CarSystemBarElementController;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;

/**
 * Dagger injection module for {@link SystemUIQCViewController} in CarDistantDisplaySystemUI.
 */
@Module(includes = {QuickControlsModule.class})
public abstract class DistantDisplayQuickControlsModule {
    /** Injects DisplaySwitcher. */
    @Binds
    @IntoMap
    @ClassKey(DisplaySwitcher.class)
    public abstract BaseLocalQCProvider bindDisplaySwitcher(
            DisplaySwitcher displaySwitcher);

    /** Injects DistantDisplayStatusIconController. */
    @Binds
    @IntoMap
    @ClassKey(DistantDisplayStatusIconController.class)
    public abstract CarSystemBarElementController.Factory bindDistantDisplayStatusIconController(
            DistantDisplayStatusIconController.Factory distantDisplayStatusIconController);

    /** Injects DistantDisplayStatusIconPanelController. */
    @Binds
    @IntoMap
    @ClassKey(DistantDisplayStatusIconPanelController.class)
    public abstract CarSystemBarElementController.Factory bindDistantDisplayPanelController(
            DistantDisplayStatusIconPanelController.Factory distantDisplaySPanelController);
}
