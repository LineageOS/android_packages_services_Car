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

import android.content.Context;
import android.content.om.OverlayInfo;
import android.content.om.OverlayManager;
import android.content.om.OverlayManagerTransaction;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.google.android.car.kitchensink.R;

import java.util.List;

public class MutableOverlayAdapter extends ArrayAdapter<OverlayInfo> {
    private static final String TAG = "MutableOverlayAdapter";
    private final OverlayManager mOverlayManager;

    public MutableOverlayAdapter(Context context, List<OverlayInfo> widgets) {
        super(context, 0, widgets);
        mOverlayManager = getSystemService(context, android.content.om.OverlayManager.class);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.overlay_list_item,
                    parent, false);
        }

        OverlayInfo currentOverlay = getItem(position);

        TextView overlayNameTextView = convertView.findViewById(R.id.overlay_name_text_view);
        TextView packageNameTextView = convertView.findViewById(R.id.overlay_package_text_view);
        TextView userIdTextView = convertView.findViewById(R.id.userId_text_view);
        overlayNameTextView.setText(currentOverlay.packageName);
        packageNameTextView.setText(currentOverlay.getTargetPackageName());
        userIdTextView.setText("User ID: %d".formatted(currentOverlay.userId));
        ToggleButton toggleButton = convertView.findViewById(R.id.overlay_toggle);
        toggleButton.setChecked(currentOverlay.isEnabled());

        toggleButton.setOnClickListener(item -> {
            boolean isEnabled = toggleButton.isChecked();
            OverlayManagerTransaction.Builder transaction =
                    new OverlayManagerTransaction.Builder().setEnabled(
                            currentOverlay.getOverlayIdentifier(), isEnabled,
                            currentOverlay.userId);
            mOverlayManager.commit(transaction.build());
        });

        return convertView;
    }
}
