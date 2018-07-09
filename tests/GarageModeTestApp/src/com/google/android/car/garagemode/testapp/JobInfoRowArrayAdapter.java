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

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class JobInfoRowArrayAdapter extends ArrayAdapter<JobInfoRow> {
    private class ViewHolder {
        TextView mJobIDView;
        TextView mJobStateView;
        Button mButton;
        JobInfoRow mInfo;
    }

    public JobInfoRowArrayAdapter(Context context, int resource, List<JobInfoRow> objects) {
        super(context, resource, objects);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView;
        final JobInfoRow info = getItem(position);

        ViewHolder holder;

        if (row == null) {
            LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(
                    Context.LAYOUT_INFLATER_SERVICE);
            row = inflater.inflate(R.layout.job_info_row, parent, false);

            holder = new ViewHolder();
            holder.mJobIDView = row.findViewById(R.id.jobId);
            holder.mJobStateView = row.findViewById(R.id.jobState);
            holder.mButton = row.findViewById(R.id.jobInfoButton);
            holder.mInfo = info;

            row.setTag(holder);
        } else {
            holder = (ViewHolder) row.getTag();
            holder.mInfo = info;
        }

        holder.mJobIDView.setText("ID: " + info.getId());
        holder.mJobStateView.setText("State: " + info.getState());

        holder.mButton.setOnClickListener(
                v -> Toast.makeText(
                        getContext(), "Show more detailed job info", Toast.LENGTH_LONG));
        return row;
    }
}
