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

import static android.car.cluster.sample.SampleClusterServiceImpl.LOCAL_BINDING_ACTION;
import static android.car.cluster.sample.SampleClusterServiceImpl.MSG_KEY_KEY_EVENT;
import static android.car.cluster.sample.SampleClusterServiceImpl.MSG_ON_KEY_EVENT;
import static android.car.cluster.sample.SampleClusterServiceImpl.MSG_ON_NAVIGATION_STATE_CHANGED;
import static android.car.cluster.sample.SampleClusterServiceImpl.MSG_REGISTER_CLIENT;
import static android.car.cluster.sample.SampleClusterServiceImpl.MSG_UNREGISTER_CLIENT;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;
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
import androidx.versionedparcelable.ParcelUtils;
import androidx.viewpager.widget.ViewPager;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;

public class MainClusterActivity extends FragmentActivity {
    private static final String TAG = "Cluster.MainActivity";
    private static final NavigationState NULL_NAV_STATE = new NavigationState.Builder().build();
    private ViewPager mPager;

    private HashMap<Button, Facet<?>> mButtonToFacet = new HashMap<>();
    private SparseArray<Facet<?>> mOrderToFacet = new SparseArray<>();

    private InputMethodManager mInputMethodManager;
    private Messenger mService;
    private Messenger mServiceCallbacks = new Messenger(new MessageHandler(this));

    private final View.OnFocusChangeListener mFacetButtonFocusListener =
            new View.OnFocusChangeListener() {
        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            if (hasFocus) {
                mPager.setCurrentItem(mButtonToFacet.get(v).order);
            }
        }
    };

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "onServiceConnected, name: " + name + ", service: " + service);
            mService = new Messenger(service);
            sendServiceMessage(MSG_REGISTER_CLIENT, null, mServiceCallbacks);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "onServiceDisconnected, name: " + name);
            mService = null;
            onNavigationStateChange(NULL_NAV_STATE);
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
                    mActivity.get().onKeyEvent(data.getParcelable(MSG_KEY_KEY_EVENT));
                    break;
                case MSG_ON_NAVIGATION_STATE_CHANGED:
                    data.setClassLoader(ParcelUtils.class.getClassLoader());
                    NavigationState navState = NavigationState
                            .fromParcelable(data.getParcelable(
                                    SampleClusterServiceImpl.NAV_STATE_BUNDLE_KEY));
                    mActivity.get().onNavigationStateChange(navState);
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

        Intent intent = new Intent(this, SampleClusterServiceImpl.class);
        intent.setAction(LOCAL_BINDING_ACTION);
        bindService(intent, mServiceConnection, 0);

        registerFacets(
                new Facet<>(findViewById(R.id.btn_nav), 0, NavigationFragment.class),
                new Facet<>(findViewById(R.id.btn_phone), 1, PhoneFragment.class),
                new Facet<>(findViewById(R.id.btn_music), 2, MusicFragment.class),
                new Facet<>(findViewById(R.id.btn_car_info), 3, CarInfoFragment.class));

        mPager = findViewById(R.id.pager);
        mPager.setAdapter(new ClusterPageAdapter(getSupportFragmentManager()));
        mOrderToFacet.get(0).button.requestFocus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        if (mService != null) {
            sendServiceMessage(MSG_UNREGISTER_CLIENT, null, mServiceCallbacks);
            unbindService(mServiceConnection);
            mService = null;
        }
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
        // TODO: Display new navigation state on the UI.
    }

    /**
     * Sends a message to the {@link SampleClusterServiceImpl}, which runs on a different process.

     * @param what action to perform
     * @param data action data
     * @param replyTo {@link Messenger} where to reply back
     */
    public void sendServiceMessage(int what, Bundle data, Messenger replyTo) {
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
