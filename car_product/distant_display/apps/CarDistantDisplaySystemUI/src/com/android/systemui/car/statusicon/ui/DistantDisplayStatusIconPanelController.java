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

package com.android.systemui.car.statusicon.ui;

import android.view.View;

import com.android.systemui.car.distantdisplay.common.DistantDisplayController;
import com.android.systemui.car.statusicon.StatusIconPanelViewController;
import com.android.systemui.car.systembar.CarSystemBarPanelButtonView;
import com.android.systemui.car.systembar.CarSystemBarPanelButtonViewController;
import com.android.systemui.car.systembar.element.CarSystemBarElementController;
import com.android.systemui.car.systembar.element.CarSystemBarElementStateController;
import com.android.systemui.car.systembar.element.CarSystemBarElementStatusBarDisableController;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

import javax.inject.Provider;

/** Controller for the distant display panel entry point */
public class DistantDisplayStatusIconPanelController extends CarSystemBarPanelButtonViewController
        implements DistantDisplayController.StatusChangeListener {
    private final DistantDisplayController mDistantDisplayController;

    @AssistedInject
    protected DistantDisplayStatusIconPanelController(
            @Assisted CarSystemBarPanelButtonView view,
            CarSystemBarElementStatusBarDisableController disableController,
            CarSystemBarElementStateController stateController,
            Provider<StatusIconPanelViewController.Builder> statusIconPanelBuilder,
            DistantDisplayController distantDisplayController) {
        super(view, disableController, stateController, statusIconPanelBuilder);
        mDistantDisplayController = distantDisplayController;
    }

    @AssistedFactory
    public interface Factory extends
            CarSystemBarElementController.Factory<CarSystemBarPanelButtonView,
                    DistantDisplayStatusIconPanelController> {
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();
        mDistantDisplayController.addDistantDisplayControlStatusInfoListener(this);
    }

    @Override
    protected void onViewDetached() {
        super.onViewDetached();
        mDistantDisplayController.removeDistantDisplayControlStatusInfoListener(this);
    }

    @Override
    public void onVisibilityChanged(boolean visible) {
        mView.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
