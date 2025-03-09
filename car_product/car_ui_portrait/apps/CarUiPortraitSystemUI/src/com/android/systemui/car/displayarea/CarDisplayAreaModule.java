/*
 * Copyright (C) 2024 The Android Open Source Project.
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

package com.android.systemui.car.displayarea;

import android.content.Context;
import android.os.Handler;

import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.car.loading.LoadingViewController;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.qs.QSHost;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.wm.CarUiPortraitDisplaySystemBarsController;
import com.android.wm.shell.common.HandlerExecutor;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.fullscreen.FullscreenTaskListener;
import com.android.wm.shell.shared.TransactionPool;
import com.android.wm.shell.transition.Transitions;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;

/**
 * Module for Car SysUI Display area
 */
@Module
public abstract class CarDisplayAreaModule {

    @Provides
    @SysUISingleton
    static CarDisplayAreaOrganizer provideCarDisplayAreaOrganizer(
            ShellExecutor mainExecutor, Context context,
            SyncTransactionQueue syncQueue) {
        return new CarDisplayAreaOrganizer(mainExecutor, context, syncQueue);
    }

    @Provides
    @SysUISingleton
    static CarDisplayAreaController provideCarDisplayAreaController(Context context,
            FullscreenTaskListener fullscreenTaskListener,
            ShellExecutor mainExecutor, CarServiceProvider carServiceProvider,
            CarDisplayAreaOrganizer organizer,
            QSHost host,
            CarUiPortraitDisplaySystemBarsController carUiPortraitDisplaySystemBarsController,
            CommandQueue commandQueue,
            CarDeviceProvisionedController deviceProvisionedController,
            LoadingViewController loadingViewController,
            SyncTransactionQueue syncQueue,
            ConfigurationController configurationController,
            CarDisplayAreaTransitions carDisplayAreaTransitions,
            TaskCategoryManager taskCategoryManager
    ) {
        return new CarDisplayAreaController(context, fullscreenTaskListener,
                mainExecutor, carServiceProvider, organizer, host,
                carUiPortraitDisplaySystemBarsController, commandQueue,
                deviceProvisionedController, loadingViewController,
                syncQueue, configurationController, carDisplayAreaTransitions, taskCategoryManager);
    }

    @Provides
    @IntoSet
    static ConfigurationListener provideCarSystemBarConfigListener(
            CarDisplayAreaController carDisplayAreaController) {
        return carDisplayAreaController;
    }

    @Provides
    @SysUISingleton
    static SyncTransactionQueue provideSyncTransactionQueue(TransactionPool pool,
            ShellExecutor mainExecutor) {
        return new SyncTransactionQueue(pool, mainExecutor);
    }

    @Provides
    @SysUISingleton
    static TransactionPool provideTransactionPool() {
        return new TransactionPool();
    }

    /**
     * Provide a SysUI main-thread Executor.
     */
    @Provides
    @SysUISingleton
    public static ShellExecutor provideShellExecutor(
            Handler sysuiMainHandler) {
        return new HandlerExecutor(sysuiMainHandler);
    }

    /**
     * Provide a {@link CarDisplayAreaTransitions}.
     */
    @Provides
    @SysUISingleton
    public static CarDisplayAreaTransitions provideCarDisplayAreaTransitions(
            Transitions transitions) {
        return new CarDisplayAreaTransitions(transitions);
    }
}
