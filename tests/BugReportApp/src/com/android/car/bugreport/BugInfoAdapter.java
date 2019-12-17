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
package com.android.car.bugreport;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class BugInfoAdapter extends RecyclerView.Adapter<BugInfoAdapter.BugInfoViewHolder> {
    static final int BUTTON_TYPE_UPLOAD = 0;
    static final int BUTTON_TYPE_MOVE = 1;

    /** Provides a handler for click events*/
    interface ItemClickedListener {
        /**
         * Handles click events differently depending on provided buttonType and
         * uses additional information provided in metaBugReport.
         *
         * @param buttonType One of {@link #BUTTON_TYPE_UPLOAD} or {@link #BUTTON_TYPE_MOVE}.
         * @param metaBugReport Selected bugreport.
         * @param holder ViewHolder of the clicked item.
         */
        void onItemClicked(int buttonType, MetaBugReport metaBugReport, BugInfoViewHolder holder);
    }

    /**
     * Reference to each bug report info views.
     */
    static class BugInfoViewHolder extends RecyclerView.ViewHolder {
        /** Title view */
        TextView mTitleView;

        /** Status View */
        TextView mStatusView;

        /** Message View */
        TextView mMessageView;

        /** Move Button */
        Button mMoveButton;

        /** Upload Button */
        Button mUploadButton;

        BugInfoViewHolder(View v) {
            super(v);
            mTitleView = itemView.findViewById(R.id.bug_info_row_title);
            mStatusView = itemView.findViewById(R.id.bug_info_row_status);
            mMessageView = itemView.findViewById(R.id.bug_info_row_message);
            mMoveButton = itemView.findViewById(R.id.bug_info_move_button);
            mUploadButton = itemView.findViewById(R.id.bug_info_upload_button);
        }
    }

    private List<MetaBugReport> mDataset;
    private final ItemClickedListener mItemClickedListener;

    BugInfoAdapter(ItemClickedListener itemClickedListener) {
        mItemClickedListener = itemClickedListener;
        mDataset = new ArrayList<>();
        // Allow RecyclerView to efficiently update UI; getItemId() is implemented below.
        setHasStableIds(true);
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
        MetaBugReport bugreport = mDataset.get(position);
        holder.mTitleView.setText(bugreport.getTitle());
        holder.mStatusView.setText(Status.toString(bugreport.getStatus()));
        holder.mMessageView.setText(bugreport.getStatusMessage());
        if (bugreport.getStatusMessage() == null || bugreport.getStatusMessage().isEmpty()) {
            holder.mMessageView.setVisibility(View.GONE);
        } else {
            holder.mMessageView.setVisibility(View.VISIBLE);
        }
        if (getUserActionButtonsVisible()) {
            holder.mMoveButton.setVisibility(View.VISIBLE);
            holder.mUploadButton.setVisibility(View.VISIBLE);
        } else {
            holder.mMoveButton.setVisibility(View.GONE);
            holder.mUploadButton.setVisibility(View.GONE);
        }
        boolean enableUserActionButtons =
                bugreport.getStatus() == Status.STATUS_PENDING_USER_ACTION.getValue()
                        || bugreport.getStatus() == Status.STATUS_MOVE_FAILED.getValue()
                        || bugreport.getStatus() == Status.STATUS_UPLOAD_FAILED.getValue();
        if (enableUserActionButtons) {
            holder.mMoveButton.setEnabled(true);
            holder.mMoveButton.setOnClickListener(
                    view -> mItemClickedListener.onItemClicked(BUTTON_TYPE_MOVE, bugreport,
                            holder));
            holder.mUploadButton.setEnabled(true);
            holder.mUploadButton.setOnClickListener(
                    view -> mItemClickedListener.onItemClicked(BUTTON_TYPE_UPLOAD, bugreport,
                            holder));
        } else {
            holder.mMoveButton.setEnabled(false);
            holder.mUploadButton.setEnabled(false);
        }
    }

    /** Sets dataSet; it copies the list, because it modifies it in this adapter. */
    void setDataset(List<MetaBugReport> bugReports) {
        mDataset = new ArrayList<>(bugReports);
        notifyDataSetChanged();
    }

    /** Update a bug report in the data set. */
    void updateBugReportInDataSet(MetaBugReport bugReport, int position) {
        if (position != RecyclerView.NO_POSITION) {
            mDataset.set(position, bugReport);
            notifyItemChanged(position);
        }
    }

    /** Returns true if the upload/move buttons should be visible. */
    private boolean getUserActionButtonsVisible() {
        // Do not show buttons if bugreports are uploaded by default.
        return !JobSchedulingUtils.uploadByDefault();
    }

    @Override
    public long getItemId(int position) {
        return mDataset.get(position).getId();
    }

    @Override
    public int getItemCount() {
        return mDataset.size();
    }
}
