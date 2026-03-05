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

package com.android.wm.shell.automotive;

import androidx.annotation.NonNull;

import com.android.wm.shell.automotive.visibilitybarrier.VisibilityBarrierModule;
import com.android.wm.shell.compatui.letterbox.DelegateLetterboxTransitionObserver;
import com.android.wm.shell.compatui.letterbox.LetterboxCommandHandler;
import com.android.wm.shell.compatui.letterbox.config.IgnoreLetterboxDependenciesHelper;
import com.android.wm.shell.compatui.letterbox.config.LetterboxDependenciesHelper;
import com.android.wm.shell.compatui.letterbox.lifecycle.LetterboxCleanupAdapter;
import com.android.wm.shell.compatui.letterbox.state.LetterboxTaskListenerAdapter;
import com.android.wm.shell.dagger.LetterboxModule;
import com.android.wm.shell.dagger.ShellCreateTriggerOverride;
import com.android.wm.shell.dagger.WMSingleton;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;


@Module(includes = {LetterboxModule.class, VisibilityBarrierModule.class})
public abstract class AutoShellModule {
    public static final String AUTO_WM_SHELL = "AutoWmShell";

    @WMSingleton
    @Binds
    abstract AutoTaskStackController provideTaskStackController(AutoTaskStackControllerImpl impl);

    @Binds
    @IntoSet
    abstract AutoShellInitializable bindAutoTaskStackControllerInitializer(
            AutoTaskStackControllerImpl autoTaskStackController);

    @Binds
    @IntoSet
    abstract AutoShellInitializable bindHomeTaskMonitor(AutoHomeTaskMonitor homeTaskMonitor);

    @Binds
    @IntoSet
    abstract AutoShellInitializable bindProtoLogInitializer(
            CarWmShellProtoLogInitializer protoLogInitializer);

    @WMSingleton
    @ShellCreateTriggerOverride
    @Provides
    static Object provideIndependentShellComponentsToCreate(
            AutoShellInitializer initializer,
            @NonNull DelegateLetterboxTransitionObserver letterboxTransitionObserver,
            @NonNull LetterboxCommandHandler letterboxCommandHandler,
            @NonNull LetterboxTaskListenerAdapter letterboxTaskListenerAdapter,
            @NonNull LetterboxCleanupAdapter letterboxCleanupAdapter) {
        return new Object();
    }

    @WMSingleton
    @Provides
    static LetterboxDependenciesHelper provideLetterboxDependenciesHelper() {
        return new IgnoreLetterboxDependenciesHelper();
    }
}
