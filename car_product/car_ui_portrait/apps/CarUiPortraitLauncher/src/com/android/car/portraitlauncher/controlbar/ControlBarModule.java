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

import androidx.lifecycle.ViewModelProvider;

import com.android.car.carlauncher.Flags;
import com.android.car.carlauncher.homescreen.audio.AudioCardModel;
import com.android.car.carlauncher.homescreen.audio.AudioCardModule;
import com.android.car.carlauncher.homescreen.audio.AudioCardPresenter;
import com.android.car.carlauncher.homescreen.audio.dialer.DialerCardPresenter;
import com.android.car.carlauncher.homescreen.audio.media.MediaCardPresenter;

public class ControlBarModule extends AudioCardModule {
    @Override
    public void setViewModelProvider(ViewModelProvider viewModelProvider) {
        if (Flags.mediaCardFullscreen()) {
            if (mViewModelProvider != null) {
                throw new IllegalStateException("Cannot reset the view model provider");
            }
            mViewModelProvider = viewModelProvider;

            mAudioCardPresenter = new AudioCardPresenter(
                    new DialerCardPresenter(), new MediaCardPresenter());
            mAudioCardPresenter.setModel(new AudioCardModel(mViewModelProvider));
            mAudioCardView = new ControlBarAudioFragment();
            mAudioCardPresenter.setView(mAudioCardView);
        } else {
            super.setViewModelProvider(viewModelProvider);
        }
    }
}
