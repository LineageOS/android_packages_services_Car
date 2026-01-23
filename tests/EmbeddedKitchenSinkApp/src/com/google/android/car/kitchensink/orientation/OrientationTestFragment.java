/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package com.google.android.car.kitchensink.orientation;

import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.car.kitchensink.R;

public class OrientationTestFragment extends Fragment {
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.orientation_test, container, false);

        Button toggleOrientation = v.findViewById(R.id.toggle_orientation);
        toggleOrientation.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int currentRotation = Settings.System.getIntForUser(
                        getContext().getContentResolver(), Settings.System.USER_ROTATION,
                        /* def= */ 0, UserHandle.USER_CURRENT);
                int newRotation = currentRotation == 0 ? 1 : 0;
                Settings.System.putIntForUser(getContext().getContentResolver(),
                        Settings.System.USER_ROTATION, newRotation, UserHandle.USER_CURRENT);
            }
        });

        Button portraitAct = v.findViewById(R.id.launch_portrait_activity);
        portraitAct.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(getContext(), PortraitActivity.class));
            }
        });

        Button landscapeAct = v.findViewById(R.id.launch_landscape_activity);
        landscapeAct.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(getContext(), LandscapeActivity.class));
            }
        });

        return v;
    }
}
