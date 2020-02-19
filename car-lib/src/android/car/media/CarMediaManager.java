/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.car.media;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.car.Car;
import android.car.CarManagerBase;
import android.content.ComponentName;
import android.os.IBinder;
import android.os.RemoteException;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * API for updating and receiving updates to the primary media source in the car.
 * @hide
 */
@SystemApi
public final class CarMediaManager extends CarManagerBase {

    public static final int MEDIA_SOURCE_MODE_PLAYBACK = 0;
    public static final int MEDIA_SOURCE_MODE_BROWSE = 1;

    /** @hide */
    @IntDef(prefix = { "MEDIA_SOURCE_MODE_" }, value = {
            MEDIA_SOURCE_MODE_PLAYBACK,
            MEDIA_SOURCE_MODE_BROWSE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MediaSourceMode {}

    private final ICarMedia mService;
    private Map<MediaSourceChangedListener, ICarMediaSourceListener> mCallbackMap = new HashMap();

    /**
     * Get an instance of the CarMediaManager.
     *
     * Should not be obtained directly by clients, use {@link Car#getCarManager(String)} instead.
     * @hide
     */
    public CarMediaManager(Car car, IBinder service) {
        super(car);
        mService = ICarMedia.Stub.asInterface(service);
    }

    /**
     * Listener for updates to the primary media source
     */
    public interface MediaSourceChangedListener {

        /**
         * Called when the primary media source is changed
         */
        void onMediaSourceChanged(@NonNull ComponentName componentName);
    }

    /**
     * Gets the currently active media source, or null if none exists
     * Requires android.Manifest.permission.MEDIA_CONTENT_CONTROL permission
     * @hide
     */
    @RequiresPermission(value = android.Manifest.permission.MEDIA_CONTENT_CONTROL)
    public synchronized ComponentName getMediaSource() {
        try {
            return mService.getMediaSource();
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, null);
        }
    }

    /**
     * Sets the currently active media source
     * Requires android.Manifest.permission.MEDIA_CONTENT_CONTROL permission
     * @hide
     */
    @RequiresPermission(value = android.Manifest.permission.MEDIA_CONTENT_CONTROL)
    public synchronized void setMediaSource(ComponentName componentName) {
        try {
            mService.setMediaSource(componentName);
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Register a callback that receives updates to the active media source.
     * Requires android.Manifest.permission.MEDIA_CONTENT_CONTROL permission
     * @hide
     */
    @RequiresPermission(value = android.Manifest.permission.MEDIA_CONTENT_CONTROL)
    public synchronized void registerMediaSourceListener(MediaSourceChangedListener callback) {
        try {
            ICarMediaSourceListener binderCallback = new ICarMediaSourceListener.Stub() {
                @Override
                public void onMediaSourceChanged(ComponentName componentName) {
                    callback.onMediaSourceChanged(componentName);
                }
            };
            mCallbackMap.put(callback, binderCallback);
            mService.registerMediaSourceListener(binderCallback);
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Unregister a callback that receives updates to the active media source.
     * Requires android.Manifest.permission.MEDIA_CONTENT_CONTROL permission
     * @hide
     */
    @RequiresPermission(value = android.Manifest.permission.MEDIA_CONTENT_CONTROL)
    public synchronized void unregisterMediaSourceListener(MediaSourceChangedListener callback) {
        try {
            ICarMediaSourceListener binderCallback = mCallbackMap.remove(callback);
            mService.unregisterMediaSourceListener(binderCallback);
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }
    /**
     * Gets the currently active media source for the provided mode
     */
    @RequiresPermission(value = android.Manifest.permission.MEDIA_CONTENT_CONTROL)
    public @NonNull ComponentName getMediaSource(@MediaSourceMode int mode) {
        // STUB
        return null;
    }

    /**
     * Sets the currently active media source for the provided mode
     */
    @RequiresPermission(value = android.Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void setMediaSource(@NonNull ComponentName componentName, @MediaSourceMode int mode) {
        // STUB
    }

    /**
     * Register a callback that receives updates to the active media source.
     */
    @RequiresPermission(value = android.Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void addMediaSourceListener(@NonNull MediaSourceChangedListener callback,
            @MediaSourceMode int mode) {
        // STUB
    }

    /**
     * Unregister a callback that receives updates to the active media source.
     */
    @RequiresPermission(value = android.Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void removeMediaSourceListener(@NonNull MediaSourceChangedListener callback,
            @MediaSourceMode int mode) {
        // STUB
    }

    /**
     * Retrieve a list of media sources, ordered by most recently used.
     */
    @RequiresPermission(value = android.Manifest.permission.MEDIA_CONTENT_CONTROL)
    public @NonNull List<ComponentName> getLastMediaSources(@MediaSourceMode int mode) {
        // STUB
        return null;
    }

    /** @hide */
    @Override
    public synchronized void onCarDisconnected() {
        // TODO(b/142733057) Fix synchronization to use separate mLock
        mCallbackMap.clear();
    }
}
