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
package com.android.car.cluster;

import android.car.cluster.renderer.MediaRenderer;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.MediaSessionManager.OnActiveSessionsChangedListener;
import android.media.session.PlaybackState;
import android.os.Looper;
import android.util.Log;

import com.android.car.CarLog;
import com.android.car.CarServiceBase;
import com.android.car.cluster.InstrumentClusterService.RendererInitializationListener;
import com.android.car.cluster.renderer.ThreadSafeMediaRenderer;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Reports current media status to instrument cluster renderer.
 */
public class MediaStatusService implements CarServiceBase, RendererInitializationListener {

    private final static String TAG = CarLog.TAG_CLUSTER
            + "." + MediaStatusService.class.getSimpleName();

    private final Context mContext;
    private final MediaListener mMediaListener;
    private volatile MediaRenderer mMediaRenderer;
    private InstrumentClusterService mInstrumentClusterService;
    private MediaController mPrimaryMediaController;
    private OnActiveSessionsChangedListener mActiveSessionsChangedListener;
    private MediaSessionManager mMediaSessionManager;

    public MediaStatusService(Context context, InstrumentClusterService instrumentClusterService) {
        mContext = context;
        mInstrumentClusterService = instrumentClusterService;
        instrumentClusterService.registerListener(this);
        mMediaListener = new MediaListener(MediaStatusService.this);
    }

    @Override
    public void init() {
        Log.d(TAG, "init");

        if (!mInstrumentClusterService.isInstrumentClusterAvailable()) {
            Log.d(TAG, "Instrument cluster is not available.");
            return;
        }

        mActiveSessionsChangedListener = new OnActiveSessionsChangedListener() {
            @Override
            public void onActiveSessionsChanged(List<MediaController> controllers) {
                MediaStatusService.this.onActiveSessionsChanged(controllers);
            }
        };
        mMediaSessionManager = mContext.getSystemService(MediaSessionManager.class);
        mMediaSessionManager.addOnActiveSessionsChangedListener(
                mActiveSessionsChangedListener, null);

        onActiveSessionsChanged(mMediaSessionManager.getActiveSessions(null));
    }

    private void onActiveSessionsChanged(List<MediaController> controllers) {
        Log.d(TAG, "onActiveSessionsChanged, controllers found:  " + controllers.size());
        MediaController newPrimaryController = null;
        if (controllers.size() > 0) {
            newPrimaryController = controllers.get(0);
            if (mPrimaryMediaController == newPrimaryController) {
                // Primary media controller has not been changed.
                return;
            }
        }

        releasePrimaryMediaController();

        if (newPrimaryController != null) {
            mPrimaryMediaController = newPrimaryController;
            mPrimaryMediaController.registerCallback(mMediaListener);
        }
        updateRendererMediaStatusIfAvailable();

        for (MediaController m : controllers) {
            Log.d(TAG, m + ": " + m.getPackageName());
        }
    }

    @Override
    public void release() {
        releasePrimaryMediaController();
        if (mActiveSessionsChangedListener != null) {
            mMediaSessionManager.removeOnActiveSessionsChangedListener(
                    mActiveSessionsChangedListener);
            mActiveSessionsChangedListener = null;
        }
        mMediaSessionManager = null;
    }

    private void releasePrimaryMediaController() {
        if (mPrimaryMediaController != null) {
            mPrimaryMediaController.unregisterCallback(mMediaListener);
            mPrimaryMediaController = null;
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        // TODO
    }

    @Override
    public void onRendererInitSucceeded() {
        Log.d(TAG, "onRendererInitSucceeded");
        mMediaRenderer = ThreadSafeMediaRenderer.createFor(
                Looper.getMainLooper(),
                mInstrumentClusterService.getMediaRenderer());

        updateRendererMediaStatusIfAvailable();
    }

    private void updateRendererMediaStatusIfAvailable() {
        if (isRendererAvailable()) {
            mMediaRenderer.onMetadataChanged(
                    mPrimaryMediaController == null ? null : mPrimaryMediaController.getMetadata());
            mMediaRenderer.onPlaybackStateChanged(
                    mPrimaryMediaController == null
                    ? null : mPrimaryMediaController.getPlaybackState());
        }
    }

    private boolean isRendererAvailable() {
        boolean available = mMediaRenderer != null;
        if (!available) {
            Log.w(TAG, "Media renderer is not available.");
        }
        return available;
    }

    private void onPlaybackStateChanged(PlaybackState state) {
        if (isRendererAvailable()) {
            mMediaRenderer.onPlaybackStateChanged(state);
        }
    }

    private void onMetadataChanged(MediaMetadata metadata) {
        if (isRendererAvailable()) {
            mMediaRenderer.onMetadataChanged(metadata);
        }
    }

    private static class MediaListener extends MediaController.Callback {
        private final WeakReference<MediaStatusService> mServiceRef;

        MediaListener(MediaStatusService service) {
            mServiceRef = new WeakReference<>(service);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            MediaStatusService service = mServiceRef.get();
            if (service != null) {
                service.onPlaybackStateChanged(state);
            }
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            MediaStatusService service = mServiceRef.get();
            if (service != null) {
                service.onMetadataChanged(metadata);
            }
        }
    }
}
