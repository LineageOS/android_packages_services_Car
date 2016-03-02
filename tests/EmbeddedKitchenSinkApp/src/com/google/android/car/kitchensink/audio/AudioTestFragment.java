/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.google.android.car.kitchensink.audio;

import android.car.Car;
import android.car.CarAppContextManager;
import android.car.CarAppContextManager.AppContextChangeListener;
import android.car.CarNotConnectedException;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.google.android.car.kitchensink.CarEmulator;
import com.google.android.car.kitchensink.R;
import com.google.android.car.kitchensink.audio.AudioPlayer.PlayStateListener;

public class AudioTestFragment extends Fragment {
    private static final String TAG = "AudioTest";
    private static final boolean DBG = true;

    private AudioManager mAudioManager;
    private FocusHandler mAudioFocusHandler;
    private Button mNavPlayOnce;
    private Button mVrPlayOnce;
    private Button mSystemPlayOnce;
    private Button mMediaPlay;
    private Button mMediaPlayOnce;
    private Button mMediaStop;
    private Button mNavFocusStart;
    private Button mNavFocusEnd;
    private Button mVrFocusStart;
    private Button mVrFocusEnd;
    private Button mSpeakerPhoneOn;
    private Button mSpeakerPhoneOff;
    private Button mMicrophoneOn;
    private Button mMicrophoneOff;
    private ToggleButton mEnableMocking;
    private ToggleButton mRejectFocus;

    private final AudioPlayer mMusicPlayer;
    private final AudioPlayer mMusicPlayerShort;
    private final AudioPlayer mNavGuidancePlayer;
    private final AudioPlayer mVrPlayer;
    private final AudioPlayer mSystemPlayer;
    private final AudioPlayer[] mAllPlayers;

    private final Handler mHandler;
    private final Context mContext;

    private final Car mCar;
    private CarAppContextManager mAppContextManager;
    private CarEmulator mCarEmulator;

