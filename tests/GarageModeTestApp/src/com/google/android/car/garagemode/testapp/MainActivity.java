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
package com.google.android.car.garagemode.testapp;

import android.content.res.Configuration;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.car.drawer.CarDrawerAdapter;
import androidx.car.drawer.CarDrawerController;
import androidx.car.drawer.DrawerItemViewHolder;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final Logger LOG = new Logger("MainActivity");

    private CarDrawerController mDrawerController;
    private Toolbar mToolbar;

    private final List<MenuEntry> mMenuEntries = new ArrayList<MenuEntry>() {
        {
            add("Offcar testing", OffcarTestingFragment.class);
            add("Incar testing", IncarTestingFragment.class);
            add("Quit", MainActivity.this::finish);
        }

        <T extends Fragment> void add(String text, Class<T> clazz) {
            add(new FragmentMenuEntry(text, clazz));
        }

        void add(String text, ClickHandler onClick) {
            add(new OnClickMenuEntry(text, onClick));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);

        DrawerLayout drawerLayout = findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle drawerToggle = new ActionBarDrawerToggle(
                /* activity= */ this,
                drawerLayout,
                R.string.car_drawer_open,
                R.string.car_drawer_close);

        mToolbar = findViewById(R.id.car_toolbar);
        setSupportActionBar(mToolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setHomeButtonEnabled(true);

        mMenuEntries.get(0).onClick();

        mDrawerController = new CarDrawerController(drawerLayout, drawerToggle);
        mDrawerController.setRootAdapter(new DrawerAdapter());
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        mDrawerController.syncState();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mDrawerController.closeDrawer();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mDrawerController.onConfigurationChanged(newConfig);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        return mDrawerController.onOptionsItemSelected(item) || super.onOptionsItemSelected(item);
    }

    private interface ClickHandler {
        void onClick();
    }

    private abstract static class MenuEntry implements ClickHandler {
        abstract String getText();
    }

    private final class OnClickMenuEntry extends MenuEntry {
        private final String mText;
        private final ClickHandler mClickHandler;

        OnClickMenuEntry(String text, ClickHandler clickHandler) {
            mText = text;
            mClickHandler = clickHandler;
        }

        @Override
        String getText() {
            return mText;
        }

        @Override
        public void onClick() {
            mClickHandler.onClick();
        }
    }

    private final class FragmentMenuEntry<T extends Fragment> extends MenuEntry {
        private final class FragmentClassOrInstance<T extends Fragment> {
            final Class<T> mClazz;
            T mFragment = null;

            FragmentClassOrInstance(Class<T> clazz) {
                mClazz = clazz;
            }

            T getFragment() {
                if (mFragment == null) {
                    try {
                        mFragment = mClazz.newInstance();
                    } catch (InstantiationException | IllegalAccessException e) {
                        LOG.e("unable to create fragment", e);
                    }
                }
                return mFragment;
            }
        }

        private final String mText;
        private final FragmentClassOrInstance<T> mFragment;

        FragmentMenuEntry(String text, Class<T> clazz) {
            mText = text;
            mFragment = new FragmentClassOrInstance<>(clazz);
        }

        @Override
        String getText() {
            return mText;
        }

        @Override
        public void onClick() {
            Fragment fragment = mFragment.getFragment();
            if (fragment != null) {
                MainActivity.this.showFragment(fragment);
            } else {
                LOG.e("cannot show fragment for " + getText());
            }
        }
    }

    private void showFragment(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.activity_content, fragment)
                .commit();
    }

    private final class DrawerAdapter extends CarDrawerAdapter {
        DrawerAdapter() {
            super(MainActivity.this, true /* showDisabledOnListOnEmpty */);
            setTitle(getString(R.string.app_name));
        }

        @Override
        protected int getActualItemCount() {
            return mMenuEntries.size();
        }

        @Override
        protected void populateViewHolder(DrawerItemViewHolder holder, int position) {
            holder.getTitleView().setText(mMenuEntries.get(position).getText());
            holder.itemView.setOnClickListener(v -> onItemClick(holder.getAdapterPosition()));
        }

        private void onItemClick(int position) {
            if ((position < 0) || (position >= mMenuEntries.size())) {
                LOG.wtf("Unknown menu item: " + position);
                return;
            }

            mMenuEntries.get(position).onClick();
            mDrawerController.closeDrawer();
        }
    }
}
