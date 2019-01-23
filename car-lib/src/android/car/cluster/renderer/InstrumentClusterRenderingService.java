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
package android.car.cluster.renderer;

import static android.content.PermissionChecker.PERMISSION_GRANTED;

import android.annotation.CallSuper;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.ActivityOptions;
import android.app.Service;
import android.car.Car;
import android.car.CarLibLog;
import android.car.CarNotConnectedException;
import android.car.cluster.ClusterActivityState;
import android.car.navigation.CarNavigationInstrumentCluster;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.KeyEvent;

import com.android.internal.annotations.GuardedBy;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A service that used for interaction between Car Service and Instrument Cluster. Car Service may
 * provide internal navigation binder interface to Navigation App and all notifications will be
 * eventually land in the {@link NavigationRenderer} returned by {@link #getNavigationRenderer()}.
 *
 * <p>To extend this class, you must declare the service in your manifest file with
 * the {@code android.car.permission.BIND_INSTRUMENT_CLUSTER_RENDERER_SERVICE} permission
 * <pre>
 * &lt;service android:name=".MyInstrumentClusterService"
 *          android:permission="android.car.permission.BIND_INSTRUMENT_CLUSTER_RENDERER_SERVICE">
 * &lt;/service></pre>
 * <p>Also, you will need to register this service in the following configuration file:
 * {@code packages/services/Car/service/res/values/config.xml}
 *
 * @hide
 */
@SystemApi
public abstract class InstrumentClusterRenderingService extends Service {
    private static final String TAG = CarLibLog.TAG_CLUSTER;

    private final Object mLock = new Object();
    private RendererBinder mRendererBinder;
    private Handler mUiHandler = new Handler(Looper.getMainLooper());
    private ActivityOptions mActivityOptions;
    private ClusterActivityState mActivityState;
    private ComponentName mNavigationComponent;
    @GuardedBy("mLock")
    private ContextOwner mNavContextOwner;

    private static class ContextOwner {
        final int mUid;
        final int mPid;

        ContextOwner(int uid, int pid) {
            mUid = uid;
            mPid = pid;
        }

        @Override
        public String toString() {
            return "{uid: " + mUid + ", pid: " + mPid + "}";
        }
    }

    @Override
    @CallSuper
    public IBinder onBind(Intent intent) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onBind, intent: " + intent);
        }

        if (mRendererBinder == null) {
            mRendererBinder = new RendererBinder(getNavigationRenderer());
        }

        return mRendererBinder;
    }

    /**
     * Returns {@link NavigationRenderer} or null if it's not supported. This renderer will be
     * shared with the navigation context owner (application holding navigation focus).
     */
    @MainThread
    @Nullable
    public abstract NavigationRenderer getNavigationRenderer();

    /**
     * Called when key event that was addressed to instrument cluster display has been received.
     */
    @MainThread
    public void onKeyEvent(@NonNull KeyEvent keyEvent) {
    }

    /**
     * Called when a navigation application becomes a context owner (receives navigation focus) and
     * its {@link Car#CATEGORY_NAVIGATION} activity is launched.
     */
    @MainThread
    public void onNavigationComponentLaunched() {
    }

    /**
     * Called when the current context owner (application holding navigation focus) releases the
     * focus and its {@link Car#CAR_CATEGORY_NAVIGATION} activity is ready to be replaced by a
     * system default.
     */
    @MainThread
    public void onNavigationComponentReleased() {
    }

    /**
     * Updates the cluster navigation activity by checking which activity to show (an activity of
     * the {@link #mNavContextOwner}). If not yet launched, it will do so.
     */
    private void updateNavigationActivity() {
        ContextOwner contextOwner = getNavigationContextOwner();

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, String.format("updateNavigationActivity (mActivityOptions: %s, "
                    + "mActivityState: %s, mNavContextOwnerUid: %s)", mActivityOptions,
                    mActivityState, contextOwner));
        }

        if (contextOwner == null || contextOwner.mUid == 0 || mActivityOptions == null
                || mActivityState == null || !mActivityState.isVisible()) {
            // We are not yet ready to display an activity on the cluster
            if (mNavigationComponent != null) {
                mNavigationComponent = null;
                onNavigationComponentReleased();
            }
            return;
        }

        ComponentName component = getNavigationComponentByOwner(contextOwner);
        if (Objects.equals(mNavigationComponent, component)) {
            // We have already launched this component.
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Already launched component: " + component);
            }
            return;
        }

        if (component == null) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "No component found for owner: " + contextOwner);
            }
            return;
        }

        if (!startNavigationActivity(component)) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Unable to launch component: " + component);
            }
            return;
        }

        mNavigationComponent = component;
        onNavigationComponentLaunched();
    }

    /**
     * Returns a component with category {@link Car#CAR_CATEGORY_NAVIGATION} from the same package
     * as the given navigation context owner.
     */
    @Nullable
    private ComponentName getNavigationComponentByOwner(ContextOwner contextOwner) {
        for (String packageName : getPackageNamesForUid(contextOwner)) {
            ComponentName component = getComponentFromPackage(packageName);
            if (component != null) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Found component: " + component);
                }
                return component;
            }
        }
        return null;
    }

    private String[] getPackageNamesForUid(ContextOwner contextOwner) {
        if (contextOwner == null || contextOwner.mUid == 0 || contextOwner.mPid == 0) {
            return new String[0];
        }
        String[] packageNames  = getPackageManager().getPackagesForUid(contextOwner.mUid);
        return packageNames != null ? packageNames : new String[0];
    }

    private ContextOwner getNavigationContextOwner() {
        synchronized (mLock) {
            return mNavContextOwner;
        }
    }

    @Nullable
    private ComponentName getComponentFromPackage(@NonNull String packageName) {
        PackageManager packageManager = getPackageManager();

        // Check package permission.
        if (packageManager.checkPermission(Car.PERMISSION_CAR_DISPLAY_IN_CLUSTER, packageName)
                != PERMISSION_GRANTED) {
            Log.i(TAG, String.format("Package '%s' doesn't have permission %s", packageName,
                    Car.PERMISSION_CAR_DISPLAY_IN_CLUSTER));
            return null;
        }

        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Car.CAR_CATEGORY_NAVIGATION)
                .setPackage(packageName);
        List<ResolveInfo> resolveList = packageManager.queryIntentActivities(intent,
                PackageManager.GET_RESOLVED_FILTER);
        if (resolveList == null || resolveList.isEmpty()
                || resolveList.get(0).getComponentInfo() == null) {
            Log.i(TAG, "Failed to resolve an intent: " + intent);
            return null;
        }

        // In case of multiple matching activities in the same package, we pick the first one.
        return resolveList.get(0).getComponentInfo().getComponentName();
    }

    /**
     * Starts an activity on the cluster using the given component.
     *
     * @return false if the activity couldn't be started.
     */
    protected boolean startNavigationActivity(@NonNull ComponentName component) {
        // Create an explicit intent.
        Intent intent = new Intent();
        intent.setComponent(component);
        intent.putExtra(Car.CAR_EXTRA_CLUSTER_ACTIVITY_STATE, mActivityState.toBundle());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivityAsUser(intent, mActivityOptions.toBundle(), UserHandle.CURRENT);
            Log.i(TAG, String.format("Activity launched: %s (options: %s, displayId: %d)",
                    mActivityOptions, intent, mActivityOptions.getLaunchDisplayId()));
        } catch (ActivityNotFoundException ex) {
            Log.w(TAG, "Unable to find activity for intent: " + intent);
            return false;
        } catch (Exception ex) {
            // Catch all other possible exception to prevent service disruption by misbehaving
            // applications.
            Log.e(TAG, "Error trying to launch intent: " + intent + ". Ignored", ex);
            return false;
        }
        return true;
    }

    /**
     * @deprecated Use {@link #setClusterActivityLaunchOptions(ActivityOptions)} instead.
     *
     * @hide
     */
    @Deprecated
    public void setClusterActivityLaunchOptions(String category, ActivityOptions activityOptions)
            throws CarNotConnectedException {
        setClusterActivityLaunchOptions(activityOptions);
    }

    /**
     * Sets configuration for activities that should be launched directly in the instrument
     * cluster.
     *
     * @param activityOptions contains information of how to start cluster activity (on what display
     *                        or activity stack).
     *
     * @hide
     */
    public void setClusterActivityLaunchOptions(ActivityOptions activityOptions) {
        mActivityOptions = activityOptions;
        updateNavigationActivity();
    }

    /**
     * @deprecated Use {@link #setClusterActivityState(ClusterActivityState)} instead.
     *
     * @hide
     */
    @Deprecated
    public void setClusterActivityState(String category, Bundle state) throws
            CarNotConnectedException {
        setClusterActivityState(ClusterActivityState.fromBundle(state));
    }

    /**
     * Set activity state (such as unobscured bounds).
     *
     * @param state pass information about activity state, see
     *              {@link android.car.cluster.ClusterActivityState}
     *
     * @hide
     */
    public void setClusterActivityState(ClusterActivityState state) {
        mActivityState = state;
        updateNavigationActivity();
    }

    @CallSuper
    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("**" + getClass().getSimpleName() + "**");
        writer.println("renderer binder: " + mRendererBinder);
        if (mRendererBinder != null) {
            writer.println("navigation renderer: " + mRendererBinder.mNavigationRenderer);
        }
        writer.println("navigation focus owner: " + getNavigationContextOwner());
        writer.println("activity options: " + mActivityOptions);
        writer.println("activity state: " + mActivityState);
        writer.println("current nav component: " + mNavigationComponent);
        writer.println("current nav packages: " + Arrays.toString(getPackageNamesForUid(
                getNavigationContextOwner())));
    }

    private class RendererBinder extends IInstrumentCluster.Stub {
        private final NavigationRenderer mNavigationRenderer;

        RendererBinder(NavigationRenderer navigationRenderer) {
            mNavigationRenderer = navigationRenderer;
        }

        @Override
        public IInstrumentClusterNavigation getNavigationService() throws RemoteException {
            return new NavigationBinder(mNavigationRenderer);
        }

        @Override
        public void setNavigationContextOwner(int uid, int pid) throws RemoteException {
            synchronized (mLock) {
                mNavContextOwner = new ContextOwner(uid, pid);
            }
            mUiHandler.post(InstrumentClusterRenderingService.this::updateNavigationActivity);
        }

        @Override
        public void onKeyEvent(KeyEvent keyEvent) throws RemoteException {
            mUiHandler.post(() -> InstrumentClusterRenderingService.this.onKeyEvent(keyEvent));
        }
    }

    private class NavigationBinder extends IInstrumentClusterNavigation.Stub {
        private final NavigationRenderer mNavigationRenderer;

        NavigationBinder(NavigationRenderer navigationRenderer) {
            mNavigationRenderer = navigationRenderer;
        }

        @Override
        public void onEvent(int eventType, Bundle bundle) throws RemoteException {
            assertContextOwnership();
            mUiHandler.post(() -> {
                if (mNavigationRenderer != null) {
                    mNavigationRenderer.onEvent(eventType, bundle);
                }
            });
        }

        @Override
        public CarNavigationInstrumentCluster getInstrumentClusterInfo() throws RemoteException {
            return runAndWaitResult(() -> mNavigationRenderer.getNavigationProperties());
        }

        private void assertContextOwnership() {
            int uid = getCallingUid();
            int pid = getCallingPid();

            synchronized (mLock) {
                if (mNavContextOwner.mUid != uid || mNavContextOwner.mPid != pid) {
                    throw new IllegalStateException("Client {uid:" + uid + ", pid: " + pid + "} is"
                            + " not an owner of APP_FOCUS_TYPE_NAVIGATION " + mNavContextOwner);
                }
            }
        }
    }

    private <E> E runAndWaitResult(final Supplier<E> supplier) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<E> result = new AtomicReference<>();

        mUiHandler.post(() -> {
            result.set(supplier.get());
            latch.countDown();
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return result.get();
    }
}
