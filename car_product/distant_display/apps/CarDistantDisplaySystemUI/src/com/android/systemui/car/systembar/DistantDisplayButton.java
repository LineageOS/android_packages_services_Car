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

package com.android.systemui.car.systembar;

import static com.android.systemui.car.users.CarSystemUIUserUtil.getCurrentUserHandle;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import android.view.View;

/**
 * DistantDisplayButton is an extension of CarSystemBarButton that controls selection state
 * independent of {@link ButtonSelectionStateController}.
 */
public class DistantDisplayButton extends CarSystemBarButton {

    public DistantDisplayButton(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Defines the behavior of a button click of broadcast button and sets the selection state
     * without a dependency on CarSystemBarButton_toggleSelected attribute.
     */
    @Override
    protected View.OnClickListener getButtonClickListener(Intent toSend) {
        return v -> {
            boolean startState = getSelected();
            setSelected(!startState);
            Context context = getContext();
            context.sendBroadcastAsUser(toSend,
                    getCurrentUserHandle(context, getUserTracker()));
        };
    }
}
