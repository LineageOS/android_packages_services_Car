/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.google.android.car.kitchensink.dialer;

import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.telecom.PhoneAccountHandle;

import com.android.internal.telecom.IInCallAdapter;

import java.util.List;

/**
 * Mock class for InCallAdapter
 */
public class MockInCallAdapter extends IInCallAdapter.Stub {
    @Override
    public void answerCall(String callId, int videoState) throws RemoteException {

    }

    @Override
    public void deflectCall(String callId, Uri address) throws RemoteException {

    }

    @Override
    public void rejectCall(String callId, boolean rejectWithMessage,
            String textMessage) throws RemoteException {

    }

    @Override
    public void disconnectCall(String callId) throws RemoteException {

    }

    @Override
    public void holdCall(String callId) throws RemoteException {

    }

    @Override
    public void unholdCall(String callId) throws RemoteException {

    }

    @Override
    public void mute(boolean shouldMute) throws RemoteException {

    }

    @Override
    public void setAudioRoute(int route, String bluetoothAddress)
            throws RemoteException {

    }

    @Override
    public void playDtmfTone(String callId, char digit) throws RemoteException {

    }

    @Override
    public void stopDtmfTone(String callId) throws RemoteException {

    }

    @Override
    public void postDialContinue(String callId, boolean proceed)
            throws RemoteException {

    }

    @Override
    public void phoneAccountSelected(String callId,
            PhoneAccountHandle accountHandle, boolean setDefault)
            throws RemoteException {

    }

    @Override
    public void conference(String callId, String otherCallId)
            throws RemoteException {

    }

    @Override
    public void splitFromConference(String callId) throws RemoteException {

    }

    @Override
    public void mergeConference(String callId) throws RemoteException {

    }

    @Override
    public void swapConference(String callId) throws RemoteException {

    }

    @Override
    public void turnOnProximitySensor() throws RemoteException {

    }

    @Override
    public void turnOffProximitySensor(boolean screenOnImmediately)
            throws RemoteException {

    }

    @Override
    public void pullExternalCall(String callId) throws RemoteException {

    }

    @Override
    public void sendCallEvent(String callId, String event, int targetSdkVer,
            Bundle extras) throws RemoteException {

    }

    @Override
    public void putExtras(String callId, Bundle extras) throws RemoteException {

    }

    @Override
    public void removeExtras(String callId, List<String> keys)
            throws RemoteException {

    }

    @Override
    public void sendRttRequest(String callId) throws RemoteException {

    }

    @Override
    public void respondToRttRequest(String callId, int id, boolean accept)
            throws RemoteException {

    }

    @Override
    public void stopRtt(String callId) throws RemoteException {

    }

    @Override
    public void setRttMode(String callId, int mode) throws RemoteException {

    }

    @Override
    public void handoverTo(String callId, PhoneAccountHandle destAcct,
            int videoState, Bundle extras) throws RemoteException {

    }

    @Override
    public IBinder asBinder() {
        return null;
    }
}
