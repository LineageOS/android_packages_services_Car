/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.car.cluster.sample;

import static android.car.cluster.CarInstrumentClusterManager.CATEGORY_NAVIGATION;
import static android.content.Intent.ACTION_USER_SWITCHED;
import static android.content.Intent.ACTION_USER_UNLOCKED;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import static java.lang.Integer.parseInt;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.car.CarNotConnectedException;
import android.car.cluster.CarInstrumentClusterManager;
import android.car.cluster.ClusterActivityState;
import android.car.cluster.renderer.InstrumentClusterRenderingService;
import android.car.cluster.renderer.NavigationRenderer;
import android.car.navigation.CarNavigationInstrumentCluster;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.hardware.display.DisplayManager.DisplayListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;

import androidx.car.cluster.navigation.NavigationState;
import androidx.versionedparcelable.ParcelUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Implementation of {@link InstrumentClusterRenderingService} which renders an activity on a
 * virtual display that is transmitted to an external screen.
 */
public class ClusterRenderingServiceImpl extends InstrumentClusterRenderingService {
    private static final String TAG = "Cluster.SampleService";

    private static final int NO_DISPLAY = -1;

    static final String LOCAL_BINDING_ACTION = "local";
    static final String NAV_STATE_BUNDLE_KEY = "navstate";
    static final int NAV_STATE_EVENT_ID = 1;
    static final int MSG_SET_ACTIVITY_LAUNCH_OPTIONS = 1;
    static final int MSG_ON_NAVIGATION_STATE_CHANGED = 2;
    static final int MSG_ON_KEY_EVENT = 3;
    static final int MSG_REGISTER_CLIENT = 4;
    static final int MSG_UNREGISTER_CLIENT = 5;
    static final String MSG_KEY_CATEGORY = "category";
    static final String MSG_KEY_ACTIVITY_DISPLAY_ID = "activity_display_id";
    static final String MSG_KEY_ACTIVITY_STATE = "activity_state";
    static final String MSG_KEY_KEY_EVENT = "key_event";

    private static final int NAVIGATION_ACTIVITY_MAX_RETRIES = 10;
    private static final int NAVIGATION_ACTIVITY_RETRY_INTERVAL_MS = 1000;

    private List<Messenger> mClients = new ArrayList<>();
    private ClusterDisplayProvider mDisplayProvider;
    private int mDisplayId = NO_DISPLAY;
    private UserReceiver mUserReceiver;
    private final Handler mHandler = new Handler();
    private final Runnable mRetryLaunchNavigationActivity = this::tryLaunchNavigationActivity;
    private int mNavigationDisplayId = NO_DISPLAY;

