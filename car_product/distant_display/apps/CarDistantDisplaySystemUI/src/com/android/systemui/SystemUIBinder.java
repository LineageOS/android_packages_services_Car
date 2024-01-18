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

import com.android.systemui.car.distantdisplay.activity.window.ActivityWindowModule;
import com.android.systemui.car.distantdisplay.activity.window.DistantDisplayActivityWindowManager;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;

/** Binder for AAECarSystemUI specific {@link CoreStartable} modules and components. */
@Module(includes = {ActivityWindowModule.class})
abstract class SystemUIBinder extends CarSystemUIBinder {


    @Binds
    @IntoMap
    @ClassKey(DistantDisplayActivityWindowManager.class)
    public abstract CoreStartable bindActivityWindowManager(
            DistantDisplayActivityWindowManager activityWindowManager);
}
