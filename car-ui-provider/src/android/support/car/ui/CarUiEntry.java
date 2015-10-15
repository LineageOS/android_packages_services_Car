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
package android.support.car.ui;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.car.app.menu.Constants;
import android.support.car.ui.drawer.CarDrawerLayout;
import android.support.car.ui.drawer.DrawerController;
import android.support.v7.widget.CardView;
import android.util.Log;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.View;
import android.content.Context;
import android.view.LayoutInflater;

import android.support.car.app.menu.ICarMenuCallbacks;

public class CarUiEntry {
    private static final String TAG = "CarUiEntry";
    private final Context mUiLibContext;
    @SuppressWarnings("unused")
    private final Context mAppContext;

    private View mContentView;
    private ImageView mMenuButton;
    private TextView mTitleView;
    private CardView mTruncatedListCardView;
    private CarDrawerLayout mDrawerLayout;
    private DrawerController mDrawerController;
    private PagedListView mListView;
    private DrawerArrowDrawable mDrawerArrowDrawable;

    public CarUiEntry(Context uiLibContext, Context appContext) {
        mAppContext = appContext;
        mUiLibContext = uiLibContext.createConfigurationContext(
                appContext.getResources().getConfiguration());
    }

    public View getContentView() {
        LayoutInflater inflater = LayoutInflater.from(mUiLibContext);
        mContentView = inflater.inflate(R.layout.car_activity, null);
        mDrawerLayout = (CarDrawerLayout) mContentView.findViewById(R.id.drawer_container);
        adjustDrawer();
        mMenuButton = (ImageView) mContentView.findViewById(R.id.car_drawer_button);
        mTitleView = (TextView) mContentView.findViewById(R.id.car_drawer_title);
        mTruncatedListCardView = (CardView) mContentView.findViewById(R.id.truncated_list_card);
        mDrawerArrowDrawable = new DrawerArrowDrawable(mUiLibContext);
        restoreMenuDrawable();
        mListView = (PagedListView) mContentView.findViewById(R.id.list_view);
        mListView.setOnScrollBarListener(mOnScrollBarListener);
        mMenuButton.setOnClickListener(mMenuListener);
        mDrawerController = new DrawerController(this, mMenuButton,
                 mDrawerLayout, mListView, mTruncatedListCardView);
        return mContentView;
    }
    private final View.OnClickListener mMenuListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            CarUiEntry.this.mDrawerLayout.openDrawer();
        }
    };

    public void setCarMenuBinder(IBinder binder) throws RemoteException {
        ICarMenuCallbacks callbacks = ICarMenuCallbacks.Stub.asInterface(binder);
        Bundle root = callbacks.getRoot();
        if (root != null) {
            mDrawerController.setRootAndCallbacks(root.getString(Constants.CarMenuConstants.KEY_ID), callbacks);
            mDrawerController.setDrawerEnabled(true);
        } else {
            hideMenuButton();
        }
    }

    public int getFragmentContainerId() {
        return R.id.container;
    }

    public void setBackground(Bitmap bitmap) {
        BitmapDrawable bd = new BitmapDrawable(mUiLibContext.getResources(), bitmap);
        ImageView bg = (ImageView) mContentView.findViewById(R.id.background);
        bg.setBackground(bd);
    }

    public void setBackgroundResource(int resId) {
        ImageView bg = (ImageView) mContentView.findViewById(R.id.background);
        bg.setBackgroundResource(resId);
    }

    public void hideMenuButton() {
        mMenuButton.setVisibility(View.GONE);
    }

    public void restoreMenuDrawable() {
        mMenuButton.setImageDrawable(mDrawerArrowDrawable);
    }

    /**
     * Set the progress of the animated {@link DrawerArrowDrawable}.
     * @param progress 0f displays a menu button
     *                 1f displays a back button
     *                 anything in between will be an interpolation of the drawable between
     *                 back and menu
     */
    public void setMenuProgress(float progress) {
        mDrawerArrowDrawable.setProgress(progress);
    }

    public void setScrimColor(int color) {
        mDrawerLayout.setScrimColor( color);
    }

    public void setTitle(CharSequence title) {
        mDrawerController.setTitle(title);
    }

    public void setTitleText(CharSequence title) {
        mTitleView.setText(title);
    }

    public void closeDrawer() {
        mDrawerLayout.closeDrawer();
    }

    public void openDrawer() {
        mDrawerLayout.openDrawer();
    }

    private void adjustDrawer() {
        Resources resources = mUiLibContext.getResources();
        float width = resources.getDisplayMetrics().widthPixels;
        CarDrawerLayout.LayoutParams layoutParams = new CarDrawerLayout.LayoutParams(
                CarDrawerLayout.LayoutParams.MATCH_PARENT,
                CarDrawerLayout.LayoutParams.MATCH_PARENT);
        layoutParams.gravity = Gravity.LEFT;
        // 1. If the screen width is larger than 800dp, the drawer width is kept as 704dp;
        // 2. Else the drawer width is adjusted to keep the margin end of drawer as 96dp.
        if (width > resources.getDimension(R.dimen.car_standard_width)) {
            layoutParams.setMarginEnd(
                    (int) (width - resources.getDimension(R.dimen.car_drawer_standard_width)));
        } else {
            layoutParams.setMarginEnd(
                    (int) resources.getDimension(R.dimen.car_card_margin));
        }
        mContentView.findViewById(R.id.drawer).setLayoutParams(layoutParams);
    }

    private final PagedListView.OnScrollBarListener mOnScrollBarListener =
            new PagedListView.OnScrollBarListener() {

                @Override
                public void onReachBottom() {
                    if (mDrawerController.isTruncatedList()) {
                        mTruncatedListCardView.setVisibility(View.VISIBLE);
                    }
                }

                @Override
                public void onLeaveBottom() {
                    mTruncatedListCardView.setVisibility(View.GONE);
                }
            };

    public void setMenuButtonColor(int color) {
        setViewColor(mMenuButton, color);
        setViewColor(mTitleView, color);
    }

    public void showTitle() {
        mTitleView.setVisibility(View.VISIBLE);
    }

    public void hideTitle() {
        mTitleView.setVisibility(View.GONE);
    }

    public void setLightMode() {
        mDrawerController.setLightMode();
    }

    public void setDarkMode() {
        mDrawerController.setDarkMode();
    }

    public void setAutoLightDarkMode() {
        mDrawerController.setAutoLightDarkMode();
    }

    public void onRestoreInstanceState(Bundle savedInstanceState) {
        if (mDrawerController != null) {
            mDrawerController.restoreState(savedInstanceState);
        }
    }

    public void onSaveInstanceState(Bundle outState) {
        if (mDrawerController != null) {
            mDrawerController.saveState(outState);
        }
    }

    private static void setViewColor(View view, int color) {
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(color);
        } else if (view instanceof ImageView) {
            ImageView imageView = (ImageView) view;
            PorterDuffColorFilter filter =
                    new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
            imageView.setColorFilter(filter);
        } else {
            if (Log.isLoggable(TAG, Log.WARN)) {
                Log.w(TAG, "Setting color is only supported for TextView and ImageView.");
            }
        }
    }
}
