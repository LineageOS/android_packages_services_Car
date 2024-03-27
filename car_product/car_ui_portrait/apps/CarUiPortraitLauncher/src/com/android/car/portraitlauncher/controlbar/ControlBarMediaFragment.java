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

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.car.carlauncher.homescreen.audio.media.MediaCardFragment;
import com.android.car.portraitlauncher.R;

public class ControlBarMediaFragment extends MediaCardFragment {

    private ControlBarMediaController mControlBarController;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        return inflater.inflate(R.layout.control_bar_media_card, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        mControlBarController = (ControlBarMediaController) new ControlBarMediaController.Builder()
                .setModels(mViewModel.getPlaybackViewModel(),
                        mViewModel,
                        mViewModel.getMediaItemsRepository())
                .setViewGroup((ViewGroup) view)
                .build();
    }
}
