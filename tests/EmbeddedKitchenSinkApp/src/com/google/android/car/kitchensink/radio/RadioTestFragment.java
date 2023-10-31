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

import static android.media.AudioAttributes.USAGE_MEDIA;
import static android.media.AudioManager.GET_DEVICES_INPUTS;

import android.content.Context;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioTuner;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.HwAudioSource;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.car.kitchensink.R;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;
import java.util.List;

public final class RadioTestFragment extends Fragment {

    public static final String FRAGMENT_NAME = "Radio";

    private static final String TAG = RadioTestFragment.class.getSimpleName();
    private static final int INVALID_MODULR_ID = -1;

    private Handler mHandler;
    private Context mContext;

    private AudioAttributes mMusicAudioAttribute;
    private HwAudioSource mHwAudioSource;

    private RadioManager mRadioManager;
    private RadioManager.BandConfig mFmBandConfig;
    private RadioTuner mFmAmRadioTuner;

    private List<RadioManager.ModuleProperties> mModules;
    private int mFirstAmFmModuleId = INVALID_MODULR_ID;

    private TextView mOpenTunerWarning;
    private TextView mPlayingStatus;
    private TabLayout mTunerTabLayout;
    private TabLayoutMediator mTabLayoutMediator;
    private ViewPager2 mTunerViewPager;
    private RadioTunerTabAdapter mTunerTabAdapter;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = getContext();
        mHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle bundle) {
        Log.i(TAG, "onCreateView");
        View view = inflater.inflate(R.layout.radio, container, /* attachToRoot= */ false);

        RadioGroup tunerTypeSelection = view.findViewById(R.id.selection_tuner_type);
        RadioButton amFmTunerButton = view.findViewById(R.id.button_am_fm_type_tuner);
        Button openTunerButton = view.findViewById(R.id.button_radio_open);
        mOpenTunerWarning = view.findViewById(R.id.warning_open_tuner);

        mTunerTabLayout = view.findViewById(R.id.tabs_tadio_tuner);
        mTunerViewPager = view.findViewById(R.id.view_pager_radio_tuner);
        mTunerTabAdapter = new RadioTunerTabAdapter(this);
        mTunerViewPager.setAdapter(mTunerTabAdapter);

        initializeRadioPlayer();
        View hwAudioSourceNotFound = view.findViewById(R.id.hw_audio_source_not_found);
        View hwAudioSourceStart = view.findViewById(R.id.hw_audio_source_start);
        View hwAudioSourceStop = view.findViewById(R.id.hw_audio_source_stop);
        mPlayingStatus = view.findViewById(R.id.text_radio_playing_state);
        if (mHwAudioSource == null) {
            hwAudioSourceNotFound.setVisibility(View.VISIBLE);
            hwAudioSourceStart.setVisibility(View.GONE);
            hwAudioSourceStop.setVisibility(View.GONE);
        } else {
            hwAudioSourceNotFound.setVisibility(View.GONE);
            hwAudioSourceStart.setVisibility(View.VISIBLE);
            hwAudioSourceStop.setVisibility(View.VISIBLE);
            view.findViewById(R.id.hw_audio_source_start).setOnClickListener(
                    v -> handleHwAudioSourceStart());
            view.findViewById(R.id.hw_audio_source_stop).setOnClickListener(
                    v -> handleHwAudioSourceStop());
        }

        connectRadio();

        if (mFirstAmFmModuleId != INVALID_MODULR_ID) {
            amFmTunerButton.setVisibility(View.VISIBLE);
            tunerTypeSelection.check(R.id.button_am_fm_type_tuner);
        } else {
            openTunerButton.setVisibility(View.INVISIBLE);
        }

        openTunerButton.setOnClickListener(v -> {
            mOpenTunerWarning.setText(getString(R.string.empty));
            int selectedButtonId = tunerTypeSelection.getCheckedRadioButtonId();
            int moduleId;
            switch (selectedButtonId) {
                case R.id.button_am_fm_type_tuner:
                    moduleId = mFirstAmFmModuleId;
                    break;
                default:
                    return;
            }

            String tabTitle = getString(R.string.radio_fm_am_tuner);
            RadioTunerFragment tunerFragment;
            if (mFmAmRadioTuner != null) {
                mOpenTunerWarning.setText(getString(R.string.radio_warning,
                        "Tuner exists, cannot open a new one"));
                return;
            }
            tunerFragment = new RadioTunerFragment(mRadioManager,
                    mModules.get(moduleId).getId(), mFmBandConfig, mHandler,
                    new TunerListener(tabTitle));
            mFmAmRadioTuner = tunerFragment.getRadioTuner();
            if (mFmAmRadioTuner == null) {
                mOpenTunerWarning.setText(getString(R.string.radio_warning,
                        "Cannot open new tuner"));
                return;
            }

            mTunerTabAdapter.addFragment(tunerFragment, tabTitle);
            mTunerTabAdapter.notifyDataSetChanged();
            mTabLayoutMediator = new TabLayoutMediator(mTunerTabLayout, mTunerViewPager,
                    (tab, pos) -> tab.setText(mTunerTabAdapter.getPageTitle(pos)));
            mTabLayoutMediator.attach();
        });

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Log.i(TAG, "onDestroyView");
    }

    private void connectRadio() {
        mRadioManager = mContext.getSystemService(RadioManager.class);
        if (mRadioManager == null) {
            mOpenTunerWarning.setText(getString(R.string.radio_warning,
                    "RadioManager is not found"));
            return;
        }

        mModules = new ArrayList<>();
        int status = mRadioManager.listModules(mModules);
        if (status != RadioManager.STATUS_OK) {
            mOpenTunerWarning.setText(getString(R.string.radio_warning,
                    "Couldn't get radio module list"));
            return;
        }

        if (mModules.size() == 0) {
            mOpenTunerWarning.setText(getString(R.string.radio_warning,
                    "No radio modules on this device"));
            return;
        }

        RadioManager.AmBandDescriptor amBandDescriptor = null;
        RadioManager.FmBandDescriptor fmBandDescriptor = null;
        for (int moduleIndex = 0; moduleIndex < mModules.size(); moduleIndex++) {
            for (RadioManager.BandDescriptor band : mModules.get(moduleIndex).getBands()) {
                int bandType = band.getType();
                if (bandType == RadioManager.BAND_AM || bandType == RadioManager.BAND_AM_HD) {
                    amBandDescriptor = (RadioManager.AmBandDescriptor) band;
                }
                if (bandType == RadioManager.BAND_FM || bandType == RadioManager.BAND_FM_HD) {
                    fmBandDescriptor = (RadioManager.FmBandDescriptor) band;
                }
            }
            if (amBandDescriptor != null && fmBandDescriptor != null) {
                mFirstAmFmModuleId = moduleIndex;
                mFmBandConfig = new RadioManager.FmBandConfig.Builder(fmBandDescriptor).build();
                break;
            }
        }
    }

    private void initializeRadioPlayer() {
        mMusicAudioAttribute = new AudioAttributes.Builder().setUsage(USAGE_MEDIA).build();
        AudioDeviceInfo tuner = findTunerDevice(mContext);
        if (tuner != null) {
            mHwAudioSource = new HwAudioSource.Builder().setAudioAttributes(mMusicAudioAttribute)
                    .setAudioDeviceInfo(findTunerDevice(mContext)).build();
        }
    }

    private AudioDeviceInfo findTunerDevice(Context context) {
        AudioManager am = context.getSystemService(AudioManager.class);
        AudioDeviceInfo[] devices = am.getDevices(GET_DEVICES_INPUTS);
        for (AudioDeviceInfo device : devices) {
            if (device.getType() == AudioDeviceInfo.TYPE_FM_TUNER) {
                return device;
            }
        }
        return null;
    }

    private void handleHwAudioSourceStart() {
        if (mHwAudioSource != null && !mHwAudioSource.isPlaying()) {
            mHwAudioSource.start();
            mPlayingStatus.setText(getString(R.string.radio_play));
        }
    }

    private void handleHwAudioSourceStop() {
        if (mHwAudioSource != null && mHwAudioSource.isPlaying()) {
            mHwAudioSource.stop();
            mPlayingStatus.setText(getString(R.string.radio_stop));
        }
    }

    final class TunerListener {
        private final String mTunerTabTitle;
        TunerListener(String tunerTabTitle) {
            mTunerTabTitle = tunerTabTitle;
        }
        public void onTunerClosed() {
            if (mTunerTabAdapter != null) {
                mTunerTabAdapter.removeFragment(mTunerTabTitle);
                mTunerTabAdapter.notifyDataSetChanged();
                mFmAmRadioTuner = null;
                handleHwAudioSourceStop();
                mOpenTunerWarning.setText(getString(R.string.empty));
            }
        }

        public void onTunerPlay() {
            handleHwAudioSourceStart();
        }
    }
}
