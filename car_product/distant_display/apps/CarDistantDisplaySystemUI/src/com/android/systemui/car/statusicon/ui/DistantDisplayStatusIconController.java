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

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;

import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.car.qc.SystemUIQCViewController;
import com.android.systemui.car.statusicon.StatusIconController;
import com.android.systemui.car.statusicon.StatusIconPanelController;
import com.android.systemui.car.systembar.DistantDisplayController;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.ConfigurationController;

import javax.inject.Inject;
import javax.inject.Provider;

public class DistantDisplayStatusIconController extends StatusIconController implements
        DistantDisplayController.StatusChangeListener {
    public static final String TAG = DistantDisplayStatusIconController.class.getSimpleName();
    private static final int DEFAULT_DISPLAY_ID = 0;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final CarServiceProvider mCarServiceProvider;
    private final Context mContext;
    private final ConfigurationController mConfigurationController;
    private final DistantDisplayController mDistantDisplayController;
    private final Provider<SystemUIQCViewController> mQCViewControllerProvider;
    private final UserTracker mUserTracker;
    private Drawable mDistantDisplayDrawable;
    private Drawable mDefaultDisplayDrawable;
    private ImageView mDistantDisplayButton;
    private int mCurrentDisplayId;

    private StatusIconPanelController mDistantDisplayPanelController;

    @Inject
    DistantDisplayStatusIconController(BroadcastDispatcher broadcastDispatcher,
            CarServiceProvider carServiceProvider, Context context,
            ConfigurationController configurationController,
            DistantDisplayController distantDisplayController,
            Provider<SystemUIQCViewController> qcViewControllerProvider, UserTracker userTracker,
            @Main Resources resources) {
        mBroadcastDispatcher = broadcastDispatcher;
        mCarServiceProvider = carServiceProvider;
        mContext = context;
        mConfigurationController = configurationController;
        mDistantDisplayController = distantDisplayController;
        mQCViewControllerProvider = qcViewControllerProvider;
        mUserTracker = userTracker;
        mDistantDisplayDrawable = resources.getDrawable(
                R.drawable.ic_sys_ui_send_to_distant_display, /* theme= */ null);
        mDefaultDisplayDrawable = resources.getDrawable(
                R.drawable.ic_sys_ui_bring_back, /* theme= */ null);
    }

    /**
     * Find the {@link DistantDisplayButton} from a view and sets button state.
     */
    public void addDistantDisplayButtonView(View view) {
        if (mDistantDisplayButton != null) return;

        mDistantDisplayButton = view.findViewById(getId());

        if (mDistantDisplayButton == null) {
            Log.e(TAG, "Distant Display button view not found to initialize button and panel.");
            return;
        }

        boolean profilePanelDisabledWhileDriving = mContext.getResources().getBoolean(
                R.bool.config_profile_panel_disabled_while_driving);
        mDistantDisplayPanelController = new StatusIconPanelController(
                mContext, mUserTracker,
                mCarServiceProvider, mBroadcastDispatcher, mConfigurationController,
                mQCViewControllerProvider, profilePanelDisabledWhileDriving);
        mDistantDisplayPanelController.attachPanel(mDistantDisplayButton, getPanelContentLayout(),
                R.dimen.car_profile_quick_controls_panel_width, Gravity.TOP | Gravity.END);
        registerIconView(mDistantDisplayButton);
        mDistantDisplayController.setDistantDisplayControlStatusInfoListener(this);
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
    public void onDestroy() {
        if (mDistantDisplayPanelController != null) {
            mDistantDisplayPanelController.destroyPanel();
        }
        if (mDistantDisplayButton != null) {
            unregisterIconView(mDistantDisplayButton);
        }
        mDistantDisplayController.removeDistantDisplayControlStatusInfoListener();
    }

    @Override
    protected int getId() {
        return R.id.distant_display_nav;
    }

    @Override
    protected int getPanelContentLayout() {
        return R.layout.qc_distant_display_panel;
    }

    @Override
    public void onDisplayChanged(int displayId) {
        mCurrentDisplayId = displayId;
        updateStatus();
    }

    @Override
    public void onVisibilityChanged(boolean visible) {
        setIconVisibility(visible);
        onStatusUpdated();
    }
}
