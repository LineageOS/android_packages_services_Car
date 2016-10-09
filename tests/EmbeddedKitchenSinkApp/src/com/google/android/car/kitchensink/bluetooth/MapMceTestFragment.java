/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.google.android.car.kitchensink.bluetooth;

import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothMapClient;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import com.google.android.car.kitchensink.R;

import java.util.List;

public class MapMceTestFragment extends Fragment {
    static final String MESSAGE_TO_SEND = "Im Busy Driving";
    private static final String TAG = "CAR.BLUETOOTH.KS";
    BluetoothMapClient mMapProfile;
    BluetoothAdapter mBluetoothAdapter;
    TextView mMessage;
    TextView mOriginator;
    CheckBox mSent;
    CheckBox mDelivered;
    TextView mBluetoothDevice;
    PendingIntent mSentIntent;
    PendingIntent mDeliveredIntent;
    NotificationReceiver mTransmissionStatusReceiver;
    Object mLock = new Object();
    private Intent mSendIntent;
    private Intent mDeliveryIntent;

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.sms_received, container, false);

        Button reply = (Button) v.findViewById(R.id.reply);
        Button checkMessages = (Button) v.findViewById(R.id.check_messages);
        mBluetoothDevice = (TextView) v.findViewById(R.id.bluetoothDevice);
        mOriginator = (TextView) v.findViewById(R.id.messageOriginator);
        mSent = (CheckBox) v.findViewById(R.id.sent_checkbox);
        mDelivered = (CheckBox) v.findViewById(R.id.delivered_checkbox);
        mSendIntent = new Intent(BluetoothMapClient.ACTION_MESSAGE_SENT_SUCCESSFULLY);
        mDeliveryIntent = new Intent(BluetoothMapClient.ACTION_MESSAGE_DELIVERED_SUCCESSFULLY);
        mMessage = (TextView) v.findViewById(R.id.messageContent);

        //TODO add manual entry option for phone number
        reply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendMessage(new Uri[]{Uri.parse(mOriginator.getText().toString())},
                        MESSAGE_TO_SEND);
            }
        });

        checkMessages.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                getMessages();
            }
        });

        mTransmissionStatusReceiver = new NotificationReceiver();
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothAdapter.getProfileProxy(getContext(), new MapServiceListener(),
                BluetoothProfile.MAP_CLIENT);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothMapClient.ACTION_MESSAGE_SENT_SUCCESSFULLY);
        intentFilter.addAction(BluetoothMapClient.ACTION_MESSAGE_DELIVERED_SUCCESSFULLY);
        intentFilter.addAction(BluetoothMapClient.ACTION_MESSAGE_RECEIVED);
        getContext().registerReceiver(mTransmissionStatusReceiver, intentFilter);
    }

    @Override
    public void onPause() {
        super.onPause();
        getContext().unregisterReceiver(mTransmissionStatusReceiver);
    }

    private void getMessages() {
        synchronized (mLock) {
            if (mMapProfile != null) {
                Log.d(TAG, "Getting Messages");
                mMapProfile.getUnreadMessages(mBluetoothAdapter.getRemoteDevice(
                        mBluetoothDevice.getText().toString()));
            }
        }
    }

    private void sendMessage(Uri[] recipients, String message) {
        synchronized (mLock) {
            mSent.setChecked(false);
            mDelivered.setChecked(false);
            if (mMapProfile != null) {
                Log.d(TAG, "Sending reply");
                if (recipients == null) {
                    Log.d(TAG, "Recipients is null");
                    return;
                }
                if (mBluetoothDevice == null) {
                    Log.d(TAG, "BluetoothDevice is null");
                    return;
                }

                mSentIntent = PendingIntent.getBroadcast(getContext(), 0, mSendIntent,
                        PendingIntent.FLAG_ONE_SHOT);
                mDeliveredIntent = PendingIntent.getBroadcast(getContext(), 0, mDeliveryIntent,
                        PendingIntent.FLAG_ONE_SHOT);
                mMapProfile.sendMessage(
                        mBluetoothAdapter.getRemoteDevice(mBluetoothDevice.getText().toString()),
                        recipients, message, mSentIntent, mDeliveredIntent);
            }
        }
    }

    class MapServiceListener implements BluetoothProfile.ServiceListener {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            synchronized (mLock) {
                mMapProfile = (BluetoothMapClient) proxy;
                List<BluetoothDevice> connectedDevices = proxy.getConnectedDevices();
                if (connectedDevices.size() > 0) {
                    mBluetoothDevice.setText(connectedDevices.get(0).getAddress());
                }
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            synchronized (mLock) {
                mMapProfile = null;
            }
        }
    }

    private class NotificationReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            synchronized (mLock) {
                if (action.equals(BluetoothMapClient.ACTION_MESSAGE_SENT_SUCCESSFULLY)) {
                    mSent.setChecked(true);
                } else if (action.equals(
                        BluetoothMapClient.ACTION_MESSAGE_DELIVERED_SUCCESSFULLY)) {
                    mDelivered.setChecked(true);
                } else if (action.equals(BluetoothMapClient.ACTION_MESSAGE_RECEIVED)) {
                    String[] recipients = intent.getStringArrayExtra(android.provider
                            .ContactsContract.Intents.EXTRA_RECIPIENT_CONTACT_URI);
                    StringBuilder stringBuilder = new StringBuilder();
                    if (recipients != null) {
                        for (String s : recipients) {
                            stringBuilder.append(s);
                        }
                    }

                    mMessage.setText(intent.getStringExtra(android.content.Intent.EXTRA_TEXT));
                    mOriginator.setText(stringBuilder.toString());
                }
            }
        }
    }
}