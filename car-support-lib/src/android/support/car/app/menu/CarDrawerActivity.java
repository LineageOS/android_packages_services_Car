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

package android.support.car.app.menu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.annotation.LayoutRes;
import android.support.car.app.CarFragmentActivity;
import android.support.car.input.CarEditable;
import android.support.car.input.CarEditableListener;
import android.support.car.input.CarInputManager;
import android.support.car.input.CarRestrictedEditText;
import android.support.v4.app.Fragment;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;


import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for a car app which wants to use a drawer.
 */
public abstract class CarDrawerActivity extends CarFragmentActivity {
    private static final String TAG = "CarDrawerActivity";
    private static final String KEY_DRAWERSHOWING =
            "android.support.car.app.CarDrawerActivity.DRAWER_SHOWING";
    private static final String KEY_INPUTSHOWING =
            "android.support.car.app.CarDrawerActivity.INPUT_SHOWING";
    private static final String KEY_SEARCHBOXENABLED =
            "android.support.car.app.CarDrawerActivity.SEARCH_BOX_ENABLED";

    private final Handler mHandler = new Handler();
    private final CarUiController mUiController;

    private CarMenuCallbacks mMenuCallbacks;
    private CarMenuCallbacksBinder mBinder;
    private boolean mDrawerShowing;
    private boolean mShowingSearchBox;
    private boolean mSearchBoxEnabled;
    private boolean mOnCreateCalled = false;
    private View.OnClickListener mSearchBoxOnClickListener;

    private CarInputManager mInputManager;
    private EditText mSearchBoxView;

    /**
     * Simple interface to listen for keyboard events.
     */
    public interface SearchBoxEditListener {
        /**
         * The user hit enter on the keyboard.
         */
        void onSearch(String text);

        /**
         * The user changed the text in the search box with the keyboard.
         */
        void onEdit(String text);
    }

    public CarDrawerActivity(Proxy proxy, Context context) {
        super(proxy, context);
        mUiController = new CarUiController(this);
    }

    @Override
    public void setContentView(View view) {
        ViewGroup parent = (ViewGroup) findViewById(mUiController.getFragmentContainerId());
        parent.addView(view);
    }

    @Override
    public void setContentView(@LayoutRes int resourceId) {
        ViewGroup parent = (ViewGroup) findViewById(mUiController.getFragmentContainerId());
        LayoutInflater inflater = getLayoutInflater();
        inflater.inflate(resourceId, parent, true);
    }

    public void setContentFragment(Fragment fragment) {
        super.setContentFragment(fragment, mUiController.getFragmentContainerId());
    }

    @Override
    public View findViewById(@LayoutRes int id) {
        return super.findViewById(mUiController.getFragmentContainerId()).findViewById(id);
    }

    @Override
    protected void onStart() {
        super.onStart();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        mUiController.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mUiController.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mUiController.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mUiController.onStop();
    }

