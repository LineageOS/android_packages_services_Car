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

package com.google.android.car.kitchensink.radio;

import android.annotation.Nullable;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioTuner;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.google.android.car.kitchensink.R;

import java.util.Objects;

public final class RadioTunerFragment extends Fragment {

    private static final String TAG = RadioTunerFragment.class.getSimpleName();
    private static final CharSequence NULL_TUNER_WARNING = "Tuner cannot be null";

    private final RadioTuner mRadioTuner;
    private final RadioTestFragment.TunerListener mListener;
    private boolean mViewCreated = false;

    private TextView mWarningTextView;

    RadioTunerFragment(RadioManager radioManager, int moduleId, RadioManager.BandConfig bandConfig,
                       Handler handler, RadioTestFragment.TunerListener tunerListener) {
        mRadioTuner = radioManager.openTuner(moduleId, bandConfig, /* withAudio= */ true,
                new RadioTunerCallbackImpl(), handler);
        mListener = Objects.requireNonNull(tunerListener, "Tuner listener can not be null");
    }

    RadioTuner getRadioTuner() {
        return mRadioTuner;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        Log.i(TAG, "onCreateView");
        View view = inflater.inflate(R.layout.radio_tuner_fragment, container,
                /* attachToRoot= */ false);
        Button closeButton = view.findViewById(R.id.button_radio_close);
        mWarningTextView = view.findViewById(R.id.warning_tune);

        closeButton.setOnClickListener((v) -> handleClose());

        mViewCreated = true;
        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Log.i(TAG, "onDestroyView");
    }

    private void handleClose() {
        if (mRadioTuner == null) {
            mWarningTextView.setText(getString(R.string.radio_warning, NULL_TUNER_WARNING));
            return;
        }
        mWarningTextView.setText(getString(R.string.empty));
        try {
            mRadioTuner.close();
            mListener.onTunerClosed();
        } catch (Exception e) {
            mWarningTextView.setText(getString(R.string.radio_warning, e.getMessage()));
        }
    }

    private final class RadioTunerCallbackImpl extends RadioTuner.Callback {
        @Override
        public void onTuneFailed(int result, @Nullable ProgramSelector selector) {
            if (!mViewCreated) {
                return;
            }
            String warning = "onTuneFailed:";
            if (selector != null) {
                warning += " for selector " + selector;
            }
            mWarningTextView.setText(getString(R.string.radio_warning, warning));
        }
    }
}
