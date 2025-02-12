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

package com.android.car.portraitlauncher.controlbar;

import android.telecom.Call;

import com.android.car.carlauncher.homescreen.audio.InCallServiceManagerProvider;
import com.android.car.carlauncher.homescreen.audio.InCallViewModel;
import com.android.car.carlauncher.homescreen.ui.DescriptiveTextWithControlsView;
import com.android.car.portraitlauncher.R;
import com.android.car.telephony.calling.InCallServiceManager;
import com.android.car.telephony.common.CallDetail;
import com.android.internal.util.ArrayUtils;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/** A wrapper around InCallViewModel to track when an active call is in progress. */
public class DialerCardModel extends InCallViewModel {
    private List<Integer> mAvailableRoutes;
    private int mActiveRoute;

    public DialerCardModel() {
        InCallTaskStateRouter.InCallTaskStateListener inCallTaskStateListener = isTaskOnTop -> {
            if (updateDialpadButtonSelectedState(isTaskOnTop)) {
                mOnModelUpdateListener.onModelUpdate(this);
            }
        };
        InCallTaskStateRouter.getInstance().registerInCallTaskStateListener(
                inCallTaskStateListener);
    }

    @Override
    protected void handleActiveCall(@NotNull Call call) {
        InCallServiceManager icsManager = InCallServiceManagerProvider.get();
        CallDetail callDetails = CallDetail.fromTelecomCallDetail(call.getDetails());
        mAvailableRoutes = icsManager.getSupportedAudioRoute(callDetails);
        mActiveRoute = icsManager.getAudioRoute(
                CallDetail.fromTelecomCallDetail(call.getDetails()).getScoState());
        super.handleActiveCall(call);
    }

    /**
     * Returns audio routes supported by current call.
     */
    public List<Integer> getAvailableAudioRoutes() {
        return mAvailableRoutes;
    }

    /**
     * Returns current call audio state.
     */
    public int getActiveAudioRoute() {
        return mActiveRoute;
    }

    /**
     * Sets current call audio route.
     */
    public void setActiveAudioRoute(int audioRoute) {
        if (getCurrentCall() == null) {
            // AudioRouteButton is disabled if it is null. Simply ignore it.
            return;
        }
        InCallServiceManagerProvider.get().setAudioRoute(audioRoute, getCurrentCall());
        mActiveRoute = audioRoute;
    }

    protected void initializeAudioControls() {
        mMuteButton = new DescriptiveTextWithControlsView.Control(
                mContext.getDrawable(R.drawable.ic_mute_activatable),
                v -> {
                    boolean toggledValue = !v.isSelected();
                    InCallServiceManagerProvider.get().setMuted(toggledValue);
                    v.setSelected(toggledValue);
                });
        mEndCallButton = new DescriptiveTextWithControlsView.Control(
                mContext.getDrawable(R.drawable.ic_call_end_button),
                v -> mCurrentCall.disconnect());
        mDialpadButton = new DescriptiveTextWithControlsView.Control(
                mContext.getDrawable(R.drawable.ic_dialpad_selectable), this::onClick);
    }

    private boolean updateDialpadButtonSelectedState(boolean dialpadSelectedState) {
        int[] iconState = mDialpadButton.getIcon().getState();
        boolean selectedStateExists = ArrayUtils.contains(iconState,
                android.R.attr.state_selected);

        if (selectedStateExists == dialpadSelectedState) {
            return false;
        }

        if (dialpadSelectedState) {
            iconState = ArrayUtils.appendInt(iconState,
                    android.R.attr.state_selected);
        } else {
            iconState = ArrayUtils.removeInt(iconState,
                    android.R.attr.state_selected);
        }
        mDialpadButton
                .getIcon()
                .setState(iconState);
        return true;
    }
}
