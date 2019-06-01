/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.google.android.car.bugreport;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Adapter class for bug report information
 */
public class BugInfoAdapter extends RecyclerView.Adapter<BugInfoAdapter.BugInfoViewHolder> {

    /**
     * Reference to each bug report info views.
     */
    public static class BugInfoViewHolder extends RecyclerView.ViewHolder {

        /** Title view */
        public TextView titleView;

        /** User view */
        public TextView userView;

        /** TimeStamp View */
        public TextView timestampView;

        /** Status View */
        public TextView statusView;

        /** Message View */
        public TextView messageView;

        BugInfoViewHolder(View v) {
            super(v);
            titleView = itemView.findViewById(R.id.bug_info_row_title);
            userView = itemView.findViewById(R.id.bug_info_row_user);
            timestampView = itemView.findViewById(R.id.bug_info_row_timestamp);
            statusView = itemView.findViewById(R.id.bug_info_row_status);
            messageView = itemView.findViewById(R.id.bug_info_row_message);
        }
    }

    private List<MetaBugReport> mDataset;

    BugInfoAdapter(List<MetaBugReport> dataSet) {
        mDataset = dataSet;
    }

    @Override
    public BugInfoViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        // create a new view
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.bug_info_view, parent, false);
        return new BugInfoViewHolder(v);
    }

    @Override
    public void onBindViewHolder(BugInfoViewHolder holder, int position) {
        holder.titleView.setText(mDataset.get(position).getTitle());
        holder.userView.setText(mDataset.get(position).getUsername());
        holder.timestampView.setText(mDataset.get(position).getTimestamp());
        holder.statusView.setText(Status.toString(mDataset.get(position).getStatus()));
        holder.messageView.setText(mDataset.get(position).getStatusMessage());
    }

    @Override
    public int getItemCount() {
        return mDataset.size();
    }
}
