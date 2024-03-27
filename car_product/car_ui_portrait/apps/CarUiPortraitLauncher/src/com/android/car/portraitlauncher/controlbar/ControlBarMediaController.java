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

import static android.car.media.CarMediaIntents.EXTRA_MEDIA_COMPONENT;

import android.car.media.CarMediaIntents;
import android.content.Intent;

import com.android.car.carlauncher.homescreen.audio.media.MediaIntentRouter;
import com.android.car.media.common.source.MediaSource;
import com.android.car.media.common.ui.PlaybackCardController;

public class ControlBarMediaController extends PlaybackCardController {

    private final MediaIntentRouter mMediaIntentRouter = MediaIntentRouter.getInstance();

    /**
     * Builder for {@link ControlBarMediaController}. Overrides build() method to return
     * ControlBarMediaController rather than base {@link PlaybackCardController}
     */
    public static class Builder extends PlaybackCardController.Builder {

        @Override
        public ControlBarMediaController build() {
            ControlBarMediaController controller = new ControlBarMediaController(this);
            controller.setupController();
            return controller;
        }
    }

    public ControlBarMediaController(ControlBarMediaController.Builder builder) {
        super(builder);

        mView.setOnClickListener(view -> {
            MediaSource mediaSource = mDataModel.getMediaSource().getValue();
            Intent intent = new Intent(CarMediaIntents.ACTION_MEDIA_TEMPLATE);
            if (mediaSource != null) {
                intent.putExtra(EXTRA_MEDIA_COMPONENT,
                        mediaSource.getBrowseServiceComponentName().flattenToString());
            }
            mMediaIntentRouter.handleMediaIntent(intent);
        });
    }
}
