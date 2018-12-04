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
import static android.car.cluster.sample.ClusterRenderingServiceImpl.LOCAL_BINDING_ACTION;
import static android.car.cluster.sample.ClusterRenderingServiceImpl.MSG_KEY_ACTIVITY_DISPLAY_ID;
import static android.car.cluster.sample.ClusterRenderingServiceImpl.MSG_KEY_ACTIVITY_STATE;
import static android.car.cluster.sample.ClusterRenderingServiceImpl.MSG_KEY_CATEGORY;
import static android.car.cluster.sample.ClusterRenderingServiceImpl.MSG_KEY_FREE_NAVIGATION_ACTIVITY_NAME;
import static android.car.cluster.sample.ClusterRenderingServiceImpl
        .MSG_KEY_FREE_NAVIGATION_ACTIVITY_VISIBLE;
import static android.car.cluster.sample.ClusterRenderingServiceImpl.MSG_KEY_KEY_EVENT;
import static android.car.cluster.sample.ClusterRenderingServiceImpl
        .MSG_ON_FREE_NAVIGATION_ACTIVITY_STATE_CHANGED;
import static android.car.cluster.sample.ClusterRenderingServiceImpl.MSG_ON_KEY_EVENT;
import static android.car.cluster.sample.ClusterRenderingServiceImpl
        .MSG_ON_NAVIGATION_STATE_CHANGED;
import static android.car.cluster.sample.ClusterRenderingServiceImpl.MSG_REGISTER_CLIENT;
import static android.car.cluster.sample.ClusterRenderingServiceImpl
        .MSG_SET_ACTIVITY_LAUNCH_OPTIONS;
import static android.car.cluster.sample.ClusterRenderingServiceImpl.MSG_UNREGISTER_CLIENT;

import android.car.Car;
import android.car.CarAppFocusManager;
import android.car.CarNotConnectedException;
import android.car.cluster.ClusterActivityState;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;

import androidx.car.cluster.navigation.NavigationState;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.lifecycle.ViewModelProviders;
import androidx.versionedparcelable.ParcelUtils;
import androidx.viewpager.widget.ViewPager;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;

public class MainClusterActivity extends FragmentActivity {
    private static final String TAG = "Cluster.MainActivity";

    private static final NavigationState NULL_NAV_STATE = new NavigationState.Builder().build();

    private ViewPager mPager;
    private NavStateController mNavStateController;
    private ClusterViewModel mClusterViewModel;

    private HashMap<Button, Facet<?>> mButtonToFacet = new HashMap<>();
    private SparseArray<Facet<?>> mOrderToFacet = new SparseArray<>();

    private InputMethodManager mInputMethodManager;
    private Messenger mService;
    private Messenger mServiceCallbacks = new Messenger(new MessageHandler(this));
    private VirtualDisplay mPendingVirtualDisplay = null;
    private Car mCar;
    private CarAppFocusManager mCarAppFocusManager;

    public static class VirtualDisplay {
        public final int mDisplayId;
        public final Rect mBounds;

        public VirtualDisplay(int displayId, Rect bounds) {
            mDisplayId = displayId;
            mBounds = bounds;
        }
    }

