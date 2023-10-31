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
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioMetadata;
import android.hardware.radio.RadioTuner;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.android.car.broadcastradio.support.platform.ProgramSelectorExt;

import com.google.android.car.kitchensink.R;

import java.util.Objects;

public final class RadioTunerFragment extends Fragment {

    private static final String TAG = RadioTunerFragment.class.getSimpleName();
    private static final CharSequence NULL_TUNER_WARNING = "Tuner cannot be null";

    private final RadioTuner mRadioTuner;
    private final RadioTestFragment.TunerListener mListener;
    private final ProgramList mFmAmProgramList;
    private boolean mViewCreated = false;

    private ProgramInfoAdapter mProgramInfoAdapter;

    private EditText mFrequencyInput;
    private RadioGroup mFmAmBandSelection;
    private CheckBox mStepChannelCheckBox;
    private CheckBox mSeekChannelCheckBox;
    private TextView mWarningTextView;
    private TextView mCurrentStationTextView;
    private TextView mCurrentChannelTextView;
    private TextView mCurrentSongTitleTextView;
    private TextView mCurrentArtistTextView;

    RadioTunerFragment(RadioManager radioManager, int moduleId, RadioManager.BandConfig bandConfig,
                       Handler handler, RadioTestFragment.TunerListener tunerListener) {
        mRadioTuner = radioManager.openTuner(moduleId, bandConfig, /* withAudio= */ true,
                new RadioTunerCallbackImpl(), handler);
        mListener = Objects.requireNonNull(tunerListener, "Tuner listener can not be null");
        if (mRadioTuner == null) {
            mFmAmProgramList =  null;
        } else {
            mFmAmProgramList = mRadioTuner.getDynamicProgramList(/* filter= */ null);
        }
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
        Button cancelButton = view.findViewById(R.id.button_radio_cancel);
        mWarningTextView = view.findViewById(R.id.warning_tune);
        mFrequencyInput = view.findViewById(R.id.input_am_fm_frequency);
        mFmAmBandSelection = view.findViewById(R.id.button_fm_am_selection);
        RadioButton fmRadioButton = view.findViewById(R.id.button_radio_fm);
        RadioButton amRadioButton = view.findViewById(R.id.button_radio_am);
        Button tuneButton = view.findViewById(R.id.button_radio_tune);
        mStepChannelCheckBox = view.findViewById(R.id.selection_step_skip_subchannels);
        Button stepUpButton = view.findViewById(R.id.button_radio_step_up);
        Button stepDownButton = view.findViewById(R.id.button_radio_step_down);
        mSeekChannelCheckBox = view.findViewById(R.id.selection_seek_skip_subchannels);
        Button seekUpButton = view.findViewById(R.id.button_radio_seek_up);
        Button seekDownButton = view.findViewById(R.id.button_radio_seek_down);
        ListView programListView = view.findViewById(R.id.radio_program_list);
        mCurrentStationTextView = view.findViewById(R.id.radio_current_station_info);
        mCurrentChannelTextView = view.findViewById(R.id.radio_current_channel_info);
        mCurrentSongTitleTextView = view.findViewById(R.id.radio_current_song_info);
        mCurrentArtistTextView = view.findViewById(R.id.radio_current_artist_info);
        mProgramInfoAdapter = new ProgramInfoAdapter(getContext(), R.layout.program_info_item,
                new RadioManager.ProgramInfo[]{}, this);
        programListView.setAdapter(mProgramInfoAdapter);

        registerProgramListListener();

        closeButton.setOnClickListener((v) -> handleClose());
        cancelButton.setOnClickListener((v) -> handleCancel());
        tuneButton.setOnClickListener((v) -> tuneToInputStation());
        fmRadioButton.setOnClickListener((v) -> mFrequencyInput
                .setText(getString(R.string.radio_default_fm_frequency_input)));
        amRadioButton.setOnClickListener((v) -> mFrequencyInput
                .setText(getString(R.string.radio_default_am_frequency_input)));
        stepUpButton.setOnClickListener((v) -> handleStep(RadioTuner.DIRECTION_UP));
        stepDownButton.setOnClickListener((v) -> handleStep(RadioTuner.DIRECTION_DOWN));
        seekUpButton.setOnClickListener((v) -> handleSeek(RadioTuner.DIRECTION_UP));
        seekDownButton.setOnClickListener((v) -> handleSeek(RadioTuner.DIRECTION_DOWN));

        mViewCreated = true;
        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Log.i(TAG, "onDestroyView");
    }

    private void registerProgramListListener() {
        if (mFmAmProgramList == null) {
            Log.e(TAG, "Can not get program list");
            return;
        }
        OnCompleteListenerImpl onCompleteListener = new OnCompleteListenerImpl();
        mFmAmProgramList.addOnCompleteListener(getContext().getMainExecutor(), onCompleteListener);
    }

