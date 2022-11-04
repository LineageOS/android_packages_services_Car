/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.chassis.car.ui.plugin.toolbar;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;

import com.android.car.ui.plugin.oemapis.BiConsumer;
import com.android.car.ui.plugin.oemapis.Consumer;
import com.android.car.ui.plugin.oemapis.toolbar.ImeSearchInterfaceOEMV2;
import com.android.car.ui.plugin.oemapis.toolbar.MenuItemOEMV1;
import com.android.car.ui.plugin.oemapis.toolbar.ProgressBarControllerOEMV1;
import com.android.car.ui.plugin.oemapis.toolbar.TabOEMV1;
import com.android.car.ui.plugin.oemapis.toolbar.ToolbarControllerOEMV1;
import com.android.car.ui.plugin.oemapis.toolbar.ToolbarControllerOEMV2;

import com.chassis.car.ui.plugin.R;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SuppressWarnings("AndroidJdkLibsChecker")
class ToolbarControllerImpl implements ToolbarControllerOEMV2 {

    private final Context mPluginContext;
    private final ImageView mBackButtonView;
    private final TextView mTitleView;
    private final TextView mSubtitleView;
    private final ImageView mLogo;
    private final ProgressBarController mProgressBar;
    private final TabLayout mTabContainer;
    private final ViewGroup mMenuItemsContainer;
    private final SearchController mSearchController;
    private final ViewGroup mNavIconContainer;
    private final View mBackground;
    private final View mNavIconSpacer;
    private final View mLogoSpacer;

    private final boolean mTitleAndTabsMutuallyExclusive;

    private Runnable mBackListener;

    private boolean mBackButtonVisible = false;
    private boolean mHasLogo = false;
    private int mSearchMode = ToolbarControllerOEMV1.SEARCH_MODE_DISABLED;
    private final OverflowMenuItem mOverflowMenuItem;

    ToolbarControllerImpl(View view, Context pluginContext, Context sourceContext) {
        mPluginContext = pluginContext;
        mBackButtonView = view.requireViewById(R.id.toolbar_nav_icon);
        mNavIconContainer = view.requireViewById(R.id.toolbar_nav_icon_container);
        mTitleView = view.requireViewById(R.id.toolbar_title);
        mSubtitleView = view.requireViewById(R.id.toolbar_subtitle);
        mLogo = view.requireViewById(R.id.toolbar_title_logo);
        mProgressBar = new ProgressBarController(
                view.requireViewById(R.id.toolbar_progress_bar));
        mTabContainer = view.requireViewById(R.id.toolbar_tabs);
        mMenuItemsContainer = view.requireViewById(R.id.toolbar_menu_items_container);
        mBackground = view.requireViewById(R.id.toolbar_background);
        mNavIconSpacer = view.requireViewById(R.id.toolbar_nav_icon_spacer);
        mLogoSpacer = view.requireViewById(R.id.toolbar_logo_spacer);
        mSearchController = new SearchController(
                view.requireViewById(R.id.toolbar_search_view_stub));
        mOverflowMenuItem = new OverflowMenuItem(pluginContext,
                view.requireViewById(R.id.toolbar_dialog_stub));

        mTitleAndTabsMutuallyExclusive = pluginContext.getResources()
                .getBoolean(R.bool.toolbar_title_and_tabs_mutually_exclusive);
        update();
    }

    @Override
    public void setTitle(String title) {
        boolean hadTitle = !TextUtils.isEmpty(getTitle());
        mTitleView.setText(title);
        boolean hasTitle = !TextUtils.isEmpty(getTitle());

        if (hadTitle != hasTitle) {
            update();
        }
    }

    private CharSequence getTitle() {
        return mTitleView.getText();
    }

    @Override
    public void setSubtitle(String title) {
        boolean hadSubtitle = !TextUtils.isEmpty(getSubtitle());
        mSubtitleView.setText(title);
        boolean hasSubtitle = !TextUtils.isEmpty(getSubtitle());

        if (hadSubtitle != hasSubtitle) {
            update();
        }
    }

    private CharSequence getSubtitle() {
        return mSubtitleView.getText();
    }

    @Override
    public void setTabs(List<TabOEMV1> tabs, int selectedTab) {
        if (tabs == null) {
            tabs = Collections.emptyList();
        }

        boolean hadTabs = mTabContainer.hasTabs();
        mTabContainer.setTabs(tabs, selectedTab);
        boolean hasTabs = mTabContainer.hasTabs();

        if (hadTabs != hasTabs) {
            update();
        }
    }

    @Override
    public void selectTab(int position, boolean sendCallback) {
        mTabContainer.selectTab(position, sendCallback);
    }

    @Override
    public void setLogo(Drawable drawable) {
        mLogo.setImageDrawable(drawable);

        boolean hasLogo = drawable != null;
        if (hasLogo != mHasLogo) {
            mHasLogo = hasLogo;
            update();
        }
    }

    @Override
    public void setSearchHint(@Nullable String hint) {
        mSearchController.setSearchHint(hint);
    }

    @Override
    public void setSearchIcon(@Nullable Drawable d) {
        mSearchController.setSearchIcon(d);
    }

