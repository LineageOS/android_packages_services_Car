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

package com.chassis.car.ui.plugin.appstyledview;

import android.view.View;
import android.view.WindowManager.LayoutParams;

import androidx.annotation.NonNull;

import com.android.car.ui.appstyledview.AppStyledDialogController.NavIcon;
import com.android.car.ui.appstyledview.AppStyledViewControllerImpl;
import com.android.car.ui.plugin.oemapis.appstyledview.AppStyledViewControllerOEMV1;

/**
 * See {@code AppStyledViewControllerAdapterProxyV3}. This class is for backwards compatibility with
 * apps that use an older version of car-ui-lib.
 */
public class AppStyledViewControllerAdapterProxyV1 implements AppStyledViewControllerOEMV1 {

    @NonNull
    private final AppStyledViewControllerImpl mStaticController;
    private View mContentView;

    public AppStyledViewControllerAdapterProxyV1(@NonNull AppStyledViewControllerImpl controller) {
        mStaticController = controller;
    }

    @Override
    public View getView() {
        if (mContentView == null) {
            return null;
        }

        //TODO(b/333901191): Rename api or cache
        return mStaticController.createAppStyledView(mContentView);
    }

    @Override
    public void setContent(View view) {
        mContentView = view;
        mStaticController.setContent(mContentView);
    }

    @Override
    public void setOnBackClickListener(Runnable runnable) {
        mStaticController.setOnNavIconClickListener(runnable);
    }

    @Override
    public void setNavIcon(@NavIcon int navIcon) {
        switch (navIcon) {
            case AppStyledViewControllerOEMV1.NAV_ICON_BACK:
                mStaticController.setNavIcon(NavIcon.BACK);
                break;
            case AppStyledViewControllerOEMV1.NAV_ICON_CLOSE:
                mStaticController.setNavIcon(NavIcon.CLOSE);
                break;
            default:
                throw new IllegalArgumentException("Unknown nav icon style: " + navIcon);
        }
    }

    @NonNull
    @Override
    public LayoutParams getDialogWindowLayoutParam(@NonNull LayoutParams params) {
        return mStaticController.getDialog().getDialogWindowLayoutParam(params);
    }
}