    void handleTune(ProgramSelector sel) {
        if (mRadioTuner == null) {
            mWarningTextView.setText(getString(R.string.radio_warning, NULL_TUNER_WARNING));
            return;
        }
        mWarningTextView.setText(getString(R.string.empty));
        try {
            mRadioTuner.tune(sel);
        } catch (Exception e) {
            mWarningTextView.setText(getString(R.string.radio_warning, e.getMessage()));
        }
        mListener.onTunerPlay();
    }

    private void tuneToInputStation() {
        int selectedButtonId = mFmAmBandSelection.getCheckedRadioButtonId();
        ProgramSelector sel;
        try {
            double frequencyInput = Double.parseDouble(mFrequencyInput.getText().toString());
            switch (selectedButtonId) {
                case R.id.button_radio_fm:
                    int fmFrequency = (int) Math.round(frequencyInput * 1_000);
                    sel = ProgramSelector.createAmFmSelector(RadioManager.BAND_FM, fmFrequency);
                    break;
                case R.id.button_radio_am:
                    int amFrequency = (int) Math.round(frequencyInput);
                    sel = ProgramSelector.createAmFmSelector(RadioManager.BAND_AM, amFrequency);
                    break;
                default:
                    mWarningTextView.setText(getString(R.string.radio_warning,
                            "Unsupported input selector type"));
                    return;
            }
        } catch (Exception e) {
            mWarningTextView.setText(getString(R.string.radio_warning, e.getMessage()));
            return;
        }
        handleTune(sel);
    }

    private void handleStep(int direction) {
        if (mRadioTuner == null) {
            mWarningTextView.setText(getString(R.string.radio_warning, NULL_TUNER_WARNING));
            return;
        }
        mWarningTextView.setText(getString(R.string.empty));
        try {
            mRadioTuner.step(direction, mStepChannelCheckBox.isChecked());
        } catch (Exception e) {
            mWarningTextView.setText(getString(R.string.radio_warning, e.getMessage()));
        }
        mListener.onTunerPlay();
    }

    private void handleSeek(int direction) {
        if (mRadioTuner == null) {
            mWarningTextView.setText(getString(R.string.radio_warning, NULL_TUNER_WARNING));
            return;
        }
        mWarningTextView.setText(getString(R.string.empty));
        try {
            mRadioTuner.seek(direction, mSeekChannelCheckBox.isChecked());
        } catch (Exception e) {
            mWarningTextView.setText(getString(R.string.radio_warning, e.getMessage()));
        }
        mListener.onTunerPlay();
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

    private void handleCancel() {
        if (mRadioTuner == null) {
            mWarningTextView.setText(getString(R.string.radio_warning, NULL_TUNER_WARNING));
            return;
        }
        mWarningTextView.setText(getString(R.string.empty));
        try {
            mRadioTuner.cancel();
        } catch (Exception e) {
            mWarningTextView.setText(getString(R.string.radio_warning, e.getMessage()));
        }
    }

    private void setProgramInfo(RadioManager.ProgramInfo info) {
        String channelText = null;
        if (info != null && info.getSelector().getPrimaryId().getType()
                == ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY) {
            channelText = ProgramSelectorExt.getDisplayName(info.getSelector(), /* flags= */ 0);
        }
        if (channelText == null) {
            channelText = getString(R.string.radio_na);
        }
        mCurrentStationTextView.setText(getString(R.string.radio_current_station_info,
                getMetadataText(info, RadioMetadata.METADATA_KEY_RDS_PS)));
        mCurrentChannelTextView.setText(getString(R.string.radio_current_channel_info,
                channelText));
        mCurrentArtistTextView.setText(getString(R.string.radio_current_song_info,
                getMetadataText(info, RadioMetadata.METADATA_KEY_TITLE)));
        mCurrentSongTitleTextView.setText(getString(R.string.radio_current_artist_info,
                getMetadataText(info, RadioMetadata.METADATA_KEY_ARTIST)));
    }

    private CharSequence getMetadataText(RadioManager.ProgramInfo info, String metadataType) {
        String naText = getString(R.string.radio_na);
        if (info == null || info.getMetadata() == null) {
            return naText;
        }
        CharSequence metadataText = info.getMetadata().getString(metadataType);
        return metadataText == null ? naText : metadataText;
    }

    private final class RadioTunerCallbackImpl extends RadioTuner.Callback {
        @Override
        public void onProgramInfoChanged(RadioManager.ProgramInfo info) {
            if (!mViewCreated) {
                return;
            }
            setProgramInfo(info);
        }

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

    private final class OnCompleteListenerImpl implements ProgramList.OnCompleteListener {
        @Override
        public void onComplete() {
            if (mFmAmProgramList == null) {
                Log.e(TAG, "Program list is null");
            }
            mProgramInfoAdapter.updateProgramInfos(mFmAmProgramList.toList()
                    .toArray(new RadioManager.ProgramInfo[0]));
        }
    }
}
