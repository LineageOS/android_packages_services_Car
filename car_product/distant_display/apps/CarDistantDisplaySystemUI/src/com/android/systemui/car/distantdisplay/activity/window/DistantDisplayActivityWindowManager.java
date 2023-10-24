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
package com.android.systemui.car.distantdisplay.activity.window;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;

import javax.inject.Inject;

/**
 * Dagger Subcomponent for windows within SysUI.
 */
@SysUISingleton
public class DistantDisplayActivityWindowManager implements CoreStartable {

    private final ActivityWindowController mActivityOverlayController;

    @Inject
    public DistantDisplayActivityWindowManager(
            ActivityWindowController activityOverlayController) {
        mActivityOverlayController = activityOverlayController;
    }

    @Override
    public void start() {
        mActivityOverlayController.initialize();
    }
}
