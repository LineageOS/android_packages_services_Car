/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.google.android.car.kitchensink.fullscreen;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.car.kitchensink.R;

public final class RequestFullScreenFragment extends Fragment {
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        return inflater.inflate(R.layout.request_full_screen_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Button start = view.findViewById(R.id.start_activity_button);
        start.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), RequestFullScreenActivity.class);
            startActivity(intent);
        });

        Button startSafeRegionOptOut = view.findViewById(
                R.id.start_activity_safe_region_opt_out_button);
        startSafeRegionOptOut.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(),
                    RequestFullScreenSafeRegionOptOutActivity.class);
            startActivity(intent);
        });
    }
}
