/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.google.android.car.kitchensink.customizationtool;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.car.kitchensink.R;

/**
 * Provides methods to enable or disable the Customization Tool service.
 */
public class CustomizationToolFragment extends Fragment {

    private CustomizationToolController mCustomizationToolController;
    private Switch mCustomizationToolSwitch;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.customization_tool, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mCustomizationToolController = new CustomizationToolController(getContext());

        mCustomizationToolSwitch = view.findViewById(R.id.customization_tool_switch);
        mCustomizationToolSwitch.setOnClickListener(v ->
                mCustomizationToolController.toggleCustomizationTool(
                        mCustomizationToolSwitch.isChecked()));
        mCustomizationToolSwitch.setChecked(
                mCustomizationToolController.isCustomizationToolActive());
    }
}
