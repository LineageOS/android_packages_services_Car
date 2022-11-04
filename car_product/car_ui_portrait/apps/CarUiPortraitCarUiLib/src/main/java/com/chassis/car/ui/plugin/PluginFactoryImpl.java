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
package com.chassis.car.ui.plugin;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.car.ui.plugin.oemapis.Consumer;
import com.android.car.ui.plugin.oemapis.FocusAreaOEMV1;
import com.android.car.ui.plugin.oemapis.FocusParkingViewOEMV1;
import com.android.car.ui.plugin.oemapis.Function;
import com.android.car.ui.plugin.oemapis.InsetsOEMV1;
import com.android.car.ui.plugin.oemapis.PluginFactoryOEMV5;
import com.android.car.ui.plugin.oemapis.appstyledview.AppStyledViewControllerOEMV2;
import com.android.car.ui.plugin.oemapis.recyclerview.AdapterOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.ListItemOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.RecyclerViewAttributesOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.RecyclerViewOEMV2;
import com.android.car.ui.plugin.oemapis.recyclerview.ViewHolderOEMV1;
import com.android.car.ui.plugin.oemapis.toolbar.ToolbarControllerOEMV2;

import com.chassis.car.ui.plugin.toolbar.BaseLayoutInstaller;

import java.util.List;

/**
 * An implementation of {@link PluginFactoryImpl} for creating the reference design
 * car-ui-lib components.
 */
public class PluginFactoryImpl implements PluginFactoryOEMV5 {

    private final Context mPluginContext;
    @Nullable
    private Function<Context, FocusParkingViewOEMV1> mFocusParkingViewFactory;
    @Nullable
    private Function<Context, FocusAreaOEMV1> mFocusAreaFactory;

    public PluginFactoryImpl(Context pluginContext) {
        mPluginContext = pluginContext;
    }

    @Override
    public void setRotaryFactories(
            Function<Context, FocusParkingViewOEMV1> focusParkingViewFactory,
            Function<Context, FocusAreaOEMV1> focusAreaFactory) {
        mFocusParkingViewFactory = focusParkingViewFactory;
        mFocusAreaFactory = focusAreaFactory;
    }

    @Override
    public ToolbarControllerOEMV2 installBaseLayoutAround(
            @NonNull Context sourceContext,
            @NonNull View contentView,
            Consumer<InsetsOEMV1> insetsChangedListener,
            boolean toolbarEnabled,
            boolean fullscreen) {

        return BaseLayoutInstaller.installBaseLayoutAround(
                sourceContext,
                mPluginContext,
                contentView,
                insetsChangedListener,
                toolbarEnabled,
                fullscreen,
                mFocusParkingViewFactory,
                mFocusAreaFactory);
    }

    @Override
    public boolean customizesBaseLayout() {
        return true;
    }

    @Override
    public AppStyledViewControllerOEMV2 createAppStyledView(@NonNull Context sourceContext) {
        return null;
    }

    @Override
    public RecyclerViewOEMV2 createRecyclerView(
            @NonNull Context sourceContext,
            RecyclerViewAttributesOEMV1 attrs) {
        return null;
    }

    @Override
    public AdapterOEMV1<? extends ViewHolderOEMV1> createListItemAdapter(
            @NonNull List<ListItemOEMV1> items) {
        return null;
    }
}
