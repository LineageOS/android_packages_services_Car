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
package com.android.car.portraitlauncher.controlbar.dialer;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Size;
import android.view.View;
import android.widget.Chronometer;

import com.android.car.apps.common.BitmapUtils;
import com.android.car.carlauncher.R;
import com.android.car.carlauncher.homescreen.HomeCardFragment;
import com.android.car.carlauncher.homescreen.ui.CardContent;
import com.android.car.carlauncher.homescreen.ui.DescriptiveTextWithControlsView;

/** A fragment for in-call controls. */
public class DialerCardFragment extends HomeCardFragment {
    private Chronometer mChronometer;
    private View mChronometerSeparator;

    private CardContent.CardBackgroundImage mDefaultCardBackgroundImage;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDefaultCardBackgroundImage = new CardContent.CardBackgroundImage(
                getContext().getDrawable(R.drawable.default_audio_background),
                getContext().getDrawable(R.drawable.control_bar_image_background));
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        getRootView().setVisibility(View.VISIBLE);
        getRootView().findViewById(R.id.optional_seek_bar_with_times_container).setVisibility(
                View.GONE);
    }

    @Override
    public void updateContentViewInternal(CardContent content) {
        if (content.getType() == CardContent.HomeCardContentType.DESCRIPTIVE_TEXT_WITH_CONTROLS) {
            DescriptiveTextWithControlsView audioContent =
                    (DescriptiveTextWithControlsView) content;
            updateBackgroundImage(audioContent.getImage());
            updateDescriptiveTextWithControlsView(audioContent.getTitle(),
                    audioContent.getSubtitle(),
                    /* optionalImage= */ null, audioContent.getLeftControl(),
                    audioContent.getCenterControl(), audioContent.getRightControl());
            updateAudioDuration(audioContent);
        } else {
            super.updateContentViewInternal(content);
        }
    }

    @Override
    protected void hideAllViews() {
        super.hideAllViews();
        getCardBackground().setVisibility(View.GONE);
    }

    private Chronometer getChronometer() {
        if (mChronometer == null) {
            mChronometer = getDescriptiveTextWithControlsLayoutView().findViewById(
                    R.id.optional_timer);
            mChronometerSeparator = getDescriptiveTextWithControlsLayoutView().findViewById(
                    R.id.optional_timer_separator);
        }
        return mChronometer;
    }

    private void updateBackgroundImage(CardContent.CardBackgroundImage cardBackgroundImage) {
        if (cardBackgroundImage == null || cardBackgroundImage.getForeground() == null) {
            cardBackgroundImage = mDefaultCardBackgroundImage;
        }
        int maxDimen = Math.max(getCardBackgroundImage().getWidth(),
                getCardBackgroundImage().getHeight());
        Size scaledSize = new Size(maxDimen, maxDimen);
        Bitmap imageBitmap = BitmapUtils.fromDrawable(cardBackgroundImage.getForeground(),
                scaledSize);
        if (cardBackgroundImage.getBackground() != null) {
            getCardBackgroundImage().setBackground(cardBackgroundImage.getBackground());
            getCardBackgroundImage().setClipToOutline(true);
        }
        getCardBackgroundImage().setImageBitmap(imageBitmap, /* showAnimation= */ true);
        getCardBackground().setVisibility(View.VISIBLE);
    }

    private void updateAudioDuration(DescriptiveTextWithControlsView content) {
        if (content.getStartTime() > 0) {
            getChronometer().setVisibility(View.VISIBLE);
            getChronometer().setBase(content.getStartTime());
            getChronometer().start();
            mChronometerSeparator.setVisibility(View.VISIBLE);
        } else {
            getChronometer().setVisibility(View.GONE);
            mChronometerSeparator.setVisibility(View.GONE);
        }
    }
}

