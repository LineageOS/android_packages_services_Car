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
package com.google.android.car.kitchensink.overlay;

import static androidx.core.content.ContextCompat.getSystemService;

import android.annotation.Nullable;
import android.content.Context;
import android.content.om.OverlayInfo;
import android.content.om.OverlayManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.google.android.car.kitchensink.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public class MutableOverlayListFragment extends Fragment {
    private static final String TAG = "MutableRroToggleListFragment";
    private final Set<String> mSupportedPackages = Set.of("com.android.car.carlauncher",
            "com.android.systemui");
    private ListView mOverlayListView;
    private TextView mTextView;
    private OverlayManager mOverlayManager;
    private Context mContext;

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.overlay_toggle_list_fragment, container, false);

        mContext = container.getContext();
        mOverlayListView = view.findViewById(R.id.overlay_list_view);
        mTextView = view.findViewById(R.id.empty_text_view);
        mOverlayManager = getSystemService(mContext, android.content.om.OverlayManager.class);

        loadOverlays();

        return view;
    }

    private void loadOverlays() {
        List<OverlayInfo> mutableOverlays = new ArrayList<>();
        mSupportedPackages.forEach(packageName -> {
            List<OverlayInfo> overlays = Stream.concat(
                    mOverlayManager.getOverlayInfosForTarget(packageName,
                            UserHandle.CURRENT).stream(),
                    mOverlayManager.getOverlayInfosForTarget(packageName,
                            UserHandle.SYSTEM).stream()).toList();
            if (!overlays.isEmpty()) {
                overlays.forEach(overlay -> {
                    if (overlay.isMutable) {
                        mutableOverlays.add(overlay);
                    }
                });
            }
        });

        if (!mutableOverlays.isEmpty()) {
            MutableOverlayAdapter adapter = new MutableOverlayAdapter(mContext, mutableOverlays);
            mOverlayListView.setAdapter(adapter);
            mTextView.setVisibility(View.GONE);
            mOverlayListView.setVisibility(View.VISIBLE);
        } else {
            mOverlayListView.setVisibility(View.GONE);
            mTextView.setVisibility(View.VISIBLE);
        }
    }
}
