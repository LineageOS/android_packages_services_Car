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
import android.hardware.radio.Flags;
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
import android.widget.Switch;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.android.car.broadcastradio.support.platform.ProgramSelectorExt;

import com.google.android.car.kitchensink.R;

import java.util.Objects;

public final class RadioTunerFragment extends Fragment {

    private static final String TAG = RadioTunerFragment.class.getSimpleName();
    private static final CharSequence NULL_TUNER_WARNING = "Tuner cannot be null";
    private static final CharSequence TUNING_TEXT = "Tuning...";
    private static final CharSequence TUNING_COMPLETION_TEXT = "Tuning completes";

    private final RadioTuner mRadioTuner;
    private final RadioTestFragment.TunerListener mListener;
    private final ProgramList mFmAmProgramList;
    private int mLastFmFrequency;
    private int mLastAmFrequency;
    private boolean mIsFmBand = true;
    private boolean mViewCreated = false;

    private ProgramInfoAdapter mProgramInfoAdapter;

    private RadioButton mFmRadioButton;
    private RadioButton mAmRadioButton;
    private Switch mFmHdSwitch;
    private Switch mAmHdSwitch;
    private EditText mFrequencyInput;
    private RadioGroup mFmAmBandSelection;
    private CheckBox mSeekChannelCheckBox;
    private TextView mTuningTextView;
    private TextView mCurrentStationTextView;
    private TextView mCurrentChannelTextView;
    private TextView mCurrentSongTitleTextView;
    private TextView mCurrentArtistTextView;

