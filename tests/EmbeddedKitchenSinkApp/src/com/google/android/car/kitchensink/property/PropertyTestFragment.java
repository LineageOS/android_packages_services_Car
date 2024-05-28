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

package com.google.android.car.kitchensink.property;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static java.lang.Integer.toHexString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.Car;
import android.car.VehiclePropertyIds;
import android.car.VehiclePropertyType;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback;
import android.car.hardware.property.CarPropertyManager.GetPropertyCallback;
import android.car.hardware.property.CarPropertyManager.GetPropertyRequest;
import android.car.hardware.property.CarPropertyManager.GetPropertyResult;
import android.car.hardware.property.Subscription;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.fragment.app.Fragment;

import com.google.android.car.kitchensink.KitchenSinkHelper;
import com.google.android.car.kitchensink.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class PropertyTestFragment extends Fragment implements OnItemSelectedListener {
    private static final String TAG = "PropertyTestFragment";
    private static final int KS_PERMISSIONS_REQUEST = 1;

    // The dangerous permissions that need to be granted at run-time.
    private static final String[] REQUIRED_DANGEROUS_PERMISSIONS = new String[]{
        Car.PERMISSION_ENERGY,
        Car.PERMISSION_SPEED
    };
    private static final Float[] SUBSCRIPTION_RATES_HZ = new Float[]{
        0.0f,
        1.0f,
        2.0f,
        5.0f,
        10.0f,
        100.0f
    };
    private static final Float[] RESOLUTIONS = new Float[]{
        0.0f,
        0.1f,
        1.0f,
        10.0f
    };

    private Context mContext;
    private KitchenSinkHelper mKitchenSinkHelper;
    private CarPropertyManager mMgr;
    private List<PropertyInfo> mPropInfo = null;
    private Spinner mSubscriptionRateHz;
    private Spinner mResolution;
    private Spinner mVariableUpdateRate;
    private ToggleButton mSubscribeButton;
    private Spinner mAreaId;
    private TextView mEventLog;
    private Spinner mPropertyId;
    private ScrollView mScrollView;
    private EditText mSetValue;
    private PropertyListEventListener mListener;
    private final SparseIntArray mPropertySubscriptionRateHzSelection = new SparseIntArray();
    private final SparseIntArray mPropertyResolutionSelection = new SparseIntArray();
    private final SparseIntArray mPropertyVariableUpdateRateSelection = new SparseIntArray();
    private final SparseBooleanArray mPropertyIsSubscribedSelection = new SparseBooleanArray();
    private GetPropertyCallback mGetPropertyCallback = new GetPropertyCallback() {
        @Override
        public void onSuccess(@NonNull GetPropertyResult<?> getPropertyResult) {
            int propId = getPropertyResult.getPropertyId();
            long timestamp = getPropertyResult.getTimestampNanos();
            setTextOnSuccess(propId, timestamp, getPropertyResult.getValue(),
                    CarPropertyValue.STATUS_AVAILABLE);
        }

        @Override
        public void onFailure(@NonNull CarPropertyManager.PropertyAsyncError propertyAsyncError) {
            Log.e(TAG, "Failed to get async VHAL property");
            Toast.makeText(mContext, "Failed to get async VHAL property with error code: "
                    + propertyAsyncError.getErrorCode() + " and vendor error code: "
                    + propertyAsyncError.getVendorErrorCode(), Toast.LENGTH_SHORT).show();
        }
    };

    private CarPropertyManager.SetPropertyCallback mSetPropertyCallback =
            new CarPropertyManager.SetPropertyCallback() {
                @Override
                public void onSuccess(
                        @NonNull CarPropertyManager.SetPropertyResult setPropertyResult) {
                    Toast.makeText(mContext, "Success", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onFailure(
                        @NonNull CarPropertyManager.PropertyAsyncError propertyAsyncError) {
                    Log.e(TAG, "Failed to get async VHAL property");
                    Toast.makeText(mContext, "Failed to set async VHAL property with error code: "
                            + propertyAsyncError.getErrorCode() + " and vendor error code: "
                            + propertyAsyncError.getVendorErrorCode(), Toast.LENGTH_SHORT).show();
                }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        for (int i = 0; i < grantResults.length; i++) {
            if (grantResults[i] != PERMISSION_GRANTED) {
                Log.w(TAG, "Permission: " + permissions[i] + " is not granted, "
                        + "some properties might not be listed");
            }
        }
        Runnable r = () -> {
            mMgr = mKitchenSinkHelper.getPropertyManager();
            populateConfigList();

            // Configure dropdown menu for propertyId spinner
            ArrayAdapter<PropertyInfo> adapter =
                    new ArrayAdapter<PropertyInfo>(mContext, android.R.layout.simple_spinner_item,
                            mPropInfo);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            mPropertyId.setAdapter(adapter);
            mPropertyId.setOnItemSelectedListener(this);
        };
        mKitchenSinkHelper.requestRefreshManager(r, new Handler(getContext().getMainLooper()));
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.property, container, false);
        // Get resource IDs
        mSubscriptionRateHz = view.findViewById(R.id.sSubscriptionRate);
        mResolution = view.findViewById(R.id.sResolution);
        mVariableUpdateRate = view.findViewById(R.id.sVariableUpdateRate);
        mAreaId = view.findViewById(R.id.sAreaId);
        mEventLog = view.findViewById(R.id.tvEventLog);
        mPropertyId = view.findViewById(R.id.sPropertyId);
        mScrollView = view.findViewById(R.id.svEventLog);
        mSetValue = view.findViewById(R.id.etSetPropertyValue);
        mContext = getActivity();
        mListener = new PropertyListEventListener(mEventLog);
        if (!(mContext instanceof KitchenSinkHelper)) {
            throw new IllegalStateException(
                    "context does not implement " + KitchenSinkHelper.class.getSimpleName());
        }
        mKitchenSinkHelper = (KitchenSinkHelper) mContext;

        mSubscribeButton = view.findViewById(R.id.tbSubscribeButton);
        mSubscribeButton.setEnabled(false);

        // Configure listeners for buttons
        Button b = view.findViewById(R.id.bGetProperty);
        b.setOnClickListener(v -> {
            try {
                PropertyInfo info = (PropertyInfo) mPropertyId.getSelectedItem();
                int propId = info.mConfig.getPropertyId();
                int areaId = Integer.decode(mAreaId.getSelectedItem().toString());
                CarPropertyValue value = mMgr.getProperty(propId, areaId);
                setTextOnSuccess(propId, value.getTimestamp(), value.getValue(), value.getStatus());
            } catch (Exception e) {
                Log.e(TAG, "Failed to get VHAL property", e);
                Toast.makeText(mContext, "Failed to get VHAL property: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        });

        b = view.findViewById(R.id.getPropertyAsync);
        b.setOnClickListener(v -> {
            try {
                PropertyInfo info = (PropertyInfo) mPropertyId.getSelectedItem();
                int propId = info.mConfig.getPropertyId();
                int areaId = Integer.decode(mAreaId.getSelectedItem().toString());
                GetPropertyRequest getPropertyRequest = mMgr.generateGetPropertyRequest(propId,
                        areaId);
                mMgr.getPropertiesAsync(List.of(getPropertyRequest),
                        /* cancellationSignal= */ null, /* callbackExecutor= */ null,
                        mGetPropertyCallback);
            } catch (Exception e) {
                Log.e(TAG, "Failed to get async VHAL property", e);
                Toast.makeText(mContext, "Failed to get async VHAL property: "
                                + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        b = view.findViewById(R.id.bSetProperty);
        b.setOnClickListener(v -> {
            try {
                PropertyInfo info = (PropertyInfo) mPropertyId.getSelectedItem();
                int propId = info.mConfig.getPropertyId();
                int areaId = Integer.decode(mAreaId.getSelectedItem().toString());
                String valueString = mSetValue.getText().toString();

                switch (propId & VehiclePropertyType.MASK) {
                    case VehiclePropertyType.BOOLEAN:
                        Boolean boolVal = Boolean.parseBoolean(valueString);
                        mMgr.setBooleanProperty(propId, areaId, boolVal);
                        break;
                    case VehiclePropertyType.FLOAT:
                        Float floatVal = Float.parseFloat(valueString);
                        mMgr.setFloatProperty(propId, areaId, floatVal);
                        break;
                    case VehiclePropertyType.INT32:
                        Integer intVal = Integer.parseInt(valueString);
                        mMgr.setIntProperty(propId, areaId, intVal);
                        break;
                    default:
                        Toast.makeText(mContext, "PropertyType=0x" + toHexString(propId
                                        & VehiclePropertyType.MASK) + " is not handled!",
                                Toast.LENGTH_LONG).show();
                        break;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to set VHAL property", e);
                Toast.makeText(mContext, "Failed to set VHAL property: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        });

        b = view.findViewById(R.id.SetPropertyAsync);
        b.setOnClickListener(v -> {
            try {
                PropertyInfo info = (PropertyInfo) mPropertyId.getSelectedItem();
                int propId = info.mConfig.getPropertyId();
                int areaId = Integer.decode(mAreaId.getSelectedItem().toString());
                String valueString = mSetValue.getText().toString();

                switch (propId & VehiclePropertyType.MASK) {
                    case VehiclePropertyType.BOOLEAN:
                        Boolean boolVal = Boolean.parseBoolean(valueString);
                        callSetPropertiesAsync(propId, areaId, boolVal);
                        break;
                    case VehiclePropertyType.FLOAT:
                        Float floatVal = Float.parseFloat(valueString);
                        callSetPropertiesAsync(propId, areaId, floatVal);
                        break;
                    case VehiclePropertyType.INT32:
                        Integer intVal = Integer.parseInt(valueString);
                        callSetPropertiesAsync(propId, areaId, intVal);
                        break;
                    default:
                        Toast.makeText(mContext, "PropertyType=0x" + toHexString(propId
                                        & VehiclePropertyType.MASK) + " is not handled!",
                                Toast.LENGTH_LONG).show();
                        break;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to set VHAL property", e);
                Toast.makeText(mContext, "Failed to set async VHAL property: "
                        + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        b = view.findViewById(R.id.bClearLog);
        b.setOnClickListener(v -> {
            mEventLog.setText("");
        });

        requestPermissions(REQUIRED_DANGEROUS_PERMISSIONS, KS_PERMISSIONS_REQUEST);

        return view;
    }

    private void populateConfigList() {
        try {
            mPropInfo = mMgr.getPropertyList()
                    .stream()
                    .map(PropertyInfo::new)
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            Log.e(TAG, "Unhandled exception in populateConfigList: ", e);
        }
    }

    private void setEnabledSubscriptionScrollViews(boolean setEnabled) {
        mSubscriptionRateHz.setEnabled(setEnabled);
        mResolution.setEnabled(setEnabled);
        mVariableUpdateRate.setEnabled(setEnabled);
    }

    // Spinner callbacks
    public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
        PropertyInfo info = (PropertyInfo) parent.getItemAtPosition(pos);
        int propertyId = info.mPropId;
        int[] areaIds = info.mConfig.getAreaIds();
        List<String> areaIdsString = new ArrayList<String>();
        if (areaIds.length == 0) {
            areaIdsString.add("0x0");
        } else {
            for (int areaId : areaIds) {
                areaIdsString.add("0x" + toHexString(areaId));
            }
        }

        // Configure dropdown menu for propertyId spinner
        ArrayAdapter<String> areaIdAdapter = new ArrayAdapter<String>(mContext,
                android.R.layout.simple_spinner_item, areaIdsString);
        areaIdAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mAreaId.setAdapter(areaIdAdapter);

        int changeMode = info.mConfig.getChangeMode();
        List<String> subscriptionRateHzStrings = new ArrayList<String>();
        subscriptionRateHzStrings.add("0 Hz");
        List<String> resolutionStrings = new ArrayList<String>();
        resolutionStrings.add("0");
        List<String> vurStrings = new ArrayList<String>();
        vurStrings.add("DISABLED");

        if (changeMode == CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_STATIC) {
            setEnabledSubscriptionScrollViews(false);
            mSubscribeButton.setEnabled(false);
        } else if (changeMode == CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_ONCHANGE) {
            setEnabledSubscriptionScrollViews(false);
            mSubscribeButton.setEnabled(true);
        } else if (changeMode == CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS) {
            setEnabledSubscriptionScrollViews(true);
            mSubscribeButton.setEnabled(true);

            float maxSubRate = info.mConfig.getMaxSampleRate();
            subscriptionRateHzStrings.add("1 Hz");
            if (maxSubRate >= 2.0) {
                subscriptionRateHzStrings.add("2 Hz");
            }
            if (maxSubRate >= 5.0) {
                subscriptionRateHzStrings.add("5 Hz");
            }
            if (maxSubRate >= 10.0) {
                subscriptionRateHzStrings.add("10 Hz");
            }
            if (maxSubRate >= 100.0) {
                subscriptionRateHzStrings.add("100 Hz");
            }

            resolutionStrings.add("0.1");
            resolutionStrings.add("1");
            resolutionStrings.add("10");

            vurStrings.add("ENABLED");
        }

        if (mPropertySubscriptionRateHzSelection.get(propertyId, -1) == -1) {
            mPropertySubscriptionRateHzSelection.put(propertyId, 0);
            mPropertyResolutionSelection.put(propertyId, 0);
            mPropertyVariableUpdateRateSelection.put(propertyId, 0);
            mPropertyIsSubscribedSelection.put(propertyId, false);
        }

        ArrayAdapter<String> subscriptionRateHzAdapter = new ArrayAdapter<String>(mContext,
                android.R.layout.simple_spinner_item, subscriptionRateHzStrings);
        subscriptionRateHzAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        mSubscriptionRateHz.setAdapter(subscriptionRateHzAdapter);
        mSubscriptionRateHz.setSelection(mPropertySubscriptionRateHzSelection.get(propertyId));
        mSubscriptionRateHz.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int pos, long id) {
                mPropertySubscriptionRateHzSelection.put(info.mConfig.getPropertyId(), pos);
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
                // do nothing.
            }
        });

        ArrayAdapter<String> resolutionAdapter = new ArrayAdapter<String>(mContext,
                android.R.layout.simple_spinner_item, resolutionStrings);
        resolutionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mResolution.setAdapter(resolutionAdapter);
        mResolution.setSelection(mPropertyResolutionSelection.get(propertyId));
        mResolution.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int pos, long id) {
                mPropertyResolutionSelection.put(info.mConfig.getPropertyId(), pos);
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
                // do nothing.
            }
        });

        ArrayAdapter<String> variableUpdateRateAdapter = new ArrayAdapter<String>(mContext,
                android.R.layout.simple_spinner_item, vurStrings);
        variableUpdateRateAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        mVariableUpdateRate.setAdapter(variableUpdateRateAdapter);
        mVariableUpdateRate.setSelection(mPropertyVariableUpdateRateSelection.get(propertyId));
        mVariableUpdateRate.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int pos, long id) {
                mPropertyVariableUpdateRateSelection.put(info.mConfig.getPropertyId(), pos);
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
                // do nothing.
            }
        });

        mSubscribeButton.setChecked(mPropertyIsSubscribedSelection.get(propertyId));
        if (mSubscribeButton.isChecked()) {
            setEnabledSubscriptionScrollViews(false);
        }
        mSubscribeButton.setOnClickListener(v -> {
            Float subscriptionRateHz = SUBSCRIPTION_RATES_HZ[
                    mPropertySubscriptionRateHzSelection.get(propertyId)];
            if (mSubscribeButton.isChecked()
                    && (changeMode != CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS
                            || subscriptionRateHz != 0.0)) {
                mListener.addPropertySelectedSubscriptionRateHz(propertyId, subscriptionRateHz);
                mListener.updatePropertyStartTime(propertyId);
                mListener.resetEventCountForProperty(propertyId);

                Float resolution = RESOLUTIONS[mPropertyResolutionSelection.get(propertyId)];
                boolean variableUpdateRate =
                        mPropertyVariableUpdateRateSelection.get(propertyId) != 0;

                try {
                    mMgr.subscribePropertyEvents(List.of(
                            new Subscription.Builder(propertyId)
                                    .setUpdateRateHz(subscriptionRateHz)
                                    .setResolution(resolution)
                                    .setVariableUpdateRateEnabled(variableUpdateRate)
                                    .build()),
                            /* callbackExecutor= */ null, mListener);
                    mPropertyIsSubscribedSelection.put(propertyId, true);
                    setEnabledSubscriptionScrollViews(false);
                } catch (Exception e) {
                    Log.e(TAG, "Unhandled exception: ", e);
                }
            } else {
                try {
                    mMgr.unsubscribePropertyEvents(propertyId, mListener);
                    mPropertyIsSubscribedSelection.put(propertyId, false);
                    setEnabledSubscriptionScrollViews(true);
                } catch (Exception e) {
                    Log.e(TAG, "Unhandled exception: ", e);
                }
            }
        });
    }

    public void onNothingSelected(AdapterView<?> parent) {
        // Another interface callback
    }

    public void scrollEventLogsToBottom() {
        mScrollView.post(new Runnable() {
            public void run() {
                mScrollView.fullScroll(View.FOCUS_DOWN);
                //mListenerScrollView.smoothScrollTo(0, mTextStatus.getBottom());
            }
        });
    }

    private void setTextOnSuccess(int propId, long timestamp, Object value, int status) {
        mEventLog.append("getProperty: ");
        if (propId == VehiclePropertyIds.WHEEL_TICK) {
            Object[] ticks = (Object[]) value;
            mEventLog.append("ElapsedRealtimeNanos=" + timestamp
                    + " [0]=" + (Long) ticks[0]
                    + " [1]=" + (Long) ticks[1] + " [2]=" + (Long) ticks[2]
                    + " [3]=" + (Long) ticks[3] + " [4]=" + (Long) ticks[4]);
        } else {
            String valueString = value.getClass().isArray()
                    ? Arrays.toString((Object[]) value)
                    : value.toString();
            mEventLog.append("ElapsedRealtimeNanos=" + timestamp
                    + " status=" + status
                    + " value=" + valueString
                    + " read=" + mMgr.getReadPermission(propId)
                    + " write=" + mMgr.getWritePermission(propId));
        }
        mEventLog.append("\n");
        scrollEventLogsToBottom();
    }

    private <T> void callSetPropertiesAsync(int propId, int areaId, T request) {
        mMgr.setPropertiesAsync(
                List.of(mMgr.generateSetPropertyRequest(propId, areaId, request)),
                /* cancellationSignal= */ null,
                /* callbackExecutor= */ null, mSetPropertyCallback);
    }

    private class PropertyListEventListener implements CarPropertyEventCallback {
        private final TextView mTvLogEvent;
        private final SparseArray<Float> mPropSubscriptionRateHz = new SparseArray<>();
        private final SparseLongArray mStartTime = new SparseLongArray();
        private final SparseIntArray mNumEvents = new SparseIntArray();

        PropertyListEventListener(TextView logEvent) {
            mTvLogEvent = logEvent;
        }

        void addPropertySelectedSubscriptionRateHz(Integer propId, Float subscriptionRateHz) {
            mPropSubscriptionRateHz.put(propId, subscriptionRateHz);
        }

        void updatePropertyStartTime(Integer propId) {
            mStartTime.put(propId, System.currentTimeMillis());
        }

        void resetEventCountForProperty(Integer propId) {
            mNumEvents.put(propId, 0);
        }

        @Override
        public void onChangeEvent(CarPropertyValue value) {
            int propId = value.getPropertyId();
            int areaId = value.getAreaId();

            mNumEvents.put(propId, mNumEvents.get(propId) + 1);

            String valueString = value.getValue().getClass().isArray()
                    ? Arrays.toString((Object[]) value.getValue())
                    : value.getValue().toString();

            mTvLogEvent.append(String.format("Event %1$s: elapsedRealtimeNanos=%2$s propId=0x%3$s "
                    + "areaId=0x%4$s name=%5$s status=%6$s value=%7$s", mNumEvents.get(propId),
                    value.getTimestamp(), toHexString(propId), toHexString(areaId),
                    VehiclePropertyIds.toString(propId), value.getStatus(), valueString));
            if (mPropSubscriptionRateHz.contains(propId)) {
                mTvLogEvent.append(
                        String.format(" selected subscription rate (Hz)=%1$s "
                                + "actual subscription rate (Hz)=%2$s\n",
                                mPropSubscriptionRateHz.get(propId),
                                mNumEvents.get(propId) * 1000.0f / (System.currentTimeMillis()
                                        - mStartTime.get(propId))));
            } else {
                mTvLogEvent.append("\n");
            }
            scrollEventLogsToBottom();
        }

        @Override
        public void onErrorEvent(int propId, int areaId) {
            mTvLogEvent.append("Received error event propId=0x"
                    + VehiclePropertyIds.toString(propId) + ", areaId=0x" + toHexString(areaId));
            scrollEventLogsToBottom();
        }
    }
}
