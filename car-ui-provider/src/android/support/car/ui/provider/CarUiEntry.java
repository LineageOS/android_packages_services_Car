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
package android.support.car.ui.provider;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.car.app.menu.Constants;
import android.support.car.ui.DrawerArrowDrawable;
import android.support.car.ui.PagedListView;
import android.support.v7.widget.CardView;
import android.util.Log;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.View;
import android.view.LayoutInflater;

import android.support.car.ui.R;

import android.support.car.app.menu.ICarMenuCallbacks;

public class CarUiEntry extends android.support.car.app.menu.CarUiEntry {
    private static final String TAG = "Embedded_CarUiEntry";

    private View mContentView;
    private ImageView mMenuButton;
    private TextView mTitleView;
    private CardView mTruncatedListCardView;
    private CarDrawerLayout mDrawerLayout;
    private DrawerController mDrawerController;
    private PagedListView mListView;
    private DrawerArrowDrawable mDrawerArrowDrawable;

    public CarUiEntry(Context appContext, Context providerContext) {
        super(appContext, providerContext);
    }

    @Override
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
            CarUiEntry.this.mDrawerController.openDrawer();
        }
    };

    @Override
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

    @Override
    public int getFragmentContainerId() {
        return R.id.container;
    }

    @Override
    public void setBackground(Bitmap bitmap) {
        BitmapDrawable bd = new BitmapDrawable(mUiLibContext.getResources(), bitmap);
        ImageView bg = (ImageView) mContentView.findViewById(R.id.background);
        bg.setBackground(bd);
    }

    @Override
    public void setBackgroundResource(int resId) {
        ImageView bg = (ImageView) mContentView.findViewById(R.id.background);
        bg.setBackgroundResource(resId);
    }

    @Override
    public void hideMenuButton() {
        mMenuButton.setVisibility(View.GONE);
    }

    @Override
    public void restoreMenuDrawable() {
        mMenuButton.setImageDrawable(mDrawerArrowDrawable);
    }

    public void setMenuButtonBitmap(Bitmap bitmap) {
        mMenuButton.setImageDrawable(new BitmapDrawable(mUiLibContext.getResources(), bitmap));
    }

    /**
     * Set the progress of the animated {@link DrawerArrowDrawable}.
     * @param progress 0f displays a menu button
     *                 1f displays a back button
     *                 anything in between will be an interpolation of the drawable between
     *                 back and menu
     */
    @Override
    public void setMenuProgress(float progress) {
        mDrawerArrowDrawable.setProgress(progress);
    }

    @Override
    public void setScrimColor(int color) {
        mDrawerLayout.setScrimColor( color);
    }

    @Override
    public void setTitle(CharSequence title) {
        mDrawerController.setTitle(title);
    }

    @Override
    public void setTitleText(CharSequence title) {
        mTitleView.setText(title);
    }

    @Override
    public void closeDrawer() {
        mDrawerController.closeDrawer();
    }

    @Override
    public void openDrawer() {
        mDrawerController.openDrawer();
    }

    @Override
    public void showMenu(String id, String title) {
        mDrawerController.showMenu(id, title);
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

    @Override
    public void setMenuButtonColor(int color) {
        setViewColor(mMenuButton, color);
        setViewColor(mTitleView, color);
    }

    @Override
    public void showTitle() {
        mTitleView.setVisibility(View.VISIBLE);
    }

    @Override
    public void hideTitle() {
        mTitleView.setVisibility(View.GONE);
    }

    @Override
    public void setLightMode() {
        mDrawerController.setLightMode();
    }

    @Override
    public void setDarkMode() {
        mDrawerController.setDarkMode();
    }

    @Override
    public void setAutoLightDarkMode() {
        mDrawerController.setAutoLightDarkMode();
    }

    @Override
    public void onRestoreInstanceState(Bundle savedInstanceState) {
        if (mDrawerController != null) {
            mDrawerController.restoreState(savedInstanceState);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (mDrawerController != null) {
            mDrawerController.saveState(outState);
        }
    }

    @Override
    public void onStart() {

    }

    @Override
    public void onResume() {

    }

    @Override
    public void onPause() {

    }

    @Override
    public void onStop() {

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
