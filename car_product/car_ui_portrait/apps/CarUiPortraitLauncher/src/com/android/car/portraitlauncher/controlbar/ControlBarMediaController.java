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
import static com.android.car.media.common.ui.PlaybackCardControllerUtilities.updateTextViewAndVisibility;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

import com.android.car.apps.common.util.ViewUtils;
import com.android.car.carlauncher.homescreen.audio.media.MediaIntentRouter;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.R;
import com.android.car.media.common.playback.PlaybackProgress;
import com.android.car.media.common.playback.PlaybackViewModel;
import com.android.car.media.common.playback.PlaybackViewModel.PlaybackController;
import com.android.car.media.common.source.MediaSource;
import com.android.car.media.common.source.MediaSourceColors;
import com.android.car.media.common.ui.PlaybackCardController;

import java.util.ArrayList;
import java.util.List;

public class ControlBarMediaController extends PlaybackCardController {

    private static final int MAX_ACTIONS_IN_DEFAULT_LAYOUT = 6;
    private final MediaIntentRouter mMediaIntentRouter = MediaIntentRouter.getInstance();

    private ViewGroup mCustomActionLayout;
    private ViewGroup mCustomActionOverflowLayout;
    private ImageButton mActionOverflowExitButton;

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

        mCustomActionLayout = mView.findViewById(R.id.custom_action_container);
        mCustomActionOverflowLayout = mView.findViewById(R.id.custom_action_overflow_container);
        mActionOverflowExitButton = mView.findViewById(R.id.overflow_exit_button);
        mActionOverflowExitButton.setOnClickListener(view ->
                handleCustomActionsOverflowButtonClicked(mActionOverflowButton));
    }

    @Override
    protected void updateMetadata(MediaItemMetadata metadata) {
        super.updateMetadata(metadata);
        if (metadata == null) {
            updateTextViewAndVisibility(mTitle,
                    mView.getContext().getResources().getString(R.string.default_media_song_title));
        }
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
        boolean hasOverflow = false;
        PlaybackController playbackController = mDataModel.getPlaybackController().getValue();
        if (playbackState != null) {
            updatePlayButtonWithPlaybackState(mPlayPauseButton, playbackState, playbackController);
            int count = 0;
            if (playbackState.isSkipNextEnabled() || playbackState.isSkipNextReserved()) {
                count++;
            }
            if (playbackState.isSkipPreviousEnabled() || playbackState.iSkipPreviousReserved()) {
                count++;
            }
            List<ImageButton> mActionsCopy = new ArrayList<>(mActions);
            if (playbackState.getCustomActions().size() > (MAX_ACTIONS_IN_DEFAULT_LAYOUT - count)) {
                while (count >= 0) {
                    int actionSlotIndexToSkip = 8 - count - 1;
                    mActionsCopy.remove(actionSlotIndexToSkip);
                    mActionsCopy.add(actionSlotIndexToSkip, null);
                    count--;
                }
                hasOverflow = true;
                mActionOverflowButton.setVisibility(View.VISIBLE);
            } else {
                mActionOverflowButton.setVisibility(View.GONE);
            }
            updateActionsWithPlaybackState(mView.getContext(), mActionsCopy, playbackState,
                    playbackController, mView.getContext().getDrawable(R.drawable.ic_skip_previous),
                    mView.getContext().getDrawable(R.drawable.ic_skip_next),
                    mView.getContext().getDrawable(R.drawable.left_half_pill_button_shape),
                    mView.getContext().getDrawable(R.drawable.right_half_pill_button_shape),
                    /* reserveSkipSlots */ true, /* defaultButtonDrawable */ null);

            if (!hasOverflow && mViewModel.getOverflowExpanded()) {
                handleCustomActionsOverflowButtonClicked(mActionOverflowButton);
            }
        }
    }

    @Override
    protected void setUpActionsOverflowButton() {
        super.setUpActionsOverflowButton();
        setOverflowState(mViewModel.getOverflowExpanded());
    }

    @Override
    protected void handleCustomActionsOverflowButtonClicked(View overflow) {
        super.handleCustomActionsOverflowButtonClicked(overflow);
        setOverflowState(mViewModel.getOverflowExpanded());
    }

    private void setOverflowState(boolean isExpanded) {
        ViewUtils.setVisible(mCustomActionLayout, !isExpanded);
        ViewUtils.setVisible(mCustomActionOverflowLayout, isExpanded);
        ViewUtils.setVisible(mActionOverflowExitButton, isExpanded);
    }
}
