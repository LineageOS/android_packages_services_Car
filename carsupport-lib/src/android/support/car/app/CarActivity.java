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

package android.support.car.app;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.annotation.CallSuper;
import android.support.annotation.LayoutRes;
import android.support.car.Car;
import android.support.car.CarLibLog;
import android.support.car.CarNotConnectedException;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;

import android.support.car.app.menu.CarMenu;
import android.support.car.app.menu.CarMenuCallbacks;
import android.support.car.app.menu.ICarMenuCallbacks;
import android.support.car.app.menu.ISubscriptionCallbacks;
import android.support.car.app.menu.Root;
import android.view.ViewGroup;


import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Abstraction for car UI. Car applications should implement this for car UI.
 * The API looks like {@link android.app.Activity}, and behaves like one, but it is neither
 * android {@link android.app.Activity} nor {@link android.app.Context}.
 * Applications should use {@link #getContext()} to get necessary {@link android.app.Context}.
 * To use car API, {@link #getCarApi()} can be used. The {@link Car} api passed is already
 * connected, and client does not need to call {@link Car#connect()}.
 */
public abstract class CarActivity {
    private static final String TAG = "CarActivity";
    private static final String KEY_DRAWERSHOWING =
            "android.support.car.app.CarActivity.DRAWER_SHOWING";

    /**
     * Interface to connect {@link CarActivity} to {@link android.app.Activity} or other app model.
     * This interface provides utility for {@link CarActivity} to do things like manipulating view,
     * handling menu, and etc.
     */
    public interface Proxy {
        void setContentView(View view);
        void setContentView(int layoutResID);
        Resources getResources();
        View findViewById(int id);
        LayoutInflater getLayoutInflater();
        void finish();
    }

    /** @hide */
    public static final int CMD_ON_CREATE = 0;
    /** @hide */
    public static final int CMD_ON_START = 1;
    /** @hide */
    public static final int CMD_ON_RESTART = 2;
    /** @hide */
    public static final int CMD_ON_RESUME = 3;
    /** @hide */
    public static final int CMD_ON_PAUSE = 4;
    /** @hide */
    public static final int CMD_ON_STOP = 5;
    /** @hide */
    public static final int CMD_ON_DESTROY = 6;
    /** @hide */
    public static final int CMD_ON_BACK_PRESSED = 7;
    /** @hide */
    public static final int CMD_ON_SAVE_INSTANCE_STATE = 8;
    /** @hide */
    public static final int CMD_ON_RESTORE_INSTANCE_STATE = 9;

    private final Proxy mProxy;
    private final Context mContext;
    private final Handler mHandler = new Handler();

    private final CarUiController mUiController;
    private CarMenuCallbacks mMenuCallbacks;
    private CarMenuCallbacksBinder mBinder;
    private boolean mDrawerShowing;
    private boolean mOnCreateCalled = false;

    public CarActivity(Proxy proxy, Context context) {
        mProxy = proxy;
        mContext = context;
        mUiController = new CarUiController(this);
    }

    public Context getContext() {
        return mContext;
    }

    public void setContentView(View view) {
        ViewGroup parent = (ViewGroup) findViewById(mUiController.getFragmentContainerId());
        parent.addView(view);
    }

    public void setContentView(@LayoutRes int resourceId) {
        ViewGroup parent = (ViewGroup) findViewById(mUiController.getFragmentContainerId());
        LayoutInflater inflater = mProxy.getLayoutInflater();
        inflater.inflate(resourceId, parent, true);
    }

    public View findViewById(int id) {
        return mProxy.findViewById(id);
    }

    public void finish() {
        mProxy.finish();
    }

    public interface OnMenuClickListener {
        /**
         * Called when the menu button is clicked.
         *
         * @return True if event was handled. This will prevent the drawer from executing its
         *         default action (opening/closing/going back). False if the event was not handled
         *         so the drawer will execute the default action.
         */
        boolean onClicked();
    }

    public void setCarMenuCallbacks(final CarMenuCallbacks callbacks) {
        if (mOnCreateCalled) {
            throw new IllegalStateException(
                    "Cannot call setCarMenuCallbacks after onCreate has been called.");
        }
        mMenuCallbacks = callbacks;
    }

    /**
     * Listener that listens for when the menu button is pressed.
     *
     * @param listener {@link OnMenuClickListener} that will listen for menu button clicks.
     */
    public void setOnMenuClickedListener(OnMenuClickListener listener) {
        mBinder.setOnMenuClickedListener(listener);
    }


    public void setTitle(CharSequence title) {
        mUiController.setTitle(title);
    }

    /**
     * Set the System UI to be light.
     */
    public void setLightMode() {
        mUiController.setLightMode();
    }

    /**
     * Set the System UI to be dark.
     */
    public void setDarkMode() {
        mUiController.setDarkMode();
    }

    /**
     * Set the System UI to be dark during day mode and light during night mode.
     */
    public void setAutoLightDarkMode() {
        mUiController.setAutoLightDarkMode();
    }

    /**
     * Sets the application background to the given {@link android.graphics.Bitmap}.
     *
     * @param bitmap to use as background.
     */
    public void setBackground(Bitmap bitmap) {
        mUiController.setBackground(bitmap);
    }

    /**
     * Sets the background to a given resource.
     * The resource should refer to a Drawable object or 0 to remove the background.
     *
     * @param resId The identifier of the resource.
     */
    public void setBackgroundResource(int resId) {
        mUiController.setBackgroundResource(resId);
    }

    public void setScrimColor(int color) {
        mUiController.setScrimColor(color);
    }

    private void registerCarMenuCallbacks(IBinder callbacks) {
        mUiController.registerCarMenuCallbacks(callbacks);
    }

    /** @hide */
    public void dispatchCmd(int cmd, Object arg0) {

        switch (cmd) {
            case CMD_ON_CREATE:
                onCreate((Bundle) arg0);
                break;
            case CMD_ON_START:
                onStart();
                break;
            case CMD_ON_RESTART:
                onRestart();
                break;
            case CMD_ON_RESUME:
                onResume();
                break;
            case CMD_ON_PAUSE:
                onPause();
                break;
            case CMD_ON_STOP:
                onStop();
                break;
            case CMD_ON_DESTROY:
                onDestroy();
                break;
            case CMD_ON_BACK_PRESSED:
                onBackPressed();
                break;
            case CMD_ON_SAVE_INSTANCE_STATE:
                onSaveInstanceState((Bundle) arg0);
                break;
            case CMD_ON_RESTORE_INSTANCE_STATE:
                onRestoreInstanceState((Bundle) arg0);
                break;
            default:
                throw new RuntimeException("Unknown dispatch cmd for CarActivity, " + cmd);
        }

    }

    protected void onCreate(Bundle savedInstanceState) {
        mProxy.setContentView(mUiController.getContentView());
        mBinder = new CarMenuCallbacksBinder(this);
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                registerCarMenuCallbacks(mBinder);
                mOnCreateCalled = true;
            }
        });
    }

    protected void onStart() {
    }

    protected void onRestart() {
    }

    protected void onResume() {
    }

    protected void onPause() {
    }

    protected void onStop() {
    }

    protected void onDestroy() {
    }

    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        mDrawerShowing = savedInstanceState.getBoolean(KEY_DRAWERSHOWING);
        mUiController.onRestoreInstanceState(savedInstanceState);
    }

    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(KEY_DRAWERSHOWING, mDrawerShowing);
        mUiController.onSaveInstanceState(outState);
    }

    protected void onBackPressed() {
    }

    protected Resources getResources() {
        return mProxy.getResources();
    }

    private static class CarMenuCallbacksBinder extends ICarMenuCallbacks.Stub {
        // Map of subscribed ids to their respective callbacks.
        private final Map<String, List<ISubscriptionCallbacks>> mSubscriptionMap = new HashMap<>();
        private OnMenuClickListener mMenuClickListener;
        private final WeakReference<CarActivity> mActivity;

        CarMenuCallbacksBinder(CarActivity activity) {
            mActivity = new WeakReference<>(activity);
        }

        @Override
        public int getVersion() {
            return 0;
        }

        @Override
        public Bundle getRoot() throws RemoteException {
            CarActivity activity = mActivity.get();
            if (activity != null && activity.mMenuCallbacks != null) {
                Root root = activity.mMenuCallbacks.onGetRoot(null);
                return root.getBundle();
            } else {
                return null;
            }
        }

        @Override
        public synchronized void subscribe(final String parentId,
                                           final ISubscriptionCallbacks callbacks) throws RemoteException {
            if (!mSubscriptionMap.containsKey(parentId)) {
                mSubscriptionMap.put(parentId, new ArrayList<ISubscriptionCallbacks>());
            }
            mSubscriptionMap.get(parentId).add(callbacks);
            loadResultsForClient(parentId, callbacks);
        }

        @Override
        public synchronized void unsubscribe(String id, ISubscriptionCallbacks callbacks)
                throws RemoteException {
            mSubscriptionMap.get(id).remove(callbacks);
        }

        @Override
        public void onCarMenuOpened() throws RemoteException {
            CarActivity activity = mActivity.get();
            if (activity != null) {
                activity.mDrawerShowing = true;
                activity.mMenuCallbacks.onCarMenuOpened();
            }
        }


        @Override
        public void onCarMenuClosing() throws RemoteException {
        }

        @Override
        public void onCarMenuClosed() throws RemoteException {
            CarActivity activity = mActivity.get();
            activity.mDrawerShowing = false;
        }

        @Override
        public void onCarMenuOpening() throws RemoteException {
            CarActivity activity = mActivity.get();
            activity.mDrawerShowing = true;
        }

        @Override
        public void onItemClicked(String id) throws RemoteException {
            CarActivity activity = mActivity.get();
            if (activity != null) {
                activity.mMenuCallbacks.onItemClicked(id);
                // TODO: Add support for IME
                // activity.stopInput();
            }
        }

        @Override
        public boolean onItemLongClicked(String id) throws RemoteException {
            CarActivity activity = mActivity.get();
            if (activity != null) {
                return activity.mMenuCallbacks.onItemLongClicked(id);
            } else {
                return false;
            }
        }

        @Override
        public void onStateChanged(int newState) throws RemoteException {
            CarActivity activity = mActivity.get();
            if (activity != null) {
                activity.mMenuCallbacks.onStateChanged(newState);
            }
        }

        public void setOnMenuClickedListener(OnMenuClickListener listener) {
            mMenuClickListener = listener;
        }

        @Override
        public boolean onMenuClicked() {
            CarActivity activity = mActivity.get();
            if (activity == null) {
                return false;
            }

            if (mMenuClickListener != null) {
                if (mMenuClickListener.onClicked()) {
                    return true;
                }
            }
            return false;
        }

        public void onChildrenChanged(String parentId) {
            if (mSubscriptionMap.containsKey(parentId)) {
                loadResultsForAllClients(parentId);
            }
        }

        public void reloadSubscribedMenus() {
            for (String parentId : mSubscriptionMap.keySet()) {
                loadResultsForAllClients(parentId);
            }
        }

        private void loadResultsForClient(final String parentId,
                                          final ISubscriptionCallbacks callbacks) {
            CarActivity activity = mActivity.get();
            if (activity == null) {
                return;
            }

            final CarMenu result = new CarMenu(activity.getResources().getDisplayMetrics()) {
                @Override
                protected void onResultReady(List<Bundle> list) {
                    synchronized (CarMenuCallbacksBinder.this) {
                        try {
                            callbacks.onChildrenLoaded(parentId, list);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Error calling onChildrenLoaded: ", e);
                        }
                    }
                }
            };

            activity.mMenuCallbacks.onLoadChildren(parentId, result);
            if (!result.isDone()) {
                throw new IllegalStateException("You must either call sendResult() or detach() " +
                        "before returning!");
            }
        }

        private void loadResultsForAllClients(final String parentId) {
            CarActivity activity = mActivity.get();
            if (activity == null) {
                return;
            }

            final CarMenu result = new CarMenu(activity.getResources().getDisplayMetrics()) {
                @Override
                protected void onResultReady(List<Bundle> list) {
                    synchronized (CarMenuCallbacksBinder.this) {
                        if (mSubscriptionMap.containsKey(parentId)) {
                            try {
                                for (ISubscriptionCallbacks callbacks :
                                        mSubscriptionMap.get(parentId)) {
                                    callbacks.onChildrenLoaded(parentId, list);
                                }
                            } catch (RemoteException e) {
                                Log.e(TAG, "Error calling onChildrenLoaded: ", e);
                            }
                        }
                    }
                }
            };

            activity.mMenuCallbacks.onLoadChildren(parentId, result);
        }

        private synchronized void onChildChanged(String parentId, Bundle item) {
            if (mSubscriptionMap.containsKey(parentId)) {
                for (ISubscriptionCallbacks callbacks : mSubscriptionMap.get(parentId)) {
                    try {
                        callbacks.onChildChanged(parentId, item);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error calling onChildChanged: ", e);
                    }
                }
            }
        }
    }
}
