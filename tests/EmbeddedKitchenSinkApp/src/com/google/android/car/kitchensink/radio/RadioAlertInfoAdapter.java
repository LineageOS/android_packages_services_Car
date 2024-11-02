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

package com.google.android.car.kitchensink.radio;

import android.content.Context;
import android.hardware.radio.RadioAlert;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.google.android.car.kitchensink.R;

import java.util.List;

class RadioAlertInfoAdapter extends ArrayAdapter<RadioAlert.AlertInfo> {

    private int mLayoutResourceId;
    private RadioAlert.AlertInfo[] mAlertInfos;

    RadioAlertInfoAdapter(Context context, int layoutResourceId,
            RadioAlert.AlertInfo[] alertInfos) {
        super(context, layoutResourceId, alertInfos);
        mLayoutResourceId = layoutResourceId;
        mAlertInfos = alertInfos;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder vh = new ViewHolder();
        if (convertView == null) {
            LayoutInflater inflater = LayoutInflater.from(getContext());
            convertView = inflater.inflate(mLayoutResourceId, parent, /* attachToRoot= */ false);
            vh.alertInfoText = convertView.findViewById(R.id.text_alert_info);
            convertView.setTag(vh);
        } else {
            vh = (ViewHolder) convertView.getTag();
        }
        if (mAlertInfos[position] != null) {
            vh.alertInfoText.setText(getAlertInfoDisplayText(mAlertInfos[position]));
        }
        return convertView;
    }

    @Override
    public int getCount() {
        return mAlertInfos.length;
    }

    private static String getAlertInfoDisplayText(RadioAlert.AlertInfo alertInfo) {
        String infoString = "Alert Info: category:";
        int[] categories = alertInfo.getCategories();
        boolean hasCategory = false;
        for (int i = 0; i < categories.length; i++) {
            String categoryString = RadioTestFragmentUtils.alertCategoryToString(categories[i]);
            if (categoryString == null) {
                continue;
            }
            if (hasCategory) {
                infoString += ",";
            } else {
                hasCategory = true;
            }
            infoString += categoryString;
        }
        String urgencyString = RadioTestFragmentUtils.alertUrgencyToString(
                alertInfo.getUrgency());
        if (urgencyString != null) {
            infoString += " Urgency:" + urgencyString;
        }
        String severityString = RadioTestFragmentUtils.alertSeverityToString(
                alertInfo.getSeverity());
        if (severityString != null) {
            infoString += " Severity:" + severityString;
        }
        String certaintyString = RadioTestFragmentUtils.alertCertaintyToString(
                alertInfo.getCertainty());
        if (certaintyString != null) {
            infoString += " Certainty:" + certaintyString;
        }
        infoString += "\n" + alertInfo.getDescription();
        infoString += "\nArea: ";
        List<RadioAlert.AlertArea> areas = alertInfo.getAreas();
        for (int i = 0; i < areas.size(); i++) {
            if (i != 0) {
                infoString += ", ";
            }
            infoString += areas.get(i).toString();
        }
        return infoString;
    }

    private static final class ViewHolder {
        public TextView alertInfoText;
    }
}
