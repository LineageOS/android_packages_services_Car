/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.car.systembar;

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.car.caruiportrait.common.service.CarUiPortraitService.INTENT_EXTRA_ROOT_TASK_VIEW_VISIBILITY_CHANGE;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.REQUEST_FROM_LAUNCHER;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.android.systemui.car.displayarea.CarDisplayAreaController;

class CarUiPortraitButtonSelectionStateListener extends ButtonSelectionStateListener {

    private final CarDisplayAreaController mDisplayAreaController;
    private boolean mIsRootTaskViewVisible;

    CarUiPortraitButtonSelectionStateListener(Context context,
            ButtonSelectionStateController carSystemButtonController,
            CarDisplayAreaController displayAreaController) {
        super(carSystemButtonController);
        mDisplayAreaController = displayAreaController;

        BroadcastReceiver displayAreaVisibilityReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mIsRootTaskViewVisible = intent.getBooleanExtra(
                        INTENT_EXTRA_ROOT_TASK_VIEW_VISIBILITY_CHANGE, false);
                onTaskStackChanged();
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(REQUEST_FROM_LAUNCHER);
        context.registerReceiverForAllUsers(displayAreaVisibilityReceiver,
                filter, null, null, Context.RECEIVER_EXPORTED);
    }

    @Override
    public void onTaskStackChanged() {
        if (!mIsRootTaskViewVisible) {
            mButtonSelectionStateController.clearAllSelectedButtons(DEFAULT_DISPLAY);
            return;
        }
        super.onTaskStackChanged();
    }
}
