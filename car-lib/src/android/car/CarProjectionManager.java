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

package android.car;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.bluetooth.BluetoothDevice;
import android.car.projection.ProjectionOptions;
import android.car.projection.ProjectionStatus;
import android.car.projection.ProjectionStatus.ProjectionState;
import android.content.Intent;
import android.net.wifi.WifiConfiguration;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * CarProjectionManager allows applications implementing projection to register/unregister itself
 * with projection manager, listen for voice notification.
 *
 * A client must have {@link Car#PERMISSION_CAR_PROJECTION} permission in order to access this
 * manager.
 *
 * @hide
 */
@SystemApi
public final class CarProjectionManager implements CarManagerBase {
    private static final String TAG = CarProjectionManager.class.getSimpleName();

    private final Binder mToken = new Binder();
    private final Object mLock = new Object();

    /**
     * Listener to get projected notifications.
     *
     * Currently only voice search request is supported.
     */
    public interface CarProjectionListener {
        /**
         * Voice search was requested by the user.
         */
        void onVoiceAssistantRequest(boolean fromLongPress);
    }

    /**
     * Flag for voice search request.
     */
    public static final int PROJECTION_VOICE_SEARCH = 0x1;
    /**
     * Flag for long press voice search request.
     */
    public static final int PROJECTION_LONG_PRESS_VOICE_SEARCH = 0x2;

    /** @hide */
    public static final int PROJECTION_AP_STARTED = 0;
    /** @hide */
    public static final int PROJECTION_AP_STOPPED = 1;
    /** @hide */
    public static final int PROJECTION_AP_FAILED = 2;

    private final ICarProjection mService;
    private final Handler mHandler;
    private final ICarProjectionCallbackImpl mBinderListener;

    private CarProjectionListener mListener;
    private int mVoiceSearchFilter;

    private ProjectionAccessPointCallbackProxy mProjectionAccessPointCallbackProxy;

    private final Set<ProjectionStatusListener> mProjectionStatusListeners = new LinkedHashSet<>();
    private CarProjectionStatusListenerImpl mCarProjectionStatusListener;

    // Only one access point proxy object per process.
    private static final IBinder mAccessPointProxyToken = new Binder();

    /**
     * Interface to receive for projection status updates.
     */
    public interface ProjectionStatusListener {
        /**
         * This method gets invoked if projection status has been changed.
         *
         * @param state - current projection state
         * @param packageName - if projection is currently running either in the foreground or
         *                      in the background this argument will contain its package name
         * @param details - contains detailed information about all currently registered projection
         *                  receivers.
         */
        void onProjectionStatusChanged(@ProjectionState int state, @Nullable String packageName,
                @NonNull List<ProjectionStatus> details);
    }

    /**
     * @hide
     */
    public CarProjectionManager(IBinder service, Handler handler) {
        mService = ICarProjection.Stub.asInterface(service);
        mHandler = handler;
        mBinderListener = new ICarProjectionCallbackImpl(this);
    }

    /**
     * Compatibility with previous APIs due to typo
     * @hide
     */
    public void regsiterProjectionListener(CarProjectionListener listener, int voiceSearchFilter) {
        registerProjectionListener(listener, voiceSearchFilter);
    }

    /**
     * Register listener to monitor projection. Only one listener can be registered and
     * registering multiple times will lead into only the last listener to be active.
     * @param listener
     * @param voiceSearchFilter Flags of voice search requests to get notification.
     */
    @RequiresPermission(Car.PERMISSION_CAR_PROJECTION)
    public void registerProjectionListener(@NonNull CarProjectionListener listener,
            int voiceSearchFilter) {
        Preconditions.checkNotNull(listener, "listener cannot be null");
        synchronized (mLock) {
            if (mListener == null || mVoiceSearchFilter != voiceSearchFilter) {
                try {
                    mService.registerProjectionListener(mBinderListener, voiceSearchFilter);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
            mListener = listener;
            mVoiceSearchFilter = voiceSearchFilter;
        }
    }

    /**
     * Compatibility with previous APIs due to typo
     * @hide
     */
    public void unregsiterProjectionListener() {
       unregisterProjectionListener();
    }

    /**
     * Unregister listener and stop listening projection events.
     */
    @RequiresPermission(Car.PERMISSION_CAR_PROJECTION)
    public void unregisterProjectionListener() {
        synchronized (mLock) {
            try {
                mService.unregisterProjectionListener(mBinderListener);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mListener = null;
            mVoiceSearchFilter = 0;
        }
    }

    /**
     * Registers projection runner on projection start with projection service
     * to create reverse binding.
     * @param serviceIntent
     */
    @RequiresPermission(Car.PERMISSION_CAR_PROJECTION)
    public void registerProjectionRunner(@NonNull Intent serviceIntent) {
        Preconditions.checkNotNull("serviceIntent cannot be null");
        synchronized (mLock) {
            try {
                mService.registerProjectionRunner(serviceIntent);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Unregisters projection runner on projection stop with projection service to create
     * reverse binding.
     * @param serviceIntent
     */
    @RequiresPermission(Car.PERMISSION_CAR_PROJECTION)
    public void unregisterProjectionRunner(@NonNull Intent serviceIntent) {
        Preconditions.checkNotNull("serviceIntent cannot be null");
        synchronized (mLock) {
            try {
                mService.unregisterProjectionRunner(serviceIntent);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /** @hide */
    @Override
    public void onCarDisconnected() {
        // nothing to do
    }

    /**
     * Request to start Wi-Fi access point if it hasn't been started yet for wireless projection
     * receiver app.
     *
     * <p>A process can have only one request to start an access point, subsequent call of this
     * method will invalidate previous calls.
     *
     * @param callback to receive notifications when access point status changed for the request
     */
    @RequiresPermission(Car.PERMISSION_CAR_PROJECTION)
    public void startProjectionAccessPoint(@NonNull ProjectionAccessPointCallback callback) {
        Preconditions.checkNotNull(callback, "callback cannot be null");
        synchronized (mLock) {
            Looper looper = mHandler.getLooper();
            ProjectionAccessPointCallbackProxy proxy =
                    new ProjectionAccessPointCallbackProxy(this, looper, callback);
            try {
                mService.startProjectionAccessPoint(proxy.getMessenger(), mAccessPointProxyToken);
                mProjectionAccessPointCallbackProxy = proxy;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns a list of available Wi-Fi channels. A channel is specified as frequency in MHz,
     * e.g. channel 1 will be represented as 2412 in the list.
     *
     * @param band one of the values from {@code android.net.wifi.WifiScanner#WIFI_BAND_*}
     */
    @RequiresPermission(Car.PERMISSION_CAR_PROJECTION)
    public @NonNull List<Integer> getAvailableWifiChannels(int band) {
        try {
            int[] channels = mService.getAvailableWifiChannels(band);
            List<Integer> channelList = new ArrayList<>(channels.length);
            for (int v : channels) {
                channelList.add(v);
            }
            return channelList;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Stop Wi-Fi Access Point for wireless projection receiver app.
     */
    @RequiresPermission(Car.PERMISSION_CAR_PROJECTION)
    public void stopProjectionAccessPoint() {
        ProjectionAccessPointCallbackProxy proxy;
        synchronized (mLock) {
            proxy = mProjectionAccessPointCallbackProxy;
            mProjectionAccessPointCallbackProxy = null;
        }
        if (proxy == null) {
            return;
        }

        try {
            mService.stopProjectionAccessPoint(mAccessPointProxyToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request to disconnect the given profile on the given device, and prevent it from reconnecting
     * until either the request is released, or the process owning the given token dies.
     *
     * @param device  The device on which to inhibit a profile.
     * @param profile The {@link android.bluetooth.BluetoothProfile} to inhibit.
     * @param token   A {@link IBinder} to be used as an identity for the request. If the process
     *                owning the token dies, the request will automatically be released.
     * @return True if the profile was successfully inhibited, false if an error occurred.
     */
    @RequiresPermission(Car.PERMISSION_CAR_PROJECTION)
    public boolean requestBluetoothProfileInhibit(
            @NonNull BluetoothDevice device, int profile, @NonNull IBinder token) {
        Preconditions.checkNotNull(device, "device cannot be null");
        Preconditions.checkNotNull(token, "token cannot be null");
        try {
            return mService.requestBluetoothProfileInhibit(device, profile, token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Release an inhibit request made by {@link #requestBluetoothProfileInhibit}, and reconnect the
     * profile if no other inhibit requests are active.
     *
     * @param device  The device on which to release the inhibit request.
     * @param profile The profile on which to release the inhibit request.
     * @param token   The token provided in the original call to
     *                {@link #requestBluetoothProfileInhibit}.
     * @return True if the request was released, false if an error occurred.
     */
    @RequiresPermission(Car.PERMISSION_CAR_PROJECTION)
    public boolean releaseBluetoothProfileInhibit(
            BluetoothDevice device, int profile, IBinder token) {
        Preconditions.checkNotNull(device, "device cannot be null");
        Preconditions.checkNotNull(token, "token cannot be null");
        try {
            return mService.releaseBluetoothProfileInhibit(device, profile, token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Call this method to report projection status of your app. The aggregated status (from other
     * projection apps if available) will be broadcasted to interested parties.
     *
     * @param status the reported status that will be distributed to the interested listeners
     *
     * @see #registerProjectionListener(CarProjectionListener, int)
     */
    @RequiresPermission(Car.PERMISSION_CAR_PROJECTION)
    public void updateProjectionStatus(@NonNull ProjectionStatus status) {
        Preconditions.checkNotNull(status, "status cannot be null");
        try {
            mService.updateProjectionStatus(status, mToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Register projection status listener. See {@link ProjectionStatusListener} for details. It is
     * allowed to register multiple listeners.
     *
     * <p>Note: provided listener will be called immediately with the most recent status.
     *
     * @param listener the listener to receive notification for any projection status changes
     */
    @RequiresPermission(Car.PERMISSION_CAR_PROJECTION_STATUS)
    public void registerProjectionStatusListener(@NonNull ProjectionStatusListener listener) {
        Preconditions.checkNotNull(listener, "listener cannot be null");
        synchronized (mLock) {
            mProjectionStatusListeners.add(listener);

            if (mCarProjectionStatusListener == null) {
                mCarProjectionStatusListener = new CarProjectionStatusListenerImpl(this);
                try {
                    mService.registerProjectionStatusListener(mCarProjectionStatusListener);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            } else {
                // Already subscribed to Car Service, immediately notify listener with the current
                // projection status in the event handler thread.
                mHandler.post(() ->
                        listener.onProjectionStatusChanged(
                                mCarProjectionStatusListener.mCurrentState,
                                mCarProjectionStatusListener.mCurrentPackageName,
                                mCarProjectionStatusListener.mDetails));
            }
        }
    }

    /**
     * Unregister provided listener from projection status notifications
     *
     * @param listener the listener for projection status notifications that was previously
     * registered with {@link #unregisterProjectionStatusListener(ProjectionStatusListener)}
     */
    @RequiresPermission(Car.PERMISSION_CAR_PROJECTION_STATUS)
    public void unregisterProjectionStatusListener(@NonNull ProjectionStatusListener listener) {
        Preconditions.checkNotNull(listener, "listener cannot be null");
        synchronized (mLock) {
            if (!mProjectionStatusListeners.remove(listener)
                    || !mProjectionStatusListeners.isEmpty()) {
                return;
            }
            unregisterProjectionStatusListenerFromCarServiceLocked();
        }
    }

    private void unregisterProjectionStatusListenerFromCarServiceLocked() {
        try {
            mService.unregisterProjectionStatusListener(mCarProjectionStatusListener);
            mCarProjectionStatusListener = null;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void handleProjectionStatusChanged(@ProjectionState int state,
            String packageName, List<ProjectionStatus> details) {
        List<ProjectionStatusListener> listeners;
        synchronized (mLock) {
            listeners = new ArrayList<>(mProjectionStatusListeners);
        }
        for (ProjectionStatusListener listener : listeners) {
            listener.onProjectionStatusChanged(state, packageName, details);
        }
    }

    /**
     * Returns {@link Bundle} object that contains customization for projection app. This bundle
     * can be parsed using {@link ProjectionOptions}.
     */
    @RequiresPermission(Car.PERMISSION_CAR_PROJECTION)
    public @NonNull Bundle getProjectionOptions() {
        try {
            return mService.getProjectionOptions();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Callback class for applications to receive updates about the LocalOnlyHotspot status.
     */
    public abstract static class ProjectionAccessPointCallback {
        public static final int ERROR_NO_CHANNEL = 1;
        public static final int ERROR_GENERIC = 2;
        public static final int ERROR_INCOMPATIBLE_MODE = 3;
        public static final int ERROR_TETHERING_DISALLOWED = 4;

        /** Called when access point started successfully. */
        public void onStarted(WifiConfiguration wifiConfiguration) {}
        /** Called when access point is stopped. No events will be sent after that. */
        public void onStopped() {}
        /** Called when access point failed to start. No events will be sent after that. */
        public void onFailed(int reason) {}
    }

    /**
     * Callback proxy for LocalOnlyHotspotCallback objects.
     */
    private static class ProjectionAccessPointCallbackProxy {
        private static final String LOG_PREFIX =
                ProjectionAccessPointCallbackProxy.class.getSimpleName() + ": ";

        private final Handler mHandler;
        private final WeakReference<CarProjectionManager> mCarProjectionManagerRef;
        private final Messenger mMessenger;

        ProjectionAccessPointCallbackProxy(CarProjectionManager manager, Looper looper,
                final ProjectionAccessPointCallback callback) {
            mCarProjectionManagerRef = new WeakReference<>(manager);

            mHandler = new Handler(looper) {
                @Override
                public void handleMessage(Message msg) {
                    Log.d(TAG, LOG_PREFIX + "handle message what: " + msg.what + " msg: " + msg);

                    CarProjectionManager manager = mCarProjectionManagerRef.get();
                    if (manager == null) {
                        Log.w(TAG, LOG_PREFIX + "handle message post GC");
                        return;
                    }

                    switch (msg.what) {
                        case PROJECTION_AP_STARTED:
                            WifiConfiguration config = (WifiConfiguration) msg.obj;
                            if (config == null) {
                                Log.e(TAG, LOG_PREFIX + "config cannot be null.");
                                callback.onFailed(ProjectionAccessPointCallback.ERROR_GENERIC);
                                return;
                            }
                            callback.onStarted(config);
                            break;
                        case PROJECTION_AP_STOPPED:
                            Log.i(TAG, LOG_PREFIX + "hotspot stopped");
                            callback.onStopped();
                            break;
                        case PROJECTION_AP_FAILED:
                            int reasonCode = msg.arg1;
                            Log.w(TAG, LOG_PREFIX + "failed to start.  reason: "
                                    + reasonCode);
                            callback.onFailed(reasonCode);
                            break;
                        default:
                            Log.e(TAG, LOG_PREFIX + "unhandled message.  type: " + msg.what);
                    }
                }
            };
            mMessenger = new Messenger(mHandler);
        }

        Messenger getMessenger() {
            return mMessenger;
        }
    }

    private void handleVoiceAssistantRequest(boolean fromLongPress) {
        CarProjectionListener listener;
        synchronized (mLock) {
            if (mListener == null) {
                return;
            }
            listener = mListener;
        }
        listener.onVoiceAssistantRequest(fromLongPress);
    }

    private static class ICarProjectionCallbackImpl extends ICarProjectionCallback.Stub {

        private final WeakReference<CarProjectionManager> mManager;

        private ICarProjectionCallbackImpl(CarProjectionManager manager) {
            mManager = new WeakReference<>(manager);
        }

        @Override
        public void onVoiceAssistantRequest(final boolean fromLongPress) {
            final CarProjectionManager manager = mManager.get();
            if (manager == null) {
                return;
            }
            manager.mHandler.post(() -> manager.handleVoiceAssistantRequest(fromLongPress));
        }
    }

    private static class CarProjectionStatusListenerImpl
            extends ICarProjectionStatusListener.Stub {

        private @ProjectionState int mCurrentState;
        private @Nullable String mCurrentPackageName;
        private List<ProjectionStatus> mDetails = new ArrayList<>(0);

        private final WeakReference<CarProjectionManager> mManagerRef;

        private CarProjectionStatusListenerImpl(CarProjectionManager mgr) {
            mManagerRef = new WeakReference<>(mgr);
        }

        @Override
        public void onProjectionStatusChanged(int projectionState,
                String packageName,
                List<ProjectionStatus> details) {
            CarProjectionManager mgr = mManagerRef.get();
            if (mgr != null) {
                mgr.mHandler.post(() -> {
                    mCurrentState = projectionState;
                    mCurrentPackageName = packageName;
                    mDetails = Collections.unmodifiableList(details);

                    mgr.handleProjectionStatusChanged(projectionState, packageName, mDetails);
                });
            }
        }
    }
}