    private final DisplayListener mDisplayListener = new DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            Log.i(TAG, "Cluster display found, displayId: " + displayId);
            mDisplayId = displayId;
            tryLaunchActivity();
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            Log.w(TAG, "Cluster display has been removed");
        }

        @Override
        public void onDisplayChanged(int displayId) {

        }
    };

    private static class UserReceiver extends BroadcastReceiver {
        private WeakReference<ClusterRenderingServiceImpl> mService;

        UserReceiver(ClusterRenderingServiceImpl service) {
            mService = new WeakReference<>(service);
        }

        public void register(Context context) {
            IntentFilter intentFilter =  new IntentFilter(ACTION_USER_UNLOCKED);
            intentFilter.addAction(ACTION_USER_SWITCHED);
            context.registerReceiver(this, intentFilter);
        }

        public void unregister(Context context) {
            context.unregisterReceiver(this);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            ClusterRenderingServiceImpl service = mService.get();
            Log.d(TAG, "Broadcast received: " + intent);
            service.tryLaunchActivity();
        }
    }

    private static class MessageHandler extends Handler {
        private final WeakReference<ClusterRenderingServiceImpl> mService;

        MessageHandler(ClusterRenderingServiceImpl service) {
            mService = new WeakReference<>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG, "handleMessage: " + msg.what);
            try {
                switch (msg.what) {
                    case MSG_SET_ACTIVITY_LAUNCH_OPTIONS: {
                        int displayId = msg.getData().getInt(MSG_KEY_ACTIVITY_DISPLAY_ID);
                        Bundle state = msg.getData().getBundle(MSG_KEY_ACTIVITY_STATE);
                        String category = msg.getData().getString(MSG_KEY_CATEGORY);
                        ActivityOptions options = displayId != Display.INVALID_DISPLAY
                                ? ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
                                : null;
                        mService.get().setClusterActivityLaunchOptions(category, options);
                        Log.d(TAG, String.format("activity options set: %s = %s (displayeId: %d)",
                                category, options, options.getLaunchDisplayId()));
                        mService.get().setClusterActivityState(category, state);
                        Log.d(TAG, String.format("activity state set: %s = %s", category, state));

                        // Starting a default navigation activity. This would take place until any
                        // navigation app takes focus.
                        mService.get().startNavigationActivity(displayId);
                        break;
                    }
                    case MSG_REGISTER_CLIENT:
                        mService.get().mClients.add(msg.replyTo);
                        break;
                    case MSG_UNREGISTER_CLIENT:
                        mService.get().mClients.remove(msg.replyTo);
                        break;
                    default:
                        super.handleMessage(msg);
                }
            } catch (CarNotConnectedException ex) {
                Log.e(TAG, "Unable to execute message " + msg.what, ex);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind, intent: " + intent);
        return (LOCAL_BINDING_ACTION.equals(intent.getAction()))
                ? new Messenger(new MessageHandler(this)).getBinder()
                : super.onBind(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        mDisplayProvider = new ClusterDisplayProvider(this, mDisplayListener);
        mUserReceiver = new UserReceiver(this);
        mUserReceiver.register(this);
    }

    private void tryLaunchActivity() {
        int userHandle = ActivityManager.getCurrentUser();
        if (userHandle == UserHandle.USER_SYSTEM || mDisplayId == NO_DISPLAY) {
            Log.d(TAG, String.format("Launch activity ignored (user: %d, display: %d)", userHandle,
                    mDisplayId));
            // Not ready to launch yet.
            return;
        }
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(mDisplayId);
        Intent intent = new Intent(this, MainClusterActivity.class);
        intent.setFlags(FLAG_ACTIVITY_NEW_TASK);
        startActivityAsUser(intent, options.toBundle(), UserHandle.of(userHandle));
        Log.i(TAG, String.format("launching main activity: %s (user: %d, display: %d)", intent,
                userHandle, mDisplayId));
    }

    @Override
    protected void onKeyEvent(KeyEvent keyEvent) {
        Log.d(TAG, "onKeyEvent, keyEvent: " + keyEvent);
        Bundle data = new Bundle();
        data.putParcelable(MSG_KEY_KEY_EVENT, keyEvent);
        broadcastClientMessage(MSG_ON_KEY_EVENT, data);
    }

    /**
     * Broadcasts a message to all the registered service clients
     *
     * @param what event identifier
     * @param data event data
     */
    private void broadcastClientMessage(int what, Bundle data) {
        Log.d(TAG, "broadcast message " + what + " to " + mClients.size() + " clients");
        for (int i = mClients.size() - 1; i >= 0; i--) {
            Messenger client = mClients.get(i);
            try {
                Message msg = Message.obtain(null, what);
                msg.setData(data);
                client.send(msg);
            } catch (RemoteException ex) {
                Log.e(TAG, "Client " + i + " is dead", ex);
                mClients.remove(i);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.w(TAG, "onDestroy");
        mUserReceiver.unregister(this);
    }

    @Override
    protected NavigationRenderer getNavigationRenderer() {
        NavigationRenderer navigationRenderer = new NavigationRenderer() {
            @Override
            public CarNavigationInstrumentCluster getNavigationProperties() {
                CarNavigationInstrumentCluster config =
                        CarNavigationInstrumentCluster.createCluster(1000);
                Log.d(TAG, "getNavigationProperties, returns: " + config);
                return config;
            }

            @Override
            public void onEvent(int eventType, Bundle bundle) {
                try {
                    StringBuilder bundleSummary = new StringBuilder();
                    if (eventType == NAV_STATE_EVENT_ID) {
                        bundle.setClassLoader(ParcelUtils.class.getClassLoader());
                        NavigationState navState = NavigationState
                                .fromParcelable(bundle.getParcelable(NAV_STATE_BUNDLE_KEY));
                        bundleSummary.append(navState.toString());

                        // Update clients
                        broadcastClientMessage(MSG_ON_NAVIGATION_STATE_CHANGED, bundle);
                    } else {
                        for (String key : bundle.keySet()) {
                            bundleSummary.append(key);
                            bundleSummary.append("=");
                            bundleSummary.append(bundle.get(key));
                            bundleSummary.append(" ");
                        }
                    }
                    Log.d(TAG, "onEvent(" + eventType + ", " + bundleSummary + ")");
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing event data (" + eventType + ", " + bundle + ")", e);
                    bundle.putParcelable(NAV_STATE_BUNDLE_KEY, new NavigationState.Builder().build()
                            .toParcelable());
                    broadcastClientMessage(MSG_ON_NAVIGATION_STATE_CHANGED, bundle);
                }
            }
        };

        Log.i(TAG, "createNavigationRenderer, returns: " + navigationRenderer);
        return navigationRenderer;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (args != null && args.length > 0) {
            execShellCommand(args);
        } else {

            if (args == null || args.length == 0) {
                writer.println("* dump " + getClass().getCanonicalName() + " *");
                writer.println("DisplayProvider: " + mDisplayProvider);
            }
        }
    }

    private void emulateKeyEvent(int keyCode) {
        Log.i(TAG, "emulateKeyEvent, keyCode: " + keyCode);
        long downTime = SystemClock.uptimeMillis();
        long eventTime = SystemClock.uptimeMillis();
        KeyEvent event = obtainKeyEvent(keyCode, downTime, eventTime, KeyEvent.ACTION_DOWN);
        onKeyEvent(event);

        eventTime = SystemClock.uptimeMillis();
        event = obtainKeyEvent(keyCode, downTime, eventTime, KeyEvent.ACTION_UP);
        onKeyEvent(event);
    }

    private KeyEvent obtainKeyEvent(int keyCode, long downTime, long eventTime, int action) {
        int scanCode = 0;
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            scanCode = 108;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            scanCode = 106;
        }
        return KeyEvent.obtain(
                    downTime,
                    eventTime,
                    action,
                    keyCode,
                    0 /* repeat */,
                    0 /* meta state */,
                    0 /* deviceId*/,
                    scanCode /* scancode */,
                    KeyEvent.FLAG_FROM_SYSTEM /* flags */,
                    InputDevice.SOURCE_KEYBOARD,
                    null /* characters */);
    }

    private void execShellCommand(String[] args) {
        Log.i(TAG, "execShellCommand, args: " + Arrays.toString(args));

        String command = args[0];

        switch (command) {
            case "injectKey": {
                if (args.length > 1) {
                    emulateKeyEvent(parseInt(args[1]));
                } else {
                    Log.i(TAG, "Not enough arguments");
                }
                break;
            }
            case "destroyOverlayDisplay": {
                Settings.Global.putString(getContentResolver(),
                        Global.OVERLAY_DISPLAY_DEVICES, "");
                break;
            }

            case "createOverlayDisplay": {
                if (args.length > 1) {
                    Settings.Global.putString(getContentResolver(),
                            Global.OVERLAY_DISPLAY_DEVICES, args[1]);
                } else {
                    Log.i(TAG, "Not enough arguments, expected 2");
                }
                break;
            }

            case "setUnobscuredArea": {
                if (args.length > 5) {
                    Rect unobscuredArea = new Rect(parseInt(args[2]), parseInt(args[3]),
                            parseInt(args[4]), parseInt(args[5]));
                    try {
                        setClusterActivityState(args[1],
                                ClusterActivityState.create(true, unobscuredArea).toBundle());
                    } catch (CarNotConnectedException e) {
                        Log.i(TAG, "Failed to set activity state.", e);
                    }
                } else {
                    Log.i(TAG, "wrong format, expected: category left top right bottom");
                }
            }
        }
    }

    private void startNavigationActivity(int displayId) {
        mNavigationDisplayId = displayId;
        tryLaunchNavigationActivity();
    }

    /**
     * Tries to start a default navigation activity in the cluster. During system initialization
     * launching user activities might fail due the system not being ready or {@link PackageManager}
     * not being able to resolve the implicit intent. It is also possible that the system doesn't
     * have a default navigation activity selected yet.
     */
    private void tryLaunchNavigationActivity() {
        mHandler.removeCallbacks(mRetryLaunchNavigationActivity);

        Intent intent = getNavigationActivityIntent();

        try {
            if (intent == null) {
                throw new ActivityNotFoundException();
            }
            Log.d(TAG, "Launching: " + intent + " on display: " + mNavigationDisplayId);
            Bundle activityOptions = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(mNavigationDisplayId)
                    .toBundle();

            startActivityAsUser(intent, activityOptions, UserHandle.CURRENT);
        } catch (ActivityNotFoundException ex) {
            // Some activities might not be available right on startup. We will retry.
            mHandler.postDelayed(mRetryLaunchNavigationActivity,
                    NAVIGATION_ACTIVITY_RETRY_INTERVAL_MS);
        } catch (Exception ex) {
            Log.e(TAG, "Unable to start navigation activity: " + intent, ex);
        }
    }

    /**
     * Returns a default navigation activity to show in the cluster.
     * In the current implementation we search for an activity with the
     * {@link CarInstrumentClusterManager#CATEGORY_NAVIGATION} category from the same navigation app
     * selected from CarLauncher (see CarLauncher#getMapsIntent()).
     * Alternatively, other implementations could
     * <ul>
     * <li>Read this package from a resource (having a OEM default activity to show)
     * <li>Let the user select one from settings.
     * </ul>
     */
    private Intent getNavigationActivityIntent() {
        PackageManager pm = getPackageManager();
        int userId = ActivityManager.getCurrentUser();

        // Get currently selected navigation app.
        Intent intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN,
                Intent.CATEGORY_APP_MAPS);
        ResolveInfo navigationApp = pm.resolveActivityAsUser(intent,
                PackageManager.MATCH_DEFAULT_ONLY, userId);

        // Get all possible cluster activities
        intent = new Intent(Intent.ACTION_MAIN).addCategory(CATEGORY_NAVIGATION);
        List<ResolveInfo> candidates = pm.queryIntentActivitiesAsUser(intent, 0, userId);

        // If there is a select navigation app, try finding a matching auxiliary navigation activity
        if (navigationApp != null) {
            Log.d(TAG, "Current navigation app: " + navigationApp);
            for (ResolveInfo candidate : candidates) {
                Log.d(TAG, "Candidate: " + candidate);
                if (candidate.activityInfo.packageName.equals(navigationApp.activityInfo
                        .packageName)) {
                    Log.d(TAG, "Found activity: " + candidate);
                    intent.setPackage(navigationApp.activityInfo.packageName);
                    intent.setComponent(new ComponentName(candidate.activityInfo.packageName,
                            candidate.activityInfo.name));
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                    return intent;
                }
            }
        } else {
            Log.d(TAG, "NO CURRENT ACTIVITY");
            for (ResolveInfo candidate : candidates) {
                Log.d(TAG, "Candidate: " + candidate);
            }
        }

        // During initialization implicit intents might not provided a result. We will just
        // retry until we find one, or we exhaust the retries.
        Log.d(TAG, "No default activity found (it might not be available yet).");
        return null;
    }

}