    public AudioTestFragment(Context context) {
        mContext = context;
        mMusicPlayer = new AudioPlayer(mContext, R.raw.john_harrison_with_the_wichita_state_university_chamber_players_05_summer_mvt_2_adagio,
                (new AudioAttributes.Builder()).
                    setUsage(AudioAttributes.USAGE_MEDIA).
                    setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        mMusicPlayerShort = new AudioPlayer(mContext, R.raw.ring_classic_01,
                (new AudioAttributes.Builder()).
                setUsage(AudioAttributes.USAGE_MEDIA).
                setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        mNavGuidancePlayer = new AudioPlayer(mContext, R.raw.turnright,
                (new AudioAttributes.Builder()).
                    setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).
                    setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        // no Usage for voice command yet.
        mVrPlayer = new AudioPlayer(mContext, R.raw.one2six,
                (new AudioAttributes.Builder()).
                setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).
                setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        mSystemPlayer = new AudioPlayer(mContext, R.raw.ring_classic_01,
                (new AudioAttributes.Builder()).
                setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).
                setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        mAllPlayers = new AudioPlayer[] {
                mMusicPlayer,
                mMusicPlayerShort,
                mNavGuidancePlayer,
                mVrPlayer,
                mSystemPlayer
        };
        mHandler = new Handler(Looper.getMainLooper());
        mCar = Car.createCar(mContext, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                mAppContextManager =
                        (CarAppContextManager) mCar.getCarManager(Car.APP_CONTEXT_SERVICE);
                mAppContextManager.registerContextListener(new AppContextChangeListener() {

                    @Override
                    public void onAppContextOwnershipLoss(int context) {
                    }

                    @Override
                    public void onAppContextChange(int activeContexts) {
                    }
                }, CarAppContextManager.APP_CONTEXT_NAVIGATION |
                CarAppContextManager.APP_CONTEXT_VOICE_COMMAND);
            }
            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        }, Looper.getMainLooper());
        mCar.connect();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle bundle) {
        Log.i(TAG, "onCreateView");
        View view = inflater.inflate(R.layout.audio, container, false);
        mAudioManager = (AudioManager) mContext.getSystemService(
                Context.AUDIO_SERVICE);
        mAudioFocusHandler = new FocusHandler(
                (RadioGroup) view.findViewById(R.id.button_focus_request_selection),
                (Button) view.findViewById(R.id.button_audio_focus_request),
                (TextView) view.findViewById(R.id.text_audio_focus_state));
        mMediaPlay = (Button) view.findViewById(R.id.button_media_play_start);
        mMediaPlay.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mMusicPlayer.start(false, true);
            }
        });
        mMediaPlayOnce = (Button) view.findViewById(R.id.button_media_play_once);
        mMediaPlayOnce.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mMusicPlayerShort.start(true, false);
                // play only for 1 sec and stop
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mMusicPlayerShort.stop();
                    }
                }, 1000);
            }
        });
        mMediaStop = (Button) view.findViewById(R.id.button_media_play_stop);
        mMediaStop.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mMusicPlayer.stop();
            }
        });
        mNavPlayOnce = (Button) view.findViewById(R.id.button_nav_play_once);
        mNavPlayOnce.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mAppContextManager == null) {
                    return;
                }
                if (DBG) {
                    Log.i(TAG, "Nav start");
                }
                if (!mNavGuidancePlayer.isPlaying()) {
                    mAppContextManager.setActiveContexts(
                            CarAppContextManager.APP_CONTEXT_NAVIGATION);
                    mNavGuidancePlayer.start(true, false, new PlayStateListener() {
                        @Override
                        public void onCompletion() {
                            mAppContextManager.resetActiveContexts(
                                    CarAppContextManager.APP_CONTEXT_NAVIGATION);
                        }
                    });
                }
            }
        });
        mVrPlayOnce = (Button) view.findViewById(R.id.button_vr_play_once);
        mVrPlayOnce.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mAppContextManager == null) {
                    return;
                }
                if (DBG) {
                    Log.i(TAG, "VR start");
                }
                mAppContextManager.setActiveContexts(
                        CarAppContextManager.APP_CONTEXT_VOICE_COMMAND);
                if (!mVrPlayer.isPlaying()) {
                    mVrPlayer.start(true, false, new PlayStateListener() {
                        @Override
                        public void onCompletion() {
                            mAppContextManager.resetActiveContexts(
                                    CarAppContextManager.APP_CONTEXT_VOICE_COMMAND);
                        }
                    });
                }
            }
        });
        mSystemPlayOnce = (Button) view.findViewById(R.id.button_system_play_once);
        mSystemPlayOnce.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (DBG) {
                    Log.i(TAG, "System start");
                }
                if (!mSystemPlayer.isPlaying()) {
                    // system sound played without focus
                    mSystemPlayer.start(false, false);
                }
            }
        });
        mNavFocusStart = (Button) view.findViewById(R.id.button_nav_start);
        mNavFocusStart.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mAppContextManager == null) {
                    return;
                }
                if (DBG) {
                    Log.i(TAG, "Nav focus request");
                }
                mAppContextManager.setActiveContexts(
                        CarAppContextManager.APP_CONTEXT_NAVIGATION);
            }
        });
        mNavFocusEnd = (Button) view.findViewById(R.id.button_nav_end);
        mNavFocusEnd.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mAppContextManager == null) {
                    return;
                }
                if (DBG) {
                    Log.i(TAG, "Nav focus release");
                }
                mAppContextManager.resetActiveContexts(
                        CarAppContextManager.APP_CONTEXT_NAVIGATION);
            }
        });
        mVrFocusStart = (Button) view.findViewById(R.id.button_vr_start);
        mVrFocusStart.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mAppContextManager == null) {
                    return;
                }
                if (DBG) {
                    Log.i(TAG, "VR request");
                }
                mAppContextManager.setActiveContexts(
                        CarAppContextManager.APP_CONTEXT_VOICE_COMMAND);
            }
        });
        mVrFocusEnd = (Button) view.findViewById(R.id.button_vr_end);
        mVrFocusEnd.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mAppContextManager == null) {
                    return;
                }
                if (DBG) {
                    Log.i(TAG, "VR request");
                }
                mAppContextManager.resetActiveContexts(
                        CarAppContextManager.APP_CONTEXT_VOICE_COMMAND);
            }
        });
        mSpeakerPhoneOn = (Button) view.findViewById(R.id.button_speaker_phone_on);
        mSpeakerPhoneOn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mAudioManager.setSpeakerphoneOn(true);
            }
        });
        mSpeakerPhoneOff = (Button) view.findViewById(R.id.button_speaker_phone_off);
        mSpeakerPhoneOff.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mAudioManager.setSpeakerphoneOn(false);
            }
        });
        mMicrophoneOn = (Button) view.findViewById(R.id.button_microphone_on);
        mMicrophoneOn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mAudioManager.setMicrophoneMute(false); // Turn the microphone on.
            }
        });
        mMicrophoneOff = (Button) view.findViewById(R.id.button_microphone_off);
        mMicrophoneOff.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mAudioManager.setMicrophoneMute(true); // Mute the microphone.
            }
        });


        mRejectFocus = (ToggleButton) view.findViewById(R.id.button_reject_audio_focus);
        mRejectFocus.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (mCarEmulator == null) {
                    return;
                }
                if (!mEnableMocking.isChecked()) {
                    return;
                }
                if (isChecked) {
                    mCarEmulator.setAudioFocusControl(true);
                } else {
                    mCarEmulator.setAudioFocusControl(false);
                }
            }
        });
        mRejectFocus.setActivated(false);
        mEnableMocking = (ToggleButton) view.findViewById(R.id.button_mock_audio);
        mEnableMocking.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (mCarEmulator == null) {
                    mCarEmulator = new CarEmulator(mCar);
                }
                if (isChecked) {
                    mRejectFocus.setActivated(true);
                    mCarEmulator.start();
                } else {
                    mRejectFocus.setActivated(false);
                    mCarEmulator.stop();
                    mCarEmulator = null;
                }
            }
        });
        return view;
    }

    @Override
    public void onDestroyView() {
        Log.i(TAG, "onDestroyView");
        super.onDestroyView();
        if (mCarEmulator != null) {
            mCarEmulator.setAudioFocusControl(false);
            mCarEmulator.stop();
        }
        for (AudioPlayer p : mAllPlayers) {
            p.stop();
        }
        if (mAudioFocusHandler != null) {
            mAudioFocusHandler.release();
            mAudioFocusHandler = null;
        }
        if (mAppContextManager != null) {
            mAppContextManager.resetActiveContexts(CarAppContextManager.APP_CONTEXT_NAVIGATION |
                    CarAppContextManager.APP_CONTEXT_VOICE_COMMAND);
        }
    }

    private class FocusHandler {
        private static final String AUDIO_FOCUS_STATE_GAIN = "gain";
        private static final String AUDIO_FOCUS_STATE_RELEASED_UNKNOWN = "released / unknown";

        private final RadioGroup mRequestSelection;
        private final TextView mText;
        private final AudioFocusListener mFocusListener;

        public FocusHandler(RadioGroup radioGroup, Button requestButton, TextView text) {
            mText = text;
            mRequestSelection = radioGroup;
            mRequestSelection.check(R.id.focus_gain);
            setFocusText(AUDIO_FOCUS_STATE_RELEASED_UNKNOWN);
            mFocusListener = new AudioFocusListener();
            requestButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    int selectedButtonId = mRequestSelection.getCheckedRadioButtonId();
                    int focusRequest = AudioManager.AUDIOFOCUS_GAIN;
                    if (selectedButtonId == R.id.focus_gain_transient_duck) {
                        focusRequest = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK;
                    } else if (selectedButtonId == R.id.focus_release) {
                        mAudioManager.abandonAudioFocus(mFocusListener);
                        setFocusText(AUDIO_FOCUS_STATE_RELEASED_UNKNOWN);
                        return;
                    }
                    int ret = mAudioManager.requestAudioFocus(mFocusListener,
                            AudioManager.STREAM_MUSIC, focusRequest);
                    Log.i(TAG, "requestAudioFocus returned " + ret);
                    if (ret == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        setFocusText(AUDIO_FOCUS_STATE_GAIN);
                    }
                }
            });
        }

        public void release() {
            abandonAudioFocus();
        }

        private void abandonAudioFocus() {
            if (DBG) {
                Log.i(TAG, "abandonAudioFocus");
            }
            mAudioManager.abandonAudioFocus(mFocusListener);
            setFocusText(AUDIO_FOCUS_STATE_RELEASED_UNKNOWN);
        }

        private void setFocusText(String msg) {
            mText.setText("focus state:" + msg);
        }

        private class AudioFocusListener implements AudioManager.OnAudioFocusChangeListener {
            @Override
            public void onAudioFocusChange(int focusChange) {
                Log.i(TAG, "onAudioFocusChange " + focusChange);
                if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                    setFocusText(AUDIO_FOCUS_STATE_GAIN);
                } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                    setFocusText("loss");
                } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    setFocusText("loss,transient");
                } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    setFocusText("loss,transient,duck");
                }
            }
        }
    }
}
