/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.google.android.car.uxr.sample;

import android.annotation.DrawableRes;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import androidx.car.widget.ListItem;
import androidx.car.widget.ListItemAdapter;
import androidx.car.widget.ListItemProvider;
import androidx.car.widget.PagedListView;
import androidx.car.widget.TextListItem;

import java.util.ArrayList;
import java.util.List;

/**
 * A Sample Messaging Activity that illustrates how to truncate the string length on receiving the
 * corresponding UX restriction.
 */
public class SampleMessageActivity extends Activity {
    private Button mHomeButton;
    private PagedListView mPagedListView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sample_message);
        mHomeButton = findViewById(R.id.home_button);
        mHomeButton.setOnClickListener(this::returnHome);

        mPagedListView = findViewById(R.id.paged_list_view);
        setUpPagedListView();
    }

    private void returnHome(View view) {
        Intent homeIntent = new Intent(this, MainActivity.class);
        startActivity(homeIntent);

    }

    private void setUpPagedListView() {
        ListItemAdapter adapter = new ListItemAdapter(this, populateData());
        mPagedListView.setAdapter(adapter);
    }

    private ListItemProvider populateData() {
        List<ListItem> items = new ArrayList<>();
        items.add(createMessage(android.R.drawable.ic_menu_myplaces, "alice",
                "i have a really important message but it may hinder your ability to drive. "));

        items.add(createMessage(android.R.drawable.ic_menu_myplaces, "bob",
                "hey this is a really long message that i have always wanted to say. but before "
                        + "saying it i feel it's only appropriate if i lay some groundwork for it. "
                        + ""));
        items.add(createMessage(android.R.drawable.ic_menu_myplaces, "mom",
                "i think you are the best. i think you are the best. i think you are the best. "
                        + "i think you are the best. i think you are the best. i think you are the "
                        + "best. "
                        + "i think you are the best. i think you are the best. i think you are the "
                        + "best. "
                        + "i think you are the best. i think you are the best. i think you are the "
                        + "best. "
                        + "i think you are the best. i think you are the best. i think you are the "
                        + "best. "
                        + "i think you are the best. i think you are the best. "));
        items.add(createMessage(android.R.drawable.ic_menu_myplaces, "john", "hello world"));
        items.add(createMessage(android.R.drawable.ic_menu_myplaces, "jeremy",
                "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor "
                        + "incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, "
                        + "quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo "
                        + "consequat. Duis aute irure dolor in reprehenderit in voluptate velit "
                        + "esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat "
                        + "cupidatat non proident, sunt in culpa qui officia deserunt mollit "
                        + "anim id est laborum."));
        return new ListItemProvider.ListProvider(items);
    }

    private TextListItem createMessage(@DrawableRes int profile, String contact, String message) {
        TextListItem item = new TextListItem(this);
        item.setPrimaryActionIcon(profile, false /* useLargeIcon */);
        item.setTitle(contact);
        item.setBody(message);
        item.setSupplementalIcon(android.R.drawable.stat_notify_chat, false);
        return item;
    }

}