    RadioTunerFragment(RadioManager radioManager, int moduleId,
                       RadioManager.BandConfig fmBandConfig, RadioManager.BandConfig amBandConfig,
                       Handler handler, RadioTestFragment.TunerListener tunerListener) {
        mLastFmFrequency = fmBandConfig.getLowerLimit();
        mLastAmFrequency = amBandConfig.getLowerLimit();
        mRadioTuner = radioManager.openTuner(moduleId, fmBandConfig, /* withAudio= */ true,
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
        mFmHdSwitch = view.findViewById(R.id.toggle_fm_hd_state);
        mAmHdSwitch = view.findViewById(R.id.toggle_am_hd_state);
        mTuningTextView = view.findViewById(R.id.text_tuning_status);
        mFrequencyInput = view.findViewById(R.id.input_am_fm_frequency);
        mFmAmBandSelection = view.findViewById(R.id.button_fm_am_selection);
        mFmRadioButton = view.findViewById(R.id.button_radio_fm);
        mAmRadioButton = view.findViewById(R.id.button_radio_am);
        Button tuneButton = view.findViewById(R.id.button_radio_tune);
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
        if (Flags.hdRadioImproved()) {
            mAmHdSwitch.setVisibility(View.VISIBLE);
            view.findViewById(R.id.text_am_hd_state).setVisibility(View.VISIBLE);
            mFmHdSwitch.setChecked(!mRadioTuner.isConfigFlagSet(
                    RadioManager.CONFIG_FORCE_ANALOG_FM));
            mAmHdSwitch.setChecked(!mRadioTuner.isConfigFlagSet(
                    RadioManager.CONFIG_FORCE_ANALOG_AM));
        } else {
            mFmHdSwitch.setChecked(!mRadioTuner.isConfigFlagSet(RadioManager.CONFIG_FORCE_ANALOG));
        }
        mFmHdSwitch.setOnCheckedChangeListener(
                (buttonView, isChecked) -> handleHdEnable(/* isFm= */ true, isChecked));
        mAmHdSwitch.setOnCheckedChangeListener(
                (buttonView, isChecked) -> handleHdEnable(/* isFm= */ false, isChecked));
        tuneButton.setOnClickListener((v) -> tuneToInputStation());
        mFmRadioButton.setOnClickListener((v) -> handleBandSwitching(/* isFm= */ true));
        mAmRadioButton.setOnClickListener((v) -> handleBandSwitching(/* isFm= */ false));
        if (mIsFmBand) {
            mFmRadioButton.setChecked(true);
            mFrequencyInput.setText(ProgramSelectorExt.formatAmFmFrequency(mLastFmFrequency,
                    ProgramSelectorExt.NAME_NO_MODULATION));
        } else {
            mAmRadioButton.setChecked(true);
            mFrequencyInput.setText(ProgramSelectorExt.formatAmFmFrequency(mLastAmFrequency,
                    ProgramSelectorExt.NAME_NO_MODULATION));
        }
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
            mTuningTextView.setText(getString(R.string.radio_error, NULL_TUNER_WARNING));
            return;
        }
        mTuningTextView.setText(getString(R.string.radio_status, TUNING_TEXT));
        try {
            mRadioTuner.tune(sel);
        } catch (Exception e) {
            mTuningTextView.setText(getString(R.string.radio_error, e.getMessage()));
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
                    mTuningTextView.setText(getString(R.string.radio_error,
                            "Unsupported input selector type"));
                    return;
            }
        } catch (Exception e) {
            mTuningTextView.setText(getString(R.string.radio_error, e.getMessage()));
            return;
        }
        handleTune(sel);
    }

    private void handleBandSwitching(boolean isFm) {
        if (isFm) {
            mFrequencyInput.setText(ProgramSelectorExt.formatAmFmFrequency(mLastFmFrequency,
                    ProgramSelectorExt.NAME_NO_MODULATION));
        } else {
            mFrequencyInput.setText(ProgramSelectorExt.formatAmFmFrequency(mLastAmFrequency,
                    ProgramSelectorExt.NAME_NO_MODULATION));
        }
        mIsFmBand = isFm;
        ProgramSelector initialSel = isFm ? ProgramSelector.createAmFmSelector(
                RadioManager.BAND_FM, mLastFmFrequency)
                : ProgramSelector.createAmFmSelector(RadioManager.BAND_AM, mLastAmFrequency);
        handleTune(initialSel);
    }

    private void handleStep(int direction) {
        if (mRadioTuner == null) {
            mTuningTextView.setText(getString(R.string.radio_error, NULL_TUNER_WARNING));
            return;
        }
        mTuningTextView.setText(getString(R.string.radio_status, TUNING_TEXT));
        try {
            mRadioTuner.step(direction, /* skipSubChannel= */ false);
        } catch (Exception e) {
            mTuningTextView.setText(getString(R.string.radio_error, e.getMessage()));
        }
        mListener.onTunerPlay();
    }

    private void handleSeek(int direction) {
        if (mRadioTuner == null) {
            mTuningTextView.setText(getString(R.string.radio_error, NULL_TUNER_WARNING));
            return;
        }
        mTuningTextView.setText(getString(R.string.radio_status, TUNING_TEXT));
        try {
            mRadioTuner.seek(direction, mSeekChannelCheckBox.isChecked());
        } catch (Exception e) {
            mTuningTextView.setText(getString(R.string.radio_error, e.getMessage()));
        }
        mListener.onTunerPlay();
    }

    private void handleClose() {
        if (mRadioTuner == null) {
            mTuningTextView.setText(getString(R.string.radio_error, NULL_TUNER_WARNING));
            return;
        }
        mTuningTextView.setText(getString(R.string.empty));
        try {
            mRadioTuner.close();
            mListener.onTunerClosed();
        } catch (Exception e) {
            mTuningTextView.setText(getString(R.string.radio_error, e.getMessage()));
        }
    }

    private void handleCancel() {
        if (mRadioTuner == null) {
            mTuningTextView.setText(getString(R.string.radio_error, NULL_TUNER_WARNING));
            return;
        }
        try {
            mRadioTuner.cancel();
        } catch (Exception e) {
            mTuningTextView.setText(getString(R.string.radio_error, e.getMessage()));
        }
        mTuningTextView.setText(getString(R.string.radio_status, "Canceled"));
    }

    private void handleHdEnable(boolean isFm, boolean hdEnabled) {
        if (mRadioTuner == null) {
            mTuningTextView.setText(getString(R.string.radio_error, NULL_TUNER_WARNING));
            return;
        }
        mTuningTextView.setText(getString(R.string.empty));
        int configFlag;
        if (Flags.hdRadioImproved()) {
            configFlag = isFm ? RadioManager.CONFIG_FORCE_ANALOG_FM
                    : RadioManager.CONFIG_FORCE_ANALOG_AM;
        } else {
            configFlag = RadioManager.CONFIG_FORCE_ANALOG;
        }
        try {
            mRadioTuner.setConfigFlag(configFlag, !hdEnabled);
        } catch (Exception e) {
            mTuningTextView.setText(getString(R.string.radio_error, e.getMessage()));
        }
    }

    private void setTuningStatus(RadioManager.ProgramInfo info) {
        if (!mViewCreated) {
            return;
        }
        if (info == null) {
            mTuningTextView.setText(getString(R.string.radio_error, "Program info is null"));
            return;
        } else if (info.getSelector().getPrimaryId().getType()
                != ProgramSelector.IDENTIFIER_TYPE_HD_STATION_ID_EXT) {
            if (mTuningTextView.getText().toString().contains(TUNING_TEXT)) {
                mTuningTextView.setText(getString(R.string.radio_status, TUNING_COMPLETION_TEXT));
            }
            return;
        }
        if (Flags.hdRadioImproved()) {
            if (info.isSignalAcquired()) {
                if (!info.isHdSisAvailable()) {
                    mTuningTextView.setText(getString(R.string.radio_status,
                            "Signal is acquired"));
                } else {
                    if (!info.isHdAudioAvailable()) {
                        mTuningTextView.setText(getString(R.string.radio_status,
                                "HD SIS is available"));
                    } else {
                        mTuningTextView.setText(getString(R.string.radio_status,
                                TUNING_COMPLETION_TEXT));
                    }
                }
            }
        } else {
            mTuningTextView.setText(getString(R.string.radio_status, TUNING_COMPLETION_TEXT));
        }
    }

    private void setProgramInfo(RadioManager.ProgramInfo info) {
        String channelText = null;
        if (info != null) {
            int primaryIdType = info.getSelector().getPrimaryId().getType();
            if (primaryIdType == ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY
                    || primaryIdType == ProgramSelector.IDENTIFIER_TYPE_HD_STATION_ID_EXT) {
                channelText = ProgramSelectorExt.getDisplayName(info.getSelector(), /* flags= */ 0);
                long amFmFrequency = ProgramSelectorExt.getFrequency(info.getSelector());
                mIsFmBand = ProgramSelectorExt.isFmFrequency(amFmFrequency);
                if (mIsFmBand) {
                    mLastFmFrequency = (int) amFmFrequency;
                } else if (ProgramSelectorExt.isAmFrequency(amFmFrequency)) {
                    mLastAmFrequency = (int) amFmFrequency;
                }
            }
        }
        if (!mViewCreated) {
            return;
        }
        if (mIsFmBand) {
            if (!mFmRadioButton.isChecked()) {
                mFmRadioButton.setChecked(true);
            }
        } else {
            if (!mAmRadioButton.isChecked()) {
                mAmRadioButton.setChecked(true);
            }
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
            setProgramInfo(info);
            setTuningStatus(info);
        }

        @Override
        public void onConfigFlagUpdated(int flag, boolean value) {
            if (!mViewCreated) {
                return;
            }
            if (flag == RadioManager.CONFIG_FORCE_ANALOG) {
                mFmHdSwitch.setChecked(!value);
                mAmHdSwitch.setChecked(!value);
            } else if (Flags.hdRadioImproved()) {
                if (flag == RadioManager.CONFIG_FORCE_ANALOG_FM) {
                    mFmHdSwitch.setChecked(!value);
                } else if (flag == RadioManager.CONFIG_FORCE_ANALOG_AM) {
                    mAmHdSwitch.setChecked(!value);
                }
            }
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
            mTuningTextView.setText(getString(R.string.radio_error, warning));
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
