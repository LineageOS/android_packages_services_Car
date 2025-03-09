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

package com.android.systemui;

import android.content.Context;
import android.os.UserHandle;

import com.android.systemui.dagger.GlobalRootComponent;
import com.android.systemui.dagger.SysUIComponent;
import com.android.systemui.wmshell.CarDistantDisplayWMComponent;
import com.android.wm.shell.dagger.WMComponent;

import java.util.Optional;


/**
 * Class factory to provide AAECarSystemUI specific SystemUI components.
 */
public class CarDistantDisplaySystemUIInitializer extends CarSystemUIInitializer {
    public CarDistantDisplaySystemUIInitializer(Context context) {
        super(context);
    }

    @Override
    protected GlobalRootComponent.Builder getGlobalRootComponentBuilder() {
        return DaggerCarDistantDisplayGlobalRootComponent.builder();
    }

    @Override
    protected SysUIComponent.Builder prepareSysUIComponentBuilder(
            SysUIComponent.Builder sysUIBuilder, WMComponent wm) {
        CarDistantDisplayWMComponent carWm = (CarDistantDisplayWMComponent) wm;
        boolean isSystemUser = UserHandle.myUserId() == UserHandle.USER_SYSTEM;
        carWm.getDisplaySystemBarsController();
        if (isSystemUser) {
            carWm.getCarSystemUIProxy();
            carWm.getRemoteCarTaskViewTransitions();
            carWm.getDistantDisplayTransitions();
        }
        return ((CarDistantDisplaySysUIComponent.Builder) sysUIBuilder)
                .setRootTaskDisplayAreaOrganizer(
                        isSystemUser ? Optional.of(carWm.getRootTaskDisplayAreaOrganizer())
                                : Optional.empty());
    }

}
