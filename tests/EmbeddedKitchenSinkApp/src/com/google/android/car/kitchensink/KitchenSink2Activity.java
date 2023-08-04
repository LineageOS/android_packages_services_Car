/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.google.android.car.kitchensink;

import static com.google.android.car.kitchensink.KitchenSinkActivity.MENU_ENTRIES;

import android.annotation.Nullable;
import android.car.drivingstate.CarUxRestrictions;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.android.car.ui.core.CarUi;
import com.android.car.ui.recyclerview.CarUiContentListItem;
import com.android.car.ui.recyclerview.CarUiRecyclerView;
import com.android.car.ui.toolbar.MenuItem;
import com.android.car.ui.toolbar.NavButtonMode;
import com.android.car.ui.toolbar.SearchMode;
import com.android.car.ui.toolbar.ToolbarController;

import java.util.ArrayList;
import java.util.List;

// TODO: b/293660419 - Add CLI support i.e. onNewIntent and dump()
public class KitchenSink2Activity extends FragmentActivity {
    static final String TAG = KitchenSink2Activity.class.getName();

    @Nullable
    private Fragment mLastFragment;
    private static final int NO_INDEX = -1;
    private static final String EMPTY_STRING = "";
    private HighlightableAdapter mAdapter;
    private final FragmentItemClickHandler mItemClickHandler = new FragmentItemClickHandler();

    /**
     * List of all the original menu items.
     */
    private List<FragmentListItem> mData;

    /**
     * Dynamic list of menu items that needs to be given to the adapter. Useful while searching.
     */
    private List<FragmentListItem> mFilteredData;
    private boolean mIsSinglePane = false;
    private ToolbarController mGlobalToolbar, mMiniToolbar;
    private View mMenuContainer;
    private CarUiRecyclerView mRV;
    private String mLastFragmentTitle;


    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {
        super.onPointerCaptureChanged(hasCapture);
    }

    private void showDefaultFragment() {
        onFragmentItemClick(0);
    }

    private int getFragmentIndexFromTitle(String title) {
        for (int i = 0; i < mFilteredData.size(); i++) {
            String targetText = mFilteredData.get(i).getTitle().getPreferredText().toString();
            if (targetText.equalsIgnoreCase(title)) {
                return i;
            }
        }
        return NO_INDEX;
    }

    public void onFragmentItemClick(int fragIndex) {
        if (fragIndex < 0 || fragIndex >= mFilteredData.size()) return;
        FragmentListItem fragmentListItem = mFilteredData.get(fragIndex);
        Fragment fragment = fragmentListItem.getFragment();
        if (mLastFragment != fragment) {
            Log.v(TAG, "onFragmentItemClick(): from " + mLastFragment + " to " + fragment);
        } else {
            Log.v(TAG, "onFragmentItemClick(): showing " + fragment + " again");
        }
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
        mLastFragment = fragment;
        mLastFragmentTitle = fragmentListItem.getTitle().getPreferredText().toString();
        mMiniToolbar.setTitle(mLastFragmentTitle);
        mAdapter.requestHighlight(mLastFragmentTitle, fragIndex);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_2pane);
        mMenuContainer = findViewById(R.id.top_level_menu_container);
        mRV = findViewById(R.id.list_pane);

        setUpToolbars();

        mData = getProcessedData();
        mFilteredData = new ArrayList<>(mData);
        mAdapter = new HighlightableAdapter(this, mFilteredData, mRV);
        mRV.setAdapter(mAdapter);

        // showing Default
        showDefaultFragment();
    }

    private void setUpToolbars() {
        View toolBarView = requireViewById(R.id.top_level_menu_container);

        mGlobalToolbar = CarUi.installBaseLayoutAround(
                toolBarView,
                insets -> findViewById(R.id.top_level_menu_container).setPadding(
                        insets.getLeft(), insets.getTop(), insets.getRight(),
                        insets.getBottom()), /* hasToolbar= */ true);

        MenuItem searchButton = new MenuItem.Builder(this)
                .setToSearch()
                .setOnClickListener(menuItem -> onSearchButtonClicked())
                .setUxRestrictions(CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD)
                .setId(R.id.toolbar_menu_item_search)
                .build();

        mGlobalToolbar.setMenuItems(List.of(searchButton));
        mGlobalToolbar.registerBackListener(() -> {
            mGlobalToolbar.setSearchMode(SearchMode.DISABLED);
            mGlobalToolbar.setNavButtonMode(NavButtonMode.DISABLED);
            mGlobalToolbar.unregisterOnSearchListener(this::onQueryChanged);
            EditText mSearchText = findViewById(R.id.car_ui_toolbar_search_bar);
            mSearchText.getText().clear();
            onQueryChanged(EMPTY_STRING);
            mAdapter.onSearchEnded();
            return true;
        });

        mGlobalToolbar.setTitle(getString(R.string.app_title));
        mGlobalToolbar.setNavButtonMode(NavButtonMode.DISABLED);
        mGlobalToolbar.setLogo(R.drawable.ic_launcher);
//        if (mIsSinglePane) {
//            mGlobalToolbar.setNavButtonMode(NavButtonMode.BACK);
//            findViewById(R.id.top_level_menu_container).setVisibility(View.GONE);
//            findViewById(R.id.top_level_divider).setVisibility(View.GONE);
//            return;
//        }
        mMiniToolbar = CarUi.installBaseLayoutAround(
                requireViewById(R.id.fragment_container_wrapper),
                insets -> findViewById(R.id.fragment_container_wrapper).setPadding(
                        insets.getLeft(), insets.getTop(), insets.getRight(),
                        insets.getBottom()), /* hasToolbar= */ true);

        mMiniToolbar.setNavButtonMode(NavButtonMode.BACK);
    }

    private void onSearchButtonClicked() {
        mGlobalToolbar.setSearchMode(SearchMode.SEARCH);
        mGlobalToolbar.setNavButtonMode(NavButtonMode.BACK);
        mGlobalToolbar.registerOnSearchListener(this::onQueryChanged);
    }

    private void onQueryChanged(String query) {
        mFilteredData.clear();
        if (query.isEmpty()) {
            mFilteredData.addAll(mData);
        } else {
            for (FragmentListItem item : mData) {
                if (item.getTitle().getPreferredText().toString().toLowerCase().contains(
                        query.toLowerCase())) {
                    mFilteredData.add(item);
                }
            }
        }
        mAdapter.afterTextChanged();
    }

    List<FragmentListItem> getProcessedData() {

        List<FragmentListItem> data = new ArrayList<>();

        for (Pair<String, Class> entry : MENU_ENTRIES) {
            data.add(new FragmentListItem(entry.first, entry.second, mItemClickHandler));
        }

        data.sort((o1, o2) -> {
            String s1 = o1.getTitle().getPreferredText().toString();
            String s2 = o2.getTitle().getPreferredText().toString();
            return s1.compareToIgnoreCase(s2);
        });

        return data;
    }

    private class FragmentItemClickHandler implements CarUiContentListItem.OnClickListener {
        @Override
        public void onClick(@NonNull CarUiContentListItem carUiContentListItem) {
            int fragmentItemIndex = getFragmentIndexFromTitle(
                    carUiContentListItem.getTitle().getPreferredText().toString());
            onFragmentItemClick(fragmentItemIndex);
        }
    }

}
