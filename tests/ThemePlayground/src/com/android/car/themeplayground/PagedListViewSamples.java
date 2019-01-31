/*
 * Copyright (C) 2019 The Android Open Source Project.
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

package com.android.car.themeplayground;

import android.os.Bundle;

import androidx.car.widget.PagedListView;

import java.util.ArrayList;

/**
 * Activity that shows pagedlistView example with dummy data.
 */
public class PagedListViewSamples extends AbstractSampleActivity {

    private final ArrayList<String> mData = new ArrayList<>();
    private final int mDataToGenerate = 15;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Utils.onActivityCreateSetTheme(this);
        setContentView(R.layout.paged_list_view_samples);
        PagedListView pagedListView = (PagedListView) findViewById(R.id.list);

        PagedListViewAdapter pagedListAdapter = new PagedListViewAdapter(generateDummyData());
        pagedListView.setAdapter(pagedListAdapter);
    }

    private ArrayList<String> generateDummyData() {
        for (int i = 0; i <= mDataToGenerate; i++) {
            mData.add("data" + i);
        }
        return mData;
    }

}