    public int getFragmentContainerId() {
        return mUiController.getFragmentContainerId();
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

    /**
     * Restore the menu button drawable
     */
    public void restoreMenuButtonDrawable() {
        mUiController.restoreMenuButtonDrawable();
    }

    /**
     * Sets the menu button bitmap
     *
     * @param bitmap Bitmap to the menu button to.
     */
    public void setMenuButtonBitmap(Bitmap bitmap) {
        mUiController.setMenuButtonBitmap(bitmap);
    }

    /**
     * Set the title of the menu.
     */
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

    /**
     * Show the menu associated with the given id in the drawer.
     *
     * @param id Id of the menu to link to.
     * @param title Title that should be displayed.
     */
    public void showMenu(String id, String title) {
        mUiController.showMenu(id, title);
    }

    private void registerCarMenuCallbacks(IBinder callbacks) {
        mUiController.registerCarMenuCallbacks(callbacks);
    }

    private final CarMenuCallbacks.OnChildrenChangedListener mMenuListener =
            new CarMenuCallbacks.OnChildrenChangedListener() {
                @Override
                public void onChildrenChanged(String parentId) {
                    if (mOnCreateCalled) {
                        mBinder.onChildrenChanged(parentId);
                    }
                }

                @Override
                public void onChildChanged(String parentId, Bundle item,
                                           Drawable leftIcon, Drawable rightIcon) {
                    DisplayMetrics metrics = getResources().getDisplayMetrics();
                    if (leftIcon != null) {
                        item.putParcelable(Constants.CarMenuConstants.KEY_LEFTICON,
                                Utils.snapshot(metrics, leftIcon));
                    }

                    if (rightIcon != null) {
                        item.putParcelable(Constants.CarMenuConstants.KEY_RIGHTICON,
                                Utils.snapshot(metrics, rightIcon));
                    }
                    if (mOnCreateCalled) {
                        mBinder.onChildChanged(parentId, item);
                    }
                }
            };

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        super.setContentView(mUiController.getContentView());
        mBinder = new CarMenuCallbacksBinder(this);
        mInputManager = getInputManager();
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                registerCarMenuCallbacks(mBinder);
                if (mMenuCallbacks != null) {
                    mMenuCallbacks.registerOnChildrenChangedListener(mMenuListener);
                }
                mOnCreateCalled = true;
            }
        });
    }

    protected void onDestroy() {
        super.onDestroy();
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mMenuCallbacks != null) {
                    mMenuCallbacks.unregisterOnChildrenChangedListener(mMenuListener);
                    mMenuCallbacks = null;
                }
            }
        });
    }

    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        mDrawerShowing = savedInstanceState.getBoolean(KEY_DRAWERSHOWING);
        mUiController.onRestoreInstanceState(savedInstanceState);
    }

    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_DRAWERSHOWING, mDrawerShowing);
        mUiController.onSaveInstanceState(outState);
    }

    public void closeDrawer() {
        mUiController.closeDrawer();
    }

    public void openDrawer() {
        mUiController.openDrawer();
    }

    public boolean isDrawerShowing() {
        return mDrawerShowing;
    }

    /**
     * Shows a small clickable {@link android.widget.EditText}.
     *
     * {@link View} will be {@code null} in {@link View.OnClickListener#onClick(View)}.
     *
     * @param listener {@link View.OnClickListener} that is called when user selects the
     *                 {@link android.widget.EditText}.
     */
    public void showSearchBox(View.OnClickListener listener) {
        if (!isDrawerShowing()) {
            mUiController.showSearchBox(listener);
            mShowingSearchBox = true;
        }
        mSearchBoxEnabled = true;
        mSearchBoxOnClickListener = listener;
    }

    public void hideSearchBox() {
        if (isShowingSearchBox()) {
            stopInput();
        }
        mSearchBoxEnabled = false;
    }

    public boolean isShowingSearchBox() {
        return mShowingSearchBox;
    }

    public void setSearchBoxEditListener(SearchBoxEditListener listener) {
        mUiController.setSearchBoxEditListener(new SearchBoxEditListenerBinder(listener));
    }

    public void stopInput() {
        // STOPSHIP: sometimes focus is lost and we are not able to hide the keyboard.
        // properly fix this before we ship.
        if (mSearchBoxView != null) {
            mSearchBoxView.requestFocusFromTouch();
        }
        mUiController.stopInput();
        mInputManager.stopInput();
        mShowingSearchBox = false;
    }

    /**
     * Start input on the search box that is provided by a car ui provider.
     * TODO: Migrate to use the new input/search api once it becomes stable (b/27108311).
     * @param hint Search hint
     */
    public void startInput(String hint) {
        startInput(hint, mSearchBoxOnClickListener);
    }

    /**
     * Start input on the search box that is provided by a car ui provider.
     * TODO: Migrate to use the new input/search api once it becomes stable (b/27108311).
     * @param hint Search hint
     * @param onClickListener Listener for the search box clicks.
     */
    public void startInput(final String hint, final View.OnClickListener onClickListener) {
        mInputManager = getInputManager();
        EditText inputView = mUiController.startSearchInput(hint, onClickListener);
        getInputManager().startInput(inputView);
        mSearchBoxView = inputView;
        mShowingSearchBox = true;
    }

    public void setSearchBoxColors(int backgroundColor, int googleLogoColor, int textColor,
            int hintTextColor) {
        mUiController.setSearchBoxColors(backgroundColor, googleLogoColor, textColor, hintTextColor);
    }

    public void setSearchBoxEndView(View endView) {
        mUiController.setSearchBoxEndView(endView);
    }

    public void showToast(String text, int duration) {
    }

    private static class SearchBoxEditListenerBinder extends ISearchBoxEditListener.Stub {
        private final SearchBoxEditListener mListener;

        public SearchBoxEditListenerBinder(SearchBoxEditListener listener) {
            if (listener == null) {
                throw new IllegalArgumentException("Listener cannot be null");
            }
            mListener = listener;
        }

        @Override
        public void onSearch(String text) {
            mListener.onSearch(text);
        }

        @Override
        public void onEdit(String text) {
            mListener.onEdit(text);
        }
    }

    private static class CarMenuCallbacksBinder extends ICarMenuCallbacks.Stub {
        // Map of subscribed ids to their respective callbacks.
        private final Map<String, List<ISubscriptionCallbacks>> mSubscriptionMap = new HashMap<>();
        private OnMenuClickListener mMenuClickListener;
        private final WeakReference<CarDrawerActivity> mActivity;

        CarMenuCallbacksBinder(CarDrawerActivity activity) {
            mActivity = new WeakReference<>(activity);
        }

        @Override
        public int getVersion() {
            return 0;
        }

        @Override
        public Bundle getRoot() throws RemoteException {
            CarDrawerActivity activity = mActivity.get();
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
            CarDrawerActivity activity = mActivity.get();
            if (activity != null) {
                activity.mDrawerShowing = true;
                activity.mMenuCallbacks.onCarMenuOpened();
            }
        }


        @Override
        public void onCarMenuClosing() throws RemoteException {
            CarDrawerActivity activity = mActivity.get();
            if (activity != null) {
                if (activity.mSearchBoxEnabled) {
                    activity.mUiController.showSearchBox(activity.mSearchBoxOnClickListener);
                    activity.mShowingSearchBox = true;
                }
            }
        }

        @Override
        public void onCarMenuClosed() throws RemoteException {
            CarDrawerActivity activity = mActivity.get();
            if (activity != null) {
                activity.mDrawerShowing = false;
                if (activity.mSearchBoxEnabled && !activity.mShowingSearchBox) {
                    activity.showSearchBox(activity.mSearchBoxOnClickListener);
                }
            }
        }

        @Override
        public void onCarMenuOpening() throws RemoteException {
            CarDrawerActivity activity = mActivity.get();
            if (activity != null) {
                activity.stopInput();
            }
        }

        @Override
        public void onItemClicked(String id) throws RemoteException {
            CarDrawerActivity activity = mActivity.get();
            if (activity != null) {
                activity.mMenuCallbacks.onItemClicked(id);
                // TODO: Add support for IME
                activity.stopInput();
            }
        }

        @Override
        public boolean onItemLongClicked(String id) throws RemoteException {
            CarDrawerActivity activity = mActivity.get();
            if (activity != null) {
                return activity.mMenuCallbacks.onItemLongClicked(id);
            } else {
                return false;
            }
        }

        @Override
        public void onStateChanged(int newState) throws RemoteException {
            CarDrawerActivity activity = mActivity.get();
            if (activity != null) {
                activity.mMenuCallbacks.onStateChanged(newState);
            }
        }

        public void setOnMenuClickedListener(OnMenuClickListener listener) {
            mMenuClickListener = listener;
        }

        @Override
        public boolean onMenuClicked() {
            CarDrawerActivity activity = mActivity.get();
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
            CarDrawerActivity activity = mActivity.get();
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
            CarDrawerActivity activity = mActivity.get();
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
