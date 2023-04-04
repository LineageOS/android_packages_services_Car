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

package com.android.systemui.car.aloha;

import android.content.Context;

import com.android.systemui.car.window.OverlayViewMediator;
import com.android.systemui.dagger.SysUISingleton;

import javax.inject.Inject;

/**
 * The view mediator which attaches the view controller to other elements of the system ui.
 */
@SysUISingleton
public class AlohaViewMediator implements OverlayViewMediator {

    Context mContext;
    AlohaViewController mAlohaViewController;


    @Inject
    public AlohaViewMediator(Context context, AlohaViewController alohaViewController) {
        mContext = context;
        mAlohaViewController = alohaViewController;
    }

    @Override
    public void registerListeners() {
    }

    @Override
    public void setUpOverlayContentViewControllers() {

    }
}
