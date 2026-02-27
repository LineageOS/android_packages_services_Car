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

package com.google.android.car.kitchensink.trampoline;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.fragment.app.Fragment;

import com.google.android.car.kitchensink.R;

public class TrampolineFragment extends Fragment {
    public static String DOUBLE_TRAMPOLINE_KEY = "DOUBLE_TRAMPOLINE_KEY";
    public static String FINISH_KEY = "FINISH_KEY";
    public static String HOME_TRAMPOLINE_KEY = "HOME_TRAMPOLINE_KEY";

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.trampoline_fragment, container, false);

        Button startSingleTrampoline = view.findViewById(R.id.start_single_trampoline);
        startSingleTrampoline.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), TrampolineTestActivity1.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });

        Button startDoubleTrampoline = view.findViewById(R.id.start_double_trampoline);
        startDoubleTrampoline.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), TrampolineTestActivity1.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(DOUBLE_TRAMPOLINE_KEY, true);
            startActivity(intent);
        });

        Button startDoubleTrampolineWithFinish = view.findViewById(
                R.id.start_double_trampoline_with_finish);
        startDoubleTrampolineWithFinish.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), TrampolineTestActivity1.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(DOUBLE_TRAMPOLINE_KEY, true);
            intent.putExtra(FINISH_KEY, true);
            startActivity(intent);
        });

        Button startSingleTrampolineToHome = view.findViewById(
                R.id.start_single_trampoline_to_home);
        startSingleTrampolineToHome.setOnClickListener(v -> {
            Intent intent1 = new Intent(getActivity(), TrampolineTestActivity1.class);
            intent1.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent1.putExtra(HOME_TRAMPOLINE_KEY, true);
            startActivity(intent1);
        });

        return view;
    }
}
