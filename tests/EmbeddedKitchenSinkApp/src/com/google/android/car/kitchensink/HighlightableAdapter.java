/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.google.android.car.kitchensink;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.ui.recyclerview.CarUiListItemAdapter;
import com.android.car.ui.recyclerview.CarUiRecyclerView;
import com.android.car.ui.widget.CarUiTextView;

import java.util.List;

/** RecyclerView adapter that supports single-preference highlighting. */
public class HighlightableAdapter extends CarUiListItemAdapter {

    public static final int NO_NORMAL_BACKGROUND = -1;
    @DrawableRes
    private final int mNormalBackgroundRes;
    @DrawableRes
    private final int mHighlightBackgroundRes;
    private Context mContext;
    private int mHighlightPosition = RecyclerView.NO_POSITION;
    private final List<FragmentListItem> mListItems;

    public HighlightableAdapter(Context context, List<FragmentListItem> data) {
        this(context, data, NO_NORMAL_BACKGROUND, R.drawable.preference_highlight_default);
    }

    public HighlightableAdapter(Context context, List<FragmentListItem> data,
            @DrawableRes int normalBackgroundRes, @DrawableRes int highlightBackgroundRes) {
        super(data);
        mContext = context;
        mListItems = data;
        if (normalBackgroundRes == NO_NORMAL_BACKGROUND) {
            TypedValue outValue = new TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground,
                    outValue, /* resolveRefs= */ true);
            mNormalBackgroundRes = outValue.resourceId;
        } else {
            mNormalBackgroundRes = normalBackgroundRes;
        }
        mHighlightBackgroundRes = highlightBackgroundRes;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        super.onBindViewHolder(holder, position);
        holder.itemView.setSelected(mHighlightPosition == position);
        holder.itemView.getLayoutParams().height = mContext.getResources().getDimensionPixelSize(
                R.dimen.top_level_preference_height);

        CarUiTextView titleView = holder.itemView.findViewById(R.id.car_ui_list_item_title);
        if (titleView != null) {
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            titleView.setSingleLine(true);
        }
        updateBackground(holder, position);
    }

    private void updateBackground(RecyclerView.ViewHolder holder, int position) {
        View v = holder.itemView;
        if (position == mHighlightPosition) {
            addHighlightBackground(v);
        } else if (hasHighlightBackground(v)) {
            removeHighlightBackground(v);
        }
    }


    /**
     * Requests that a particular list item be highlighted. This will remove the highlight from
     * the previously highlighted item.
     */
    public void requestHighlight(View root, CarUiRecyclerView recyclerView, int newPosition) {
        if (root == null || recyclerView == null) {
            return;
        }
        if (newPosition < 0) {
            // Item is not in the list - clearing the previous highlight without setting a new one.
            clearHighlight(root);
            return;
        }
        root.post(() -> {
            notifyItemChanged(mHighlightPosition);
            recyclerView.scrollToPosition(newPosition);
            notifyItemChanged(newPosition);
            mHighlightPosition = newPosition;
        });
    }


    /**
     * Removes the highlight from the currently highlighted preference.
     */
    public void clearHighlight(View root) {
        if (root == null) {
            return;
        }
        root.post(() -> {
            if (mHighlightPosition < 0) {
                return;
            }
            int oldPosition = mHighlightPosition;
            mHighlightPosition = RecyclerView.NO_POSITION;
            notifyItemChanged(oldPosition);
        });
    }

    private void addHighlightBackground(View v) {
        v.setTag(R.id.preference_highlighted, true);
        v.setBackgroundResource(mHighlightBackgroundRes);
    }

    private void removeHighlightBackground(View v) {
        v.setTag(R.id.preference_highlighted, false);
        v.setBackgroundResource(mNormalBackgroundRes);
    }

    private boolean hasHighlightBackground(View v) {
        return Boolean.TRUE.equals(v.getTag(R.id.preference_highlighted));
    }

}
