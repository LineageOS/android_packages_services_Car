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

package com.android.car.portraitlauncher.homescreen.audio;

import android.app.ActivityOptions;
import android.content.Intent;
import android.view.Display;

import com.android.car.carlauncher.homescreen.CardPresenter;
import com.android.car.carlauncher.homescreen.HomeCardFragment;
import com.android.car.carlauncher.homescreen.HomeCardInterface;
import com.android.car.carlauncher.homescreen.audio.AudioFragment;
import com.android.car.carlauncher.homescreen.audio.AudioModel;
import com.android.car.carlauncher.homescreen.audio.InCallModel;
import com.android.car.carlauncher.homescreen.audio.MediaViewModel;
import com.android.car.carlauncher.homescreen.ui.DescriptiveTextWithControlsView;

import java.util.List;

/**
 * A portrait UI version of {@link HomeAudioCardPresenter}
 */
public class PortraitHomeAudioCardPresenter extends CardPresenter {

    private AudioFragment mAudioFragment;
    private MediaViewModel mMediaViewModel;
    private List<HomeCardInterface.Model> mModelList;
    private AudioModel mCurrentModel;
    public final MediaIntentRouter mMediaIntentRouter = MediaIntentRouter.getInstance();

    private HomeCardFragment.OnViewClickListener mOnViewClickListener =
            new HomeCardFragment.OnViewClickListener() {
                @Override
                public void onViewClicked() {
                    Intent intent = mCurrentModel.getIntent();
                    if (intent != null && mCurrentModel instanceof InCallModel) {
                        ActivityOptions options = ActivityOptions.makeBasic();
                        options.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
                        mAudioFragment.getContext().startActivity(intent, options.toBundle());
                    } else {
                        mMediaIntentRouter.handleMediaIntent(intent);
                    }
                }
            };

    private HomeCardFragment.OnViewLifecycleChangeListener mOnViewLifecycleChangeListener =
            new HomeCardFragment.OnViewLifecycleChangeListener() {
                @Override
                public void onViewCreated() {
                    for (HomeCardInterface.Model model : mModelList) {
                        if (model.getClass() == MediaViewModel.class) {
                            mMediaViewModel = (MediaViewModel) model;
                            mMediaViewModel.setOnProgressUpdateListener(mOnProgressUpdateListener);
                        }
                        model.setOnModelUpdateListener(mOnModelUpdateListener);
                        model.onCreate(getFragment().requireContext());
                    }
                }

                @Override
                public void onViewDestroyed() {
                    if (mModelList != null) {
                        for (HomeCardInterface.Model model : mModelList) {
                            model.onDestroy(getFragment().requireContext());
                        }
                    }
                }
            };

    private AudioFragment.OnMediaViewInitializedListener mOnMediaViewInitializedListener =
            new AudioFragment.OnMediaViewInitializedListener() {
                @Override
                public void onMediaViewInitialized() {
                    // set playbackviewmodel on playback control actions view
                    mAudioFragment.getPlaybackControlsActionBar().setModel(
                            mMediaViewModel.getPlaybackViewModel(),
                            mAudioFragment.getViewLifecycleOwner());
                }
            };

    private HomeCardInterface.Model.OnModelUpdateListener mOnModelUpdateListener =
            new HomeCardInterface.Model.OnModelUpdateListener() {
                @Override
                public void onModelUpdate(HomeCardInterface.Model model) {
                    AudioModel audioModel = (AudioModel) model;
                    // Null card header indicates the model has no content to display
                    if (audioModel.getCardHeader() == null) {
                        if (mCurrentModel != null
                                && audioModel.getClass() == mCurrentModel.getClass()) {
                            // If the model currently on display is updating to empty content,
                            // check if there
                            // is media content to display. If there is no media content the
                            // super method is
                            // called with empty content, which hides the card.
                            if (mMediaViewModel != null
                                    && mMediaViewModel.getCardHeader() != null) {
                                mCurrentModel = mMediaViewModel;
                                updateCurrentModelInFragment();
                                return;
                            }
                        } else {
                            // Otherwise, another model is already on display, so don't update
                            // with this
                            // empty content since that would hide the card.
                            return;
                        }
                    } else if (mCurrentModel != null
                            && mCurrentModel.getClass() == InCallModel.class
                            && audioModel.getClass() != InCallModel.class) {
                        // If the Model has content, check if currentModel on display is an
                        // ongoing phone call.
                        // If there is any ongoing phone call, do not update the View
                        // if the model trying to update View is NOT a phone call.
                        return;
                    }
                    mCurrentModel = audioModel;
                    updateCurrentModelInFragment();
                }
            };

    private AudioModel.OnProgressUpdateListener mOnProgressUpdateListener =
            new AudioModel.OnProgressUpdateListener() {
                @Override
                public void onProgressUpdate(AudioModel model, boolean updateProgress) {
                    if (model == null || model.getCardContent() == null
                            || model.getCardHeader() == null) {
                        return;
                    }
                    DescriptiveTextWithControlsView descriptiveTextWithControlsContent =
                            (DescriptiveTextWithControlsView) model.getCardContent();
                    mAudioFragment.updateProgress(
                            descriptiveTextWithControlsContent.getSeekBarViewModel(),
                            updateProgress);
                }
            };


    @Override
    public void setModels(List<HomeCardInterface.Model> models) {
        mModelList = models;
    }

    protected List<HomeCardInterface.Model> getModels() {
        return mModelList;
    }

    @Override
    public void setView(HomeCardInterface.View view) {
        super.setView(view);
        mAudioFragment = (AudioFragment) view;
        mAudioFragment.setOnViewLifecycleChangeListener(mOnViewLifecycleChangeListener);
        mAudioFragment.setOnViewClickListener(mOnViewClickListener);
        mAudioFragment.setOnMediaViewInitializedListener(mOnMediaViewInitializedListener);
    }

    private void updateCurrentModelInFragment() {
        if (mCurrentModel != null && mCurrentModel.getCardHeader() != null) {
            mAudioFragment.updateHeaderView(mCurrentModel.getCardHeader());
            if (mCurrentModel.getCardContent() != null) {
                mAudioFragment.updateContentView(mCurrentModel.getCardContent());
            }
        } else {
            mAudioFragment.hideCard();
        }
    }
}