    private final View.OnFocusChangeListener mFacetButtonFocusListener =
            new View.OnFocusChangeListener() {
        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            if (hasFocus) {
                mPager.setCurrentItem(mButtonToFacet.get(v).order);
            }
        }
    };

    private ServiceConnection mClusterRenderingServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "onServiceConnected, name: " + name + ", service: " + service);
            mService = new Messenger(service);
            sendServiceMessage(MSG_REGISTER_CLIENT, null, mServiceCallbacks);
            if (mPendingVirtualDisplay != null) {
                // If haven't reported the virtual display yet, do so on service connect.
                reportNavDisplay(mPendingVirtualDisplay);
                mPendingVirtualDisplay = null;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "onServiceDisconnected, name: " + name);
            mService = null;
            onNavigationStateChange(NULL_NAV_STATE);
        }
    };

    private ServiceConnection mCarServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                Log.i(TAG, "onServiceConnected, name: " + name + ", service: " + service);
                mCarAppFocusManager = (CarAppFocusManager) mCar.getCarManager(
                        Car.APP_FOCUS_SERVICE);
                if (mCarAppFocusManager == null) {
                    Log.e(TAG, "onServiceConnected: unable to obtain CarAppFocusManager");
                    return;
                }
                mCarAppFocusManager.addFocusListener(
                        (appType, active) -> mClusterViewModel.setNavigationFocus(active),
                        CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION);
            } catch (CarNotConnectedException e) {
                Log.e(TAG, "onServiceConnected: error obtaining manager", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "onServiceDisconnected, name: " + name);
            mCarAppFocusManager = null;
        }
    };

    private static class MessageHandler extends Handler {
        private final WeakReference<MainClusterActivity> mActivity;

        MessageHandler(MainClusterActivity activity) {
            mActivity = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            Bundle data = msg.getData();
            switch (msg.what) {
                case MSG_ON_KEY_EVENT:
                    KeyEvent event = data.getParcelable(MSG_KEY_KEY_EVENT);
                    if (event != null) {
                        mActivity.get().onKeyEvent(event);
                    }
                    break;
                case MSG_ON_NAVIGATION_STATE_CHANGED:
                    if (data == null) {
                        mActivity.get().onNavigationStateChange(null);
                    } else {
                        data.setClassLoader(ParcelUtils.class.getClassLoader());
                        NavigationState navState = NavigationState
                                .fromParcelable(data.getParcelable(
                                        ClusterRenderingServiceImpl.NAV_STATE_BUNDLE_KEY));
                        mActivity.get().onNavigationStateChange(navState);
                    }
                    break;
                case MSG_ON_FREE_NAVIGATION_ACTIVITY_STATE_CHANGED:
                    ComponentName activity = data.getParcelable(
                            MSG_KEY_FREE_NAVIGATION_ACTIVITY_NAME);
                    boolean isVisible = data.getBoolean(MSG_KEY_FREE_NAVIGATION_ACTIVITY_VISIBLE);
                    mActivity.get().mClusterViewModel.setFreeNavigationActivity(activity,
                            isVisible);
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        setContentView(R.layout.activity_main);

        mInputMethodManager = getSystemService(InputMethodManager.class);

        Intent intent = new Intent(this, ClusterRenderingServiceImpl.class);
        intent.setAction(LOCAL_BINDING_ACTION);
        bindService(intent, mClusterRenderingServiceConnection, 0);

        registerFacets(
                new Facet<>(findViewById(R.id.btn_nav), 0, NavigationFragment.class),
                new Facet<>(findViewById(R.id.btn_phone), 1, PhoneFragment.class),
                new Facet<>(findViewById(R.id.btn_music), 2, MusicFragment.class),
                new Facet<>(findViewById(R.id.btn_car_info), 3, CarInfoFragment.class));

        mPager = findViewById(R.id.pager);
        mPager.setAdapter(new ClusterPageAdapter(getSupportFragmentManager()));
        mOrderToFacet.get(0).button.requestFocus();
        mNavStateController = new NavStateController(findViewById(R.id.navigation_state));

        mClusterViewModel = ViewModelProviders.of(this).get(ClusterViewModel.class);
        mClusterViewModel.getNavigationFocus().observe(this, active ->
                mNavStateController.setActive(active));

        mCar = Car.createCar(this, mCarServiceConnection);
        mCar.connect();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        mCar.disconnect();
        mCarAppFocusManager = null;
        if (mService != null) {
            sendServiceMessage(MSG_UNREGISTER_CLIENT, null, mServiceCallbacks);
            mService = null;
        }
        unbindService(mClusterRenderingServiceConnection);
    }

    private void onKeyEvent(KeyEvent event) {
        Log.i(TAG, "onKeyEvent, event: " + event);

        // This is a hack. We use SOURCE_CLASS_POINTER here because this type of input is associated
        // with the display. otherwise this event will be ignored in ViewRootImpl because injecting
        // KeyEvent w/o activity being focused is useless.
        event.setSource(event.getSource() | InputDevice.SOURCE_CLASS_POINTER);
        mInputMethodManager.dispatchKeyEventFromInputMethod(getCurrentFocus(), event);
    }

    private void onNavigationStateChange(NavigationState state) {
        Log.d(TAG, "onNavigationStateChange: " + state);
        if (mNavStateController != null) {
            mNavStateController.update(state);
        }
    }

    public void updateNavDisplay(VirtualDisplay virtualDisplay) {
        if (mService == null) {
            // Service is not bound yet. Hold the information and notify when the service is bound.
            mPendingVirtualDisplay = virtualDisplay;
            return;
        } else {
            reportNavDisplay(virtualDisplay);
        }
    }

    private void reportNavDisplay(VirtualDisplay virtualDisplay) {
        Bundle data = new Bundle();
        data.putString(MSG_KEY_CATEGORY, CATEGORY_NAVIGATION);
        data.putInt(MSG_KEY_ACTIVITY_DISPLAY_ID, virtualDisplay.mDisplayId);
        data.putBundle(MSG_KEY_ACTIVITY_STATE, ClusterActivityState
                .create(virtualDisplay.mDisplayId != Display.INVALID_DISPLAY,
                        virtualDisplay.mBounds)
                .toBundle());
        sendServiceMessage(MSG_SET_ACTIVITY_LAUNCH_OPTIONS, data, null);
    }

    /**
     * Sends a message to the {@link ClusterRenderingServiceImpl}, which runs on a different
     * process.
     * @param what action to perform
     * @param data action data
     * @param replyTo {@link Messenger} where to reply back
     */
    private void sendServiceMessage(int what, Bundle data, Messenger replyTo) {
        try {
            Message message = Message.obtain(null, what);
            message.setData(data);
            message.replyTo = replyTo;
            mService.send(message);
        } catch (RemoteException ex) {
            Log.e(TAG, "Unable to deliver message " + what + ". Service died");
        }
    }

    public class ClusterPageAdapter extends FragmentPagerAdapter {
        public ClusterPageAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public int getCount() {
            return mButtonToFacet.size();
        }

        @Override
        public Fragment getItem(int position) {
            return mOrderToFacet.get(position).getOrCreateFragment();
        }
    }

    private void registerFacets(Facet<?>... facets) {
        for (Facet<?> f : facets) {
            registerFacet(f);
        }
    }

    private <T> void registerFacet(Facet<T> facet) {
        mOrderToFacet.append(facet.order, facet);
        mButtonToFacet.put(facet.button, facet);

        facet.button.setOnFocusChangeListener(mFacetButtonFocusListener);
    }

    private static class Facet<T> {
        Button button;
        Class<T> clazz;
        int order;

        Facet(Button button, int order, Class<T> clazz) {
            this.button = button;
            this.order = order;
            this.clazz = clazz;
        }

        private Fragment mFragment;

        Fragment getOrCreateFragment() {
            if (mFragment == null) {
                try {
                    mFragment = (Fragment) clazz.getConstructors()[0].newInstance();
                } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            }
            return mFragment;
        }
    }
}
