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

package com.android.car.portraitlauncher.controlbar.media;

import android.content.Intent;

import com.android.car.carlauncher.homescreen.CardPresenter;
import com.android.car.carlauncher.homescreen.HomeCardFragment;
import com.android.car.carlauncher.homescreen.HomeCardFragment.OnViewLifecycleChangeListener;
import com.android.car.carlauncher.homescreen.HomeCardInterface;
import com.android.car.carlauncher.homescreen.audio.AudioFragment;
import com.android.car.carlauncher.homescreen.audio.AudioModel;
import com.android.car.carlauncher.homescreen.audio.MediaViewModel;
import com.android.car.carlauncher.homescreen.ui.DescriptiveTextWithControlsView;

import java.util.List;

/**
 * A portrait UI version of {@link MediaCardPresenter}
 */
public class MediaCardPresenter extends CardPresenter {

    public final MediaIntentRouter mMediaIntentRouter = MediaIntentRouter.getInstance();

    private MediaViewModel mViewModel;
    private MediaCardFragment mFragment;

    private final HomeCardFragment.OnViewClickListener mOnViewClickListener =
            new HomeCardFragment.OnViewClickListener() {
                @Override
                public void onViewClicked() {
                    Intent intent = mViewModel.getIntent();
                    mMediaIntentRouter.handleMediaIntent(intent);
                }
            };

    private final HomeCardInterface.Model.OnModelUpdateListener mOnMediaModelUpdateListener =
            new HomeCardInterface.Model.OnModelUpdateListener() {
                @Override
                public void onModelUpdate(HomeCardInterface.Model model) {
                    mFragment.updateHeaderView(mViewModel.getCardHeader());
                    mFragment.updateContentView(mViewModel.getCardContent());
                }
            };

    private final OnViewLifecycleChangeListener mOnMediaViewLifecycleChangeListener =
            new OnViewLifecycleChangeListener() {
                @Override
                public void onViewCreated() {
                    mViewModel.setOnProgressUpdateListener(mOnMediaProgressUpdateListener);
                    mViewModel.setOnModelUpdateListener(mOnMediaModelUpdateListener);
                    mViewModel.onCreate(getFragment().requireContext());
                }

                @Override
                public void onViewDestroyed() {
                    mViewModel.onDestroy(getFragment().requireContext());
                }
            };

    private final AudioFragment.OnMediaViewInitializedListener mOnMediaViewInitializedListener =
            new AudioFragment.OnMediaViewInitializedListener() {
                @Override
                public void onMediaViewInitialized() {
                    mFragment.getPlaybackControlsActionBar().setModel(
                            mViewModel.getPlaybackViewModel(),
                            mFragment.getViewLifecycleOwner());
                }
            };

    private final AudioModel.OnProgressUpdateListener mOnMediaProgressUpdateListener =
            new AudioModel.OnProgressUpdateListener() {
                @Override
                public void onProgressUpdate(AudioModel model, boolean updateProgress) {
                    if (model == null || model.getCardContent() == null
                            || model.getCardHeader() == null) {
                        return;
                    }
                    DescriptiveTextWithControlsView descriptiveTextWithControlsContent =
                            (DescriptiveTextWithControlsView) model.getCardContent();
                    mFragment.updateProgress(
                            descriptiveTextWithControlsContent.getSeekBarViewModel(),
                            updateProgress);
                }
            };


    // Deprecated. Use setModel instead.
    @Override
    public void setModels(List<HomeCardInterface.Model> models) {
        // No-op
    }

    public void setModel(MediaViewModel viewModel) {
        mViewModel = viewModel;
    }

    @Override
    public void setView(HomeCardInterface.View view) {
        super.setView(view);

        mFragment = (MediaCardFragment) view;
        mFragment.setOnViewLifecycleChangeListener(mOnMediaViewLifecycleChangeListener);
        mFragment.setOnViewClickListener(mOnViewClickListener);
        mFragment.setOnMediaViewInitializedListener(mOnMediaViewInitializedListener);
    }
}
