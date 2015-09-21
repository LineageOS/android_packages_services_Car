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
package com.android.support.car.apitest;

import android.content.Context;
import android.media.AudioManager;
import android.test.AndroidTestCase;

public class CarAudioTest extends AndroidTestCase {
    public void testFocusChange() throws Exception {
        //TODO evolve this into full features test using testmanager / mocking.
        AudioManager am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        AudioFocusListener lister1 = new AudioFocusListener();
        AudioFocusListener lister2 = new AudioFocusListener();
        AudioFocusListener lister3 = new AudioFocusListener();
        int res = am.requestAudioFocus(lister1,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);
        Thread.sleep(1000);
        res = am.requestAudioFocus(lister2,
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        Thread.sleep(1000);
        res = am.requestAudioFocus(lister3,
                AudioManager.STREAM_TTS,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        Thread.sleep(1000);
        am.abandonAudioFocus(lister3);
        Thread.sleep(1000);
        am.abandonAudioFocus(lister2);
        Thread.sleep(1000);
        am.abandonAudioFocus(lister1);
        Thread.sleep(1000);
        res = am.requestAudioFocus(lister1,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);
        Thread.sleep(1000);
        res = am.requestAudioFocus(lister2,
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        Thread.sleep(1000);
        res = am.requestAudioFocus(lister3,
                AudioManager.STREAM_TTS,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        Thread.sleep(1000);
        am.abandonAudioFocus(lister2);
        Thread.sleep(1000);
        am.abandonAudioFocus(lister3);
        Thread.sleep(1000);
        am.abandonAudioFocus(lister1);
    }

    private class AudioFocusListener implements AudioManager.OnAudioFocusChangeListener {

        @Override
        public void onAudioFocusChange(int arg0) {
            // TODO Auto-generated method stub
        }
    }
}
