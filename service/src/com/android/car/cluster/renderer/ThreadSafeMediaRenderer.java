/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.car.cluster.renderer;

import android.car.cluster.renderer.MediaRenderer;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

/**
 * A wrapper over {@link MediaRenderer} that runs all its methods in the context of provided looper.
 * It is guaranteed that all calls will be invoked in order they were called.
 */
public class ThreadSafeMediaRenderer extends MediaRenderer {
    private final Handler mHandler;

    private final static int MSG_PLAYBACK_STATE_CHANGED = 1;
    private final static int MSG_METADATA_CHANGED = 2;

    public static MediaRenderer createFor(Looper looper, MediaRenderer renderer) {
        return renderer != null ? new ThreadSafeMediaRenderer(renderer, looper) : null;
    }

    private ThreadSafeMediaRenderer(MediaRenderer mediaRenderer, Looper looper) {
        mHandler = new MediaRendererHandler(looper, mediaRenderer);
    }

    @Override
    public void onPlaybackStateChanged(PlaybackState playbackState) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_PLAYBACK_STATE_CHANGED, playbackState));
    }

    @Override
    public void onMetadataChanged(MediaMetadata metadata) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_METADATA_CHANGED, metadata));
    }

    private static class MediaRendererHandler extends RendererHandler<MediaRenderer> {

        MediaRendererHandler(Looper looper, MediaRenderer renderer) {
            super(looper, renderer);
        }

        @Override
        public void handleMessage(Message msg, MediaRenderer renderer) {
            switch (msg.what) {
                case MSG_PLAYBACK_STATE_CHANGED:
                    renderer.onPlaybackStateChanged((PlaybackState) msg.obj);
                    break;
                case MSG_METADATA_CHANGED:
                    renderer.onMetadataChanged((MediaMetadata) msg.obj);
                    break;
                default:
                    throw new IllegalArgumentException("Msg: " + msg.what);
            }
        }
    }
}