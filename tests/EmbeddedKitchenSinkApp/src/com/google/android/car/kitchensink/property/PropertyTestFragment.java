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
import android.car.feature.Flags;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback;
import android.car.hardware.property.CarPropertyManager.GetPropertyCallback;
import android.car.hardware.property.CarPropertyManager.GetPropertyRequest;
import android.car.hardware.property.CarPropertyManager.GetPropertyResult;
import android.car.hardware.property.CarPropertyManager.SupportedValuesChangeCallback;
import android.car.hardware.property.MinMaxSupportedValue;
import android.car.hardware.property.Subscription;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.ArrayMap;
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
import android.widget.AutoCompleteTextView;
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
        Car.PERMISSION_CAR_DRIVING_STATE_3P,
        Car.PERMISSION_CAR_ENGINE_DETAILED_3P,
        Car.PERMISSION_MILEAGE_3P,
        Car.PERMISSION_ENERGY,
        Car.PERMISSION_READ_CAR_SEATS,
        Car.PERMISSION_READ_EXTERIOR_LIGHTS,
        Car.PERMISSION_READ_STEERING_STATE_3P,
        Car.PERMISSION_SPEED,
        Car.PERMISSION_TIRES_3P,
        Car.PERMISSION_READ_WINDSHIELD_WIPERS_3P,
        Car.PERMISSION_READ_CAR_HORN,
        Car.PERMISSION_READ_CAR_PEDALS,
        Car.PERMISSION_READ_BRAKE_INFO
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
    private ToggleButton mSubscribeSupportedValuesChangeButton;
    private Spinner mAreaId;
    private TextView mEventLog;
    private AutoCompleteTextView mPropertyNameInput;
    private Spinner mPropertyId;
    private ScrollView mScrollView;
    private EditText mSetValue;
    private PropertyListEventListener mListener;
    private final SparseIntArray mPropertySubscriptionRateHzSelection = new SparseIntArray();
    private final SparseIntArray mPropertyResolutionSelection = new SparseIntArray();
    private final SparseIntArray mPropertyVariableUpdateRateSelection = new SparseIntArray();
    private final SparseBooleanArray mPropertyIsSubscribedSelection = new SparseBooleanArray();
    private final SparseBooleanArray mPropertyIsSubscribedSupportedValuesChange =
            new SparseBooleanArray();
    private GetPropertyCallback mGetPropertyCallback = new GetPropertyCallback() {
        @Override
        public void onSuccess(@NonNull GetPropertyResult<?> getPropertyResult) {
            setTextOnSuccess(getPropertyResult.getPropertyId(), getPropertyResult.getAreaId(),
                    getPropertyResult.getTimestampNanos(),
                    getPropertyResult.getValue(),
                    CarPropertyValue.STATUS_AVAILABLE);
        }

        @Override
        public void onFailure(@NonNull CarPropertyManager.PropertyAsyncError propertyAsyncError) {
            Log.e(TAG, "Failed to get async VHAL property");
            Toast.makeText(mContext, "Failed to get async VHAL property", Toast.LENGTH_SHORT)
                    .show();
            mEventLog.append(String.format(
                            "getProperty(%s, %d): fail, errorCode: %d, vendorErrorCode: %d\n",
                            PropertyInfo.getPropertyName(propertyAsyncError.getPropertyId()),
                            propertyAsyncError.getAreaId(),
                            propertyAsyncError.getErrorCode(),
                            propertyAsyncError.getVendorErrorCode()));
            scrollEventLogsToBottom();
        }
    };

    private CarPropertyManager.SetPropertyCallback mSetPropertyCallback =
            new CarPropertyManager.SetPropertyCallback() {
                @Override
                public void onSuccess(
                        @NonNull CarPropertyManager.SetPropertyResult setPropertyResult) {
                    Toast.makeText(mContext, "Success", Toast.LENGTH_SHORT).show();
                    mEventLog.append(String.format("setProperty(%s, %d): success\n",
                            PropertyInfo.getPropertyName(setPropertyResult.getPropertyId()),
                            setPropertyResult.getAreaId()));
                    scrollEventLogsToBottom();
                }

                @Override
                public void onFailure(
                        @NonNull CarPropertyManager.PropertyAsyncError propertyAsyncError) {
                    Log.e(TAG, "Failed to get async VHAL property");
                    Toast.makeText(mContext, "Failed to set async VHAL property",
                            Toast.LENGTH_SHORT).show();
                    mEventLog.append(String.format(
                            "setProperty(%s, %d): fail, errorCode: %d, vendorErrorCode: %d\n",
                            PropertyInfo.getPropertyName(propertyAsyncError.getPropertyId()),
                            propertyAsyncError.getAreaId(),
                            propertyAsyncError.getErrorCode(),
                            propertyAsyncError.getVendorErrorCode()));
                    scrollEventLogsToBottom();
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
            ArrayAdapter<PropertyInfo> propertyIdAdapter =
                    new ArrayAdapter<PropertyInfo>(mContext, android.R.layout.simple_spinner_item,
                            mPropInfo);
            propertyIdAdapter.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item);
            ArrayMap<String, Integer> propertyIdAdapterPositionByName = new ArrayMap<>();
            for (int i = 0; i < mPropInfo.size(); i++) {
                propertyIdAdapterPositionByName.put(mPropInfo.get(i).toString(), i);
            }
            mPropertyId.setAdapter(propertyIdAdapter);
            mPropertyId.setOnItemSelectedListener(this);
            var propertyNameAdapter = new ArrayAdapter<PropertyInfo>(
                    mContext, android.R.layout.simple_dropdown_item_1line,
                    mPropInfo);
            mPropertyNameInput.setAdapter(propertyNameAdapter);
            mPropertyNameInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void afterTextChanged(Editable s) {
                    String propertyName = s.toString();
                    if (propertyName.equals("")) {
                        return;
                    }
                    Integer index = propertyIdAdapterPositionByName.get(propertyName);
                    if (index != null) {
                        mPropertyId.setSelection(index);
                    }
                }

                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
            });
        };
        mKitchenSinkHelper.requestRefreshManager(r, new Handler(getContext().getMainLooper()));
    }

    private @Nullable Integer getSelectedPropertyId() {
        PropertyInfo info = getSelectedPropertyInfo();
        if (info == null) {
            return null;
        }
        return info.mConfig.getPropertyId();
    }

    private int getSelectedAreaId() {
        return Integer.decode(mAreaId.getSelectedItem().toString());
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
        mPropertyNameInput = view.findViewById(R.id.autoCompletePropertyNameInput);
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

        mSubscribeSupportedValuesChangeButton = view.findViewById(
                R.id.tbSubscribeSupportedValuesChangeButton);

        // Configure listeners for buttons
        Button b = view.findViewById(R.id.bGetProperty);
        b.setOnClickListener(v -> {
            Integer propId = getSelectedPropertyId();
            if (propId == null) {
                return;
            }
            int areaId = getSelectedAreaId();
            try {
                CarPropertyValue value = mMgr.getProperty(propId, areaId);
                setTextOnSuccess(propId, areaId, value.getTimestamp(), value.getValue(),
                        value.getStatus());
            } catch (Exception e) {
                showExceptionMessage(e, "getProperty failed",
                        String.format("getProperty(%s, %d): failed",
                                PropertyInfo.getPropertyName(propId), areaId));
            }
        });

        b = view.findViewById(R.id.getPropertyAsync);
        b.setOnClickListener(v -> {
            Integer propId = getSelectedPropertyId();
            if (propId == null) {
                return;
            }
            int areaId = getSelectedAreaId();
            try {
                GetPropertyRequest getPropertyRequest = mMgr.generateGetPropertyRequest(propId,
                        areaId);
                mMgr.getPropertiesAsync(List.of(getPropertyRequest),
                        /* cancellationSignal= */ null, /* callbackExecutor= */ null,
                        mGetPropertyCallback);
            } catch (Exception e) {
                showExceptionMessage(e, "async getProperty failed",
                        String.format("async getProperty(%s, %d): failed",
                                PropertyInfo.getPropertyName(propId), areaId));
            }
        });

        b = view.findViewById(R.id.bGetMinMaxSupportedValues);
        b.setOnClickListener(v -> {
            Integer propId = getSelectedPropertyId();
            if (propId == null) {
                return;
            }
            int areaId = getSelectedAreaId();
            try {
                MinMaxSupportedValue<Object> minMaxSupportedValue = mMgr.getMinMaxSupportedValue(
                        propId, areaId);

                mEventLog.append(String.format("getMinMaxSupportedValue(%s, %d):",
                        PropertyInfo.getPropertyName(propId), areaId));
                Object minValue = minMaxSupportedValue.getMinValue();
                Object maxValue = minMaxSupportedValue.getMaxValue();
                if (minValue == null && maxValue == null) {
                    mEventLog.append("not specified\n");
                } else {
                    String message = "";
                    if (minValue != null) {
                        message += "MinValue: " + minValue;
                    }
                    if (maxValue != null) {
                        if (!message.equals("")) {
                            message += ", ";
                        }
                        message += "MaxValue: " + maxValue;
                    }
                    mEventLog.append(message + "\n");
                }
                scrollEventLogsToBottom();
            } catch (Exception e) {
                showExceptionMessage(e, "getMinMaxSupportedValue failed",
                        String.format("getMinMaxSupportedValue(%s, %d): failed",
                                PropertyInfo.getPropertyName(propId), areaId));
            }
        });

        b = view.findViewById(R.id.bGetSupportedValuesList);
        b.setOnClickListener(v -> {
            Integer propId = getSelectedPropertyId();
            if (propId == null) {
                return;
            }
            int areaId = getSelectedAreaId();
            try {
                List<Object> supportedValuesList = mMgr.getSupportedValuesList(
                        propId, areaId);

                mEventLog.append(String.format("getSupportedValuesList(%s, %d):",
                        PropertyInfo.getPropertyName(propId), areaId));
                if (supportedValuesList == null) {
                    mEventLog.append("not specified\n");
                } else {
                    mEventLog.append(supportedValuesList + "\n");
                }
                scrollEventLogsToBottom();
            } catch (Exception e) {
                showExceptionMessage(e, "getSupportedValuesList failed",
                        String.format("getSupportedValuesList(%s, %d): failed",
                                PropertyInfo.getPropertyName(propId), areaId));
            }
        });

        b = view.findViewById(R.id.bSetProperty);
        b.setOnClickListener(v -> {
            Integer propId = getSelectedPropertyId();
            if (propId == null) {
                return;
            }
            int areaId = getSelectedAreaId();
            try {
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
                showExceptionMessage(e, "setProperty failed",
                        String.format("setProperty(%s, %d): failed",
                                PropertyInfo.getPropertyName(propId), areaId));
            }
        });

        b = view.findViewById(R.id.SetPropertyAsync);
        b.setOnClickListener(v -> {
            Integer propId = getSelectedPropertyId();
            if (propId == null) {
                return;
            }
            int areaId = getSelectedAreaId();
            try {
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
                showExceptionMessage(e, "async setProperty failed",
                        String.format("async setProperty(%s, %d): failed",
                                PropertyInfo.getPropertyName(propId), areaId));
            }
        });

        b = view.findViewById(R.id.bClearLog);
        b.setOnClickListener(v -> {
            mEventLog.setText("");
        });

        mSubscribeButton.setOnClickListener(v -> {
            PropertyInfo info = getSelectedPropertyInfo();
            if (info == null) {
                return;
            }
            int propertyId = info.mConfig.getPropertyId();
            int changeMode = info.mConfig.getChangeMode();
            Float subscriptionRateHz = SUBSCRIPTION_RATES_HZ[
                    mPropertySubscriptionRateHzSelection.get(propertyId, 0)];
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

        mSubscribeSupportedValuesChangeButton.setOnClickListener(v -> {
            Integer propertyId = getSelectedPropertyId();
            if (propertyId == null) {
                return;
            }
            if (mSubscribeSupportedValuesChangeButton.isChecked()) {
                try {
                    mMgr.registerSupportedValuesChangeCallback(propertyId, mListener);
                    mPropertyIsSubscribedSupportedValuesChange.put(propertyId, true);
                } catch (Exception e) {
                    Log.e(TAG, "Unhandled exception: ", e);
                }
            } else {
                try {
                    mMgr.unregisterSupportedValuesChangeCallback(propertyId);
                    mPropertyIsSubscribedSupportedValuesChange.put(propertyId, false);
                } catch (Exception e) {
                    Log.e(TAG, "Unhandled exception: ", e);
                }
            }
        });

        requestPermissions(REQUIRED_DANGEROUS_PERMISSIONS, KS_PERMISSIONS_REQUEST);

        return view;
    }

    private @Nullable PropertyInfo getSelectedPropertyInfo() {
        PropertyInfo info = (PropertyInfo) mPropertyId.getSelectedItem();
        String propertyName = mPropertyNameInput.getText().toString();
        if (!propertyName.equals("") && !info.toString().equals(propertyName)) {
            // This means user manually input the property name and it is not one of the valid
            // choices in the spinner.
            Toast.makeText(mContext, "Invalid property name: " + propertyName, Toast.LENGTH_SHORT)
                    .show();
            return null;
        }
        return info;
    }

    private void showExceptionMessage(Exception e, String briefContext, String context) {
        Toast.makeText(mContext, briefContext, Toast.LENGTH_SHORT).show();
        mEventLog.append(context + ": " + e.getMessage() + "\n");
        Log.e(TAG, context + ": " + e.getMessage());
        scrollEventLogsToBottom();
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
        String propertyName = info.toString();
        // Clear property name input box if it is selected from the spinner.
        if (!mPropertyNameInput.getText().toString().equals(propertyName)) {
            mPropertyNameInput.setText("");
        }
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

        // Configure dropdown menu for areaId spinner
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
        mSubscribeSupportedValuesChangeButton.setChecked(
                mPropertyIsSubscribedSupportedValuesChange.get(propertyId));
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

    private void setTextOnSuccess(int propId, int areaId, long timestamp,
            Object value, int status) {
        String propertyName = PropertyInfo.getPropertyName(propId);
        mEventLog.append(String.format("getProperty(%s, %d):",
                        PropertyInfo.getPropertyName(propId), areaId));
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
                    + " value=" + valueString
                    + " status=" + status
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

    private class PropertyListEventListener implements CarPropertyEventCallback,
            SupportedValuesChangeCallback {
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

            String statusString;
            if (Flags.carPropertyStatusDetailedNotAvailable()) {
                statusString = String.format("systemStatus=%s vendorStatus=%s",
                        value.getPropertyStatus(), value.getPropertyVendorStatus());
            } else {
                statusString = String.format("status=%s", value.getStatus());
            }

            mTvLogEvent.append(String.format("Event %1$s: elapsedRealtimeNanos=%2$s "
                            + "propId=0x%3$s areaId=0x%4$s name=%5$s %6$s value=%7$s",
                    mNumEvents.get(propId),
                    value.getTimestamp(),
                    toHexString(propId),
                    toHexString(areaId),
                    PropertyInfo.getPropertyName(propId),
                    statusString,
                    valueString));

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
            mTvLogEvent.append("Received error event propId="
                    + PropertyInfo.getPropertyName(propId) + ", areaId=0x" + toHexString(areaId)
                    + "\n");
            scrollEventLogsToBottom();
        }

        @Override
        public void onSupportedValuesChange(int propId, int areaId) {
            mTvLogEvent.append("Received onSupportedValuesChange event propId="
                    + PropertyInfo.getPropertyName(propId) + ", areaId=0x" + toHexString(areaId)
                    + "\n");
            scrollEventLogsToBottom();
        }
    }
}
