/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.activityresolver;

import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ListView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.android.internal.R;
import com.android.internal.app.ResolverActivity;
import com.android.internal.app.ResolverViewPager;
import com.android.internal.widget.ResolverDrawerLayout;

/**
 * An automotive variant of the resolver activity which does not use the safe forwarding mode and
 * which supports rotary.
 */
public final class CarResolverActivity extends ResolverActivity
        implements ViewTreeObserver.OnGlobalLayoutListener {

    private ResolverViewPager mProfilePager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setSafeForwardingMode(false);

        mProfilePager = findViewById(R.id.profile_pager);
        mProfilePager.getViewTreeObserver().addOnGlobalLayoutListener(this);

        ((ResolverDrawerLayout) findViewById(R.id.contentPanel)).setShowAtTop(true);
        setupSystemBarInsets();
        getWindow().setBackgroundBlurRadius(getResources().getDimensionPixelSize(
                com.android.car.activityresolver.R.dimen.background_blur_radius));
    }

    /**
     * Override to use corresponding Car optimized layouts (supporting rotary) for content view.
     */
    @Override
    public void setContentView(int layoutResID) {
        int carLayoutResId = layoutResID;
        switch (layoutResID) {
            case R.layout.resolver_list:
                carLayoutResId = com.android.car.activityresolver.R.layout.resolver_list;
                break;
            case R.layout.resolver_list_with_default:
                carLayoutResId =
                    com.android.car.activityresolver.R.layout.resolver_list_with_default;
                break;
        }

        super.setContentView(carLayoutResId);
    }

    @Override
    protected void onDestroy() {
        mProfilePager.getViewTreeObserver().removeOnGlobalLayoutListener(this);
        super.onDestroy();
    }

    @Override
    public void onGlobalLayout() {
        ListView listView = findViewById(R.id.resolver_list);
        if (listView != null) {
            // Items must be focusable for rotary.
            listView.setItemsCanFocus(true);

            // Set click listeners for rotary.
            for (int i = 0; i < listView.getChildCount(); i++) {
                View element = listView.getChildAt(i);
                element.setOnClickListener(view -> {
                    int position = listView.getPositionForView(view);
                    long id = listView.getItemIdAtPosition(position);
                    listView.performItemClick(view, position, id);
                });
            }

            int resolverListMaxHeight = getResources().getDimensionPixelSize(
                    com.android.car.activityresolver.R.dimen.resolver_list_max_height);
            if (listView.getHeight() > resolverListMaxHeight) {
                listView.getLayoutParams().height = resolverListMaxHeight;
                listView.setLayoutParams(listView.getLayoutParams());
            }
        }
    }

    private void setupSystemBarInsets() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content),
                (v, windowInsets) -> {
                    Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    // Apply the insets paddings to the view.
                    v.setPadding(insets.left, insets.top, insets.right, insets.bottom);

                    // Return CONSUMED if you don't want the window insets to keep being
                    // passed down to descendant views.
                    return WindowInsetsCompat.CONSUMED;
                });
    }
}
