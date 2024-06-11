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

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;

/** The button used to show the notification in the system bar. */
public class CarUiPortraitNotificationButton extends CarUiPortraitSystemBarButton {
    private boolean mIsSelected;

    public CarUiPortraitNotificationButton(Context context,
            AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void setSelected(boolean selected) {
        super.setSelected(selected);
        mIsSelected = selected;
    }

    @Override
    protected OnClickListener getButtonClickListener(Intent toSend) {
        return v -> {
            if (mIsSelected) {
                collapseApplicationPanel();
                return;
            }
            super.getButtonClickListener(toSend).onClick(v);
        };
    }
}
