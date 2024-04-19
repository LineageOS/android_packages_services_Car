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

package com.android.systemui.car.statusicon.ui;

import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import com.android.systemui.R;
import com.android.systemui.car.distantdisplay.common.DistantDisplayController;
import com.android.systemui.car.statusicon.StatusIconView;
import com.android.systemui.car.statusicon.StatusIconViewController;
import com.android.systemui.car.systembar.element.CarSystemBarElementStateController;
import com.android.systemui.car.systembar.element.CarSystemBarElementStatusBarDisableController;
import com.android.systemui.dagger.qualifiers.Main;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

/** Controller for the distant display status icon */
public class DistantDisplayStatusIconController extends StatusIconViewController implements
        DistantDisplayController.StatusChangeListener {
    public static final String TAG = DistantDisplayStatusIconController.class.getSimpleName();
    private static final int DEFAULT_DISPLAY_ID = 0;
    private final DistantDisplayController mDistantDisplayController;
    private final Drawable mDistantDisplayDrawable;
    private final Drawable mDefaultDisplayDrawable;
    private int mCurrentDisplayId;

    @AssistedInject
    DistantDisplayStatusIconController(
            @Assisted StatusIconView view,
            CarSystemBarElementStatusBarDisableController disableController,
            CarSystemBarElementStateController stateController,
            DistantDisplayController distantDisplayController,
            @Main Resources resources) {
        super(view, disableController, stateController);
        mDistantDisplayController = distantDisplayController;
        mDistantDisplayDrawable = resources.getDrawable(
                R.drawable.ic_sys_ui_send_to_distant_display, /* theme= */ null);
        mDefaultDisplayDrawable = resources.getDrawable(
                R.drawable.ic_sys_ui_bring_back, /* theme= */ null);
    }

    @AssistedFactory
    public interface Factory extends
            StatusIconViewController.Factory<DistantDisplayStatusIconController> {
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
    protected void updateStatus() {
        if (mCurrentDisplayId == DEFAULT_DISPLAY_ID) {
            setIconDrawableToDisplay(mDistantDisplayDrawable);
        } else {
            setIconDrawableToDisplay(mDefaultDisplayDrawable);
        }
        onStatusUpdated();
    }

    @Override
    public void onDisplayChanged(int displayId) {
        mCurrentDisplayId = displayId;
        updateStatus();
    }
}
