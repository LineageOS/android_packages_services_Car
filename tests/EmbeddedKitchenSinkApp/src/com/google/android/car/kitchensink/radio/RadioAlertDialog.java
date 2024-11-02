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
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import androidx.fragment.app.DialogFragment;

import com.google.android.car.kitchensink.R;

import java.util.Objects;

public final class RadioAlertDialog extends DialogFragment {

    private final int mDialogId;
    private final OnAlertSnoozedCallback mOnAlertSnoozedCallback;
    private final CharSequence mChannelName;
    private final RadioAlert mAlert;
    private Context mContext;
    private ListView mRadioAlertInfoList;
    private ArrayAdapter<RadioAlert.AlertInfo> mAlertInfoAdapter;

    static RadioAlertDialog newInstance(Context context, OnAlertSnoozedCallback callback,
            CharSequence channelName, RadioAlert alert, int dialogId) {
        return new RadioAlertDialog(context, callback, channelName, alert, dialogId);
    }

    private RadioAlertDialog(Context context, OnAlertSnoozedCallback callback,
            CharSequence channelName, RadioAlert alert, int dialogId) {
        mContext = Objects.requireNonNull(context, "Context can not be null");
        mOnAlertSnoozedCallback = Objects.requireNonNull(callback, "Callback can not be null");
        mChannelName = Objects.requireNonNull(channelName, "Channel name can not be null");
        mAlert = Objects.requireNonNull(alert, "Radio alert can not be null");
        mDialogId = dialogId;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        return inflater.inflate(R.layout.radio_alert_layout, container);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TextView alertTitleMessage = view.findViewById(R.id.radio_alert_title);
        alertTitleMessage.setText(getString(R.string.radio_alert_title_text, mChannelName));
        TextView alertDetailMessage = view.findViewById(R.id.radio_alert_detail);
        mRadioAlertInfoList = view.findViewById(R.id.radio_alert_info_list);
        alertDetailMessage.setText(getString(R.string.radio_alert_detail_text,
                RadioTestFragmentUtils.alertStatusToString(mAlert.getStatus()),
                RadioTestFragmentUtils.alertMessageTypeToString(mAlert.getMessageType())));
        mAlertInfoAdapter = new RadioAlertInfoAdapter(mContext, R.layout.radio_alert_info_item,
                mAlert.getInfoList().toArray(new RadioAlert.AlertInfo[0]));
        mRadioAlertInfoList.setAdapter(mAlertInfoAdapter);
        Button snoozeButton = view.findViewById(R.id.alert_snooze_button);
        snoozeButton.setOnClickListener((v) -> handleSnooze(/* snoozed= */ true));
        Button ignoreButton = view.findViewById(R.id.alert_ignore_button);
        ignoreButton.setOnClickListener((v) -> handleSnooze(/* snoozed= */ false));
    }

    private void handleSnooze(boolean snoozed) {
        mOnAlertSnoozedCallback.onAlertSnoozed(mDialogId, snoozed);
        dismissNow();
    }

    interface OnAlertSnoozedCallback {
        void onAlertSnoozed(int dialogId, boolean snoozed);
    }
}
