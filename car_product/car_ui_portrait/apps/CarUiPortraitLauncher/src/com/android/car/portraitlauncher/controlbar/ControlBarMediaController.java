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

import static com.android.car.media.common.ui.PlaybackCardControllerUtilities.updateActionsWithPlaybackState;
import static com.android.car.media.common.ui.PlaybackCardControllerUtilities.updatePlayButtonWithPlaybackState;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.view.View;

import com.android.car.carlauncher.homescreen.audio.media.MediaIntentRouter;
import com.android.car.media.common.R;
import com.android.car.media.common.playback.PlaybackProgress;
import com.android.car.media.common.playback.PlaybackViewModel;
import com.android.car.media.common.source.MediaSource;
import com.android.car.media.common.source.MediaSourceColors;
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
            Intent intent = mediaSource != null ? mediaSource.getIntent() : null;
            mMediaIntentRouter.handleMediaIntent(intent);
        });
    }

    // TODO b/336857625: Possibly move SeekBar hide logic to parent class
    @Override
    protected void updateProgress(PlaybackProgress progress) {
        super.updateProgress(progress);
        if (progress == null || !progress.hasTime()) {
            mSeekBar.setVisibility(View.GONE);
        }
    }

    // TODO b/336857156: Add disabled state for play/pause button and make sure it reflects here
    @Override
    protected void updateViewsWithMediaSourceColors(MediaSourceColors colors) {
        int defaultColor = mView.getResources().getColor(R.color.car_on_surface, null);
        ColorStateList accentColor = colors != null ? ColorStateList.valueOf(
                colors.getAccentColor(defaultColor)) :
                ColorStateList.valueOf(defaultColor);

        if (mPlayPauseButton != null) {
            mPlayPauseButton.setBackgroundTintList(accentColor);
        }
        if (mSeekBar != null) {
            mSeekBar.setProgressTintList(accentColor);
        }
    }

    @Override
    protected void updatePlaybackState(PlaybackViewModel.PlaybackStateWrapper playbackState) {
        if (playbackState != null) {
            updatePlayButtonWithPlaybackState(mPlayPauseButton, playbackState);
            updateActionsWithPlaybackState(mView.getContext(), mActions, playbackState,
                    mDataModel.getPlaybackController().getValue(),
                    mView.getContext().getDrawable(R.drawable.ic_skip_previous),
                    mView.getContext().getDrawable(R.drawable.ic_skip_next),
                    mView.getContext().getDrawable(R.drawable.left_half_pill_button_shape),
                    mView.getContext().getDrawable(R.drawable.right_half_pill_button_shape),
                    /* reserveSkipSlots */ true, /* defaultButtonDrawable */ null);
        }
    }
}