    @Override
    public void setSearchQuery(@Nullable String query) {
        mSearchController.setSearchQuery(query);
    }

    @Override
    public void setSearchMode(int searchMode) {
        if (mSearchMode != searchMode) {
            mSearchMode = searchMode;
            mSearchController.setSearchMode(searchMode);
            update();
        }
    }

    @Override
    public ImeSearchInterfaceOEMV2 getImeSearchInterface() {
        return new ImeSearchInterfaceOEMV2() {
            @Override
            public void setSearchTextViewConsumer(Consumer<TextView> textViewConsumer) {
                mSearchController.setSearchTextViewConsumer(textViewConsumer);
            }

            @Override
            public void setOnPrivateImeCommandListener(
                    BiConsumer<String, Bundle> onPrivateImeCommandListener) {
                mSearchController.setOnPrivateImeCommandListener(onPrivateImeCommandListener);
            }
        };
    }

    @Override
    public void setNavButtonMode(int mode) {
        boolean visible = mode != NAV_BUTTON_MODE_DISABLED;
        if (visible != mBackButtonVisible) {
            mBackButtonVisible = visible;
            mNavIconContainer.setOnClickListener(mBackButtonVisible ? v -> {
                if (mBackListener != null) {
                    mBackListener.run();
                }
            } : null);
            mNavIconContainer.setClickable(mBackButtonVisible);

            mNavIconContainer.setContentDescription(mBackButtonVisible
                    ? mPluginContext
                    .getString(R.string.toolbar_nav_icon_content_description)
                    : null);
            update();
        }

        switch (mode) {
            case ToolbarControllerOEMV1.NAV_BUTTON_MODE_CLOSE:
                mBackButtonView.setImageResource(R.drawable.icon_close);
                break;
            case ToolbarControllerOEMV1.NAV_BUTTON_MODE_DOWN:
                mBackButtonView.setImageResource(R.drawable.icon_down);
                break;
            default:
                mBackButtonView.setImageResource(R.drawable.icon_back);
                break;
        }
    }

    @Override
    public void setMenuItems(List<MenuItemOEMV1> menuItems) {
        if (menuItems == null) {
            menuItems = Collections.emptyList();
        }

        List<MenuItemOEMV1> overflowMenuItems = menuItems.stream()
                .filter(i -> i.getDisplayBehavior() != MenuItemOEMV1.DISPLAY_BEHAVIOR_ALWAYS)
                .collect(Collectors.toList());

        mOverflowMenuItem.setOverflowMenuItems(overflowMenuItems);

        List<MenuItemOEMV1> regularMenuItems = Stream.concat(
                        menuItems.stream(),
                        Stream.of(mOverflowMenuItem.getMenuItem()))
                .filter(i -> i.getDisplayBehavior() == MenuItemOEMV1.DISPLAY_BEHAVIOR_ALWAYS)
                .collect(Collectors.toList());

        mMenuItemsContainer.removeAllViews();
        for (MenuItemOEMV1 menuItem : regularMenuItems) {
            MenuItemView menuItemView = new MenuItemView(mPluginContext, menuItem);
            mMenuItemsContainer.addView(menuItemView,
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    @Override
    public void setSearchListener(@Nullable Consumer<String> listener) {
        mSearchController.setSearchListener(listener);
    }

    @Override
    public void setSearchCompletedListener(@Nullable Runnable listener) {
        mSearchController.setSearchCompletedListener(listener);
    }

    @Override
    public void setBackListener(Runnable listener) {
        mBackListener = listener;
    }

    @Override
    public ProgressBarControllerOEMV1 getProgressBar() {
        return mProgressBar;
    }

    @Override
    public void setBackgroundShown(boolean shown) {
        if (shown) {
            mBackground.setBackground(
                    AppCompatResources.getDrawable(mPluginContext, R.drawable.toolbar_background));
        } else {
            mBackground.setBackground(null);
        }
    }

    private void update() {
        boolean isSearching = mSearchMode != ToolbarControllerOEMV1.SEARCH_MODE_DISABLED;
        boolean hasTabs = mTabContainer.hasTabs();

        Log.e("ToolbarControllerImpl", "HasTabs: " + hasTabs);
        if (hasTabs) {
            Log.e("ToolbarControllerImpl", "Tab count: " + mTabContainer.getTabCount());
        }

        setVisible(mLogoSpacer, !hasTabs && mBackButtonVisible);
        setVisible(mNavIconSpacer, hasTabs && mBackButtonVisible);
        setVisible(mBackButtonView, mBackButtonVisible);
        setVisible(mLogo, mHasLogo);
        setVisible(mNavIconContainer, mBackButtonVisible);
        setVisible(mTabContainer, hasTabs && !isSearching);
        setVisible(mTitleView, !TextUtils.isEmpty(getTitle()) && !isSearching
                && (!hasTabs || !mTitleAndTabsMutuallyExclusive));
        setVisible(mSubtitleView, !TextUtils.isEmpty(getSubtitle()) && !isSearching
                && (!hasTabs || !mTitleAndTabsMutuallyExclusive));
    }

    private static void setVisible(View view, boolean visible) {
        if (view != null) {
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
}
