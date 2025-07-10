/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.car.testapi;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.car.media.CarMediaManager;
import android.car.media.ICarMedia;
import android.car.media.ICarMediaSourceListener;
import android.content.ComponentName;
import android.os.RemoteException;
import android.util.SparseArray;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fake {@link ICarMedia.Stub} to provide to a {@link CarMediaManager} for testing. It has a basic
 * implementation for handling getting and setting mediaSources, but doesn't differentiate between
 * multiple users.
 */
public class FakeCarMediaService extends ICarMedia.Stub implements CarMediaController {

    private final SparseArray<Deque<ComponentName>> mSources = new SparseArray<>();
    private final HashMultimap<Integer, ICarMediaSourceListener> mCallbacksMap =
            HashMultimap.create();
    @Nullable private ComponentName mDefaultSource = null;
    private boolean mIsIndependentPlaybackConfig = false;

    public FakeCarMediaService() {}

    @Override
    @Nullable
    public ComponentName getMediaSource(@CarMediaManager.MediaSourceMode int mode,
            @UserIdInt int userId) {
        Deque<ComponentName> deque = mSources.get(mode, /* valueIfKeyNotFound= */ null);
        if (deque == null || deque.isEmpty()) {
            return mDefaultSource;
        }
        return deque.peekFirst();
    }

    @Override
    public void setMediaSource(@NonNull ComponentName mediaSource,
            @CarMediaManager.MediaSourceMode int mode, @UserIdInt int userId)
            throws RemoteException {
        Deque<ComponentName> deque = mSources.get(mode, /* valueIfKeyNotFound= */ null);
        if (deque == null) {
            deque = new ArrayDeque<>();
            mSources.put(mode, deque);
        }
        deque.addFirst(mediaSource);
        Set<ICarMediaSourceListener> callbacks = mCallbacksMap.get(mode);
        if (callbacks == null) {
            return;
        }
        for (ICarMediaSourceListener callback : callbacks) {
            callback.onMediaSourceChanged(mediaSource);
        }
    }

    @Override
    public void registerMediaSourceListener(@NonNull ICarMediaSourceListener callback,
            @CarMediaManager.MediaSourceMode int mode,@UserIdInt int userId) {
        mCallbacksMap.put(mode, callback);
    }

    @Override
    public void unregisterMediaSourceListener(@NonNull ICarMediaSourceListener callback,
            @CarMediaManager.MediaSourceMode int mode, @UserIdInt int userId) {
        boolean removed = mCallbacksMap.remove(mode, callback);
        // Simulate the real implementation and throw an exception if the listener is not
        // registered.
        if (!removed) {
            throw new NullPointerException("Listener not registered");
        }
    }

    @Override
    public List<ComponentName> getLastMediaSources(@CarMediaManager.MediaSourceMode int mode,
            @UserIdInt int userId) {
        Deque<ComponentName> deque = mSources.get(mode, /* valueIfKeyNotFound= */ null);
        ImmutableList.Builder<ComponentName> builder = ImmutableList.builder();
        if (deque != null) {
            builder.addAll(deque);
        }
        if (mDefaultSource != null && (deque == null || !deque.contains(mDefaultSource))) {
            builder.add(mDefaultSource);
        }
        return builder.build();
    }

    @Override
    public boolean isIndependentPlaybackConfig(@UserIdInt int userId) {
        return mIsIndependentPlaybackConfig;
    }

    @Override
    public void setIndependentPlaybackConfig(boolean independent, @UserIdInt int userId) {
        mIsIndependentPlaybackConfig = independent;
    }

    @Override
    public void setDefaultMediaSource(@Nullable ComponentName mediaSource, @UserIdInt int userId) {
        mDefaultSource = mediaSource;
    }

    @Override
    public void clearLastMediaSources(@CarMediaManager.MediaSourceMode int mode,
            @UserIdInt int userId) {
        Deque<ComponentName> deque = mSources.get(mode, /* valueIfKeyNotFound= */ null);
        if (deque == null) {
            return;
        }
        deque.clear();
    }

    @Override
    public void addLastMediaSources(@CarMediaManager.MediaSourceMode int mode,
            @NonNull List<ComponentName> mediaSources, @UserIdInt int userId) {
        Deque<ComponentName> deque = mSources.get(mode, /* valueIfKeyNotFound= */ null);
        if (deque == null) {
            deque = new ArrayDeque<>();
            mSources.put(mode, deque);
        }
        deque.addAll(mediaSources);
    }

    @Override
    public Set<ICarMediaSourceListener> getRegisteredMediaSourceListener(int mode, int userId) {
        Set<ICarMediaSourceListener> listeners = mCallbacksMap.get(mode);
        if (listeners == null) {
            return new HashSet<>();
        }
        return new HashSet<>(listeners);
    }
}
