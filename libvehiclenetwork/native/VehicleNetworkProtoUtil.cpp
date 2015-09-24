/*
 * Copyright (C) 2015 The Android Open Source Project
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

#define LOG_TAG "VehicleNetwork"

#include <utils/Log.h>

#include <IVehicleNetwork.h>
#include "VehicleNetworkProtoUtil.h"

namespace android {

static status_t copyString(const std::string& in, uint8_t** out, int32_t* len) {
    *len = in.length();
    *out = new uint8_t[*len];
    ASSERT_OR_HANDLE_NO_MEMORY(*out, return NO_MEMORY);
    memcpy(*out, in.data(), *len);
    return NO_ERROR;
}

status_t VehicleNetworkProtoUtil::toVehiclePropValue(const vehicle_prop_value_t& in,
        VehiclePropValue& out, bool /*inPlace*/) {
    out.set_prop(in.prop);
    out.set_value_type(in.value_type);
    out.set_timestamp(in.timestamp);
    switch (in.value_type) {
        case VEHICLE_VALUE_TYPE_STRING: {
            //TODO fix ugly copy here for inplace mode
            out.set_string_value((char*)in.value.str_value.data, in.value.str_value.len);
        } break;
        case VEHICLE_VALUE_TYPE_BYTES: {
            out.set_bytes_value(in.value.bytes_value.data, in.value.bytes_value.len);
        } break;
        case VEHICLE_VALUE_TYPE_FLOAT: {
            out.add_float_values(in.value.float_value);
        } break;
        case VEHICLE_VALUE_TYPE_FLOAT_VEC2:
        case VEHICLE_VALUE_TYPE_FLOAT_VEC3:
        case VEHICLE_VALUE_TYPE_FLOAT_VEC4: {
            int expectedSize = in.value_type - VEHICLE_VALUE_TYPE_FLOAT_VEC2 + 2;
            for (int i = 0; i < expectedSize; i++) {
                out.add_float_values(in.value.float_array[i]);
            }
        } break;
        case VEHICLE_VALUE_TYPE_INT64: {
            out.set_int64_value(in.value.int64_value);
        } break;
        case VEHICLE_VALUE_TYPE_INT32:
        case VEHICLE_VALUE_TYPE_BOOLEAN: {
            out.add_int32_values(in.value.int32_value);
        } break;
        case VEHICLE_VALUE_TYPE_INT32_VEC2:
        case VEHICLE_VALUE_TYPE_INT32_VEC3:
        case VEHICLE_VALUE_TYPE_INT32_VEC4: {
            int expectedSize = in.value_type - VEHICLE_VALUE_TYPE_INT32_VEC2 + 2;
            for (int i = 0; i < expectedSize; i++) {
                out.add_int32_values(in.value.int32_array[i]);
            }
        } break;
        case VEHICLE_VALUE_TYPE_ZONED_INT32:
        case VEHICLE_VALUE_TYPE_ZONED_BOOLEAN: {
            ZonedValue* zonedValue = new ZonedValue();
            ASSERT_OR_HANDLE_NO_MEMORY(zonedValue, return NO_MEMORY);
            zonedValue->set_zone_or_window(in.value.zoned_int32_value.zone);
            zonedValue->set_int32_value(in.value.zoned_int32_value.value);
            out.set_allocated_zoned_value(zonedValue);
        } break;
        case VEHICLE_VALUE_TYPE_ZONED_FLOAT: {
            ZonedValue* zonedValue = new ZonedValue();
            ASSERT_OR_HANDLE_NO_MEMORY(zonedValue, return NO_MEMORY);
            zonedValue->set_zone_or_window(in.value.zoned_int32_value.zone);
            zonedValue->set_float_value(in.value.zoned_float_value.value);
            out.set_allocated_zoned_value(zonedValue);
        } break;
    }
    return NO_ERROR;
}

status_t VehicleNetworkProtoUtil::fromVehiclePropValue(const VehiclePropValue& in,
        vehicle_prop_value_t& out, bool /*inPlace*/) {
    out.prop = in.prop();
    out.value_type = in.value_type();
    out.timestamp = in.timestamp();
    switch (out.value_type) {
        case VEHICLE_VALUE_TYPE_STRING: {
            if (!in.has_string_value()) {
                // set to NULL so that client can just delete this safely.
                out.value.str_value.data = NULL;
                out.value.str_value.len = 0;
                ALOGE("no string value");
                return BAD_VALUE;
            }
            //TODO fix copy...
            status_t r = copyString(in.string_value(), &(out.value.str_value.data),
                    &(out.value.str_value.len));
            if (r != NO_ERROR) {
                out.value.str_value.data = NULL;
                out.value.str_value.len = 0;
                return r;
            }
        } break;
        case VEHICLE_VALUE_TYPE_BYTES: {
            if (!in.has_bytes_value()) {
                out.value.bytes_value.data = NULL;
                out.value.bytes_value.len = 0;
                ALOGE("no bytes value");
                return BAD_VALUE;
            }
            status_t r = copyString(in.bytes_value(), &(out.value.bytes_value.data),
                    &(out.value.bytes_value.len));
            if (r != NO_ERROR) {
                out.value.bytes_value.data = NULL;
                out.value.bytes_value.len = 0;
                return r;
            }
        } break;
        case VEHICLE_VALUE_TYPE_FLOAT: {
            if (in.float_values_size() != 1) {
                ALOGE("float value, wrong size %d, expecting 1", in.float_values_size());
                return BAD_VALUE;
            }
            out.value.float_value = in.float_values(0);
        } break;
        case VEHICLE_VALUE_TYPE_FLOAT_VEC2:
        case VEHICLE_VALUE_TYPE_FLOAT_VEC3:
        case VEHICLE_VALUE_TYPE_FLOAT_VEC4: {
            int expectedSize = out.value_type - VEHICLE_VALUE_TYPE_FLOAT_VEC2 + 2;
            if (in.float_values_size() != expectedSize) {
                ALOGE("float value, wrong size %d, expecting %d", in.float_values_size(),
                        expectedSize);
                return BAD_VALUE;
            }
            for (int i = 0; i < expectedSize; i++) {
                out.value.float_array[i] = in.float_values(i);
            }
        } break;
        case VEHICLE_VALUE_TYPE_INT64: {
            if (!in.has_int64_value()) {
                ALOGE("no int64 value");
                return BAD_VALUE;
            }
            out.value.int64_value = in.int64_value();
        } break;
        case VEHICLE_VALUE_TYPE_INT32:
        case VEHICLE_VALUE_TYPE_BOOLEAN: {
            if (in.int32_values_size() != 1) {
                ALOGE("no int32 value");
                return BAD_VALUE;
            }
            out.value.int32_value = in.int32_values(0);
        } break;
        case VEHICLE_VALUE_TYPE_INT32_VEC2:
        case VEHICLE_VALUE_TYPE_INT32_VEC3:
        case VEHICLE_VALUE_TYPE_INT32_VEC4: {
            int expectedSize = out.value_type - VEHICLE_VALUE_TYPE_INT32_VEC2 + 2;
            if (in.int32_values_size() != expectedSize) {
                ALOGE("int32 value, wrong size %d, expecting %d", in.int32_values_size(),
                        expectedSize);
                return BAD_VALUE;
            }
            for (int i = 0; i < expectedSize; i++) {
                out.value.int32_array[i] = in.int32_values(i);
            }
        } break;
        case VEHICLE_VALUE_TYPE_ZONED_INT32:
        case VEHICLE_VALUE_TYPE_ZONED_BOOLEAN: {
            if (!in.has_zoned_value()) {
                ALOGE("no zoned value");
                return BAD_VALUE;
            }
            const ZonedValue& zonedValue = in.zoned_value();
            if (!zonedValue.has_int32_value()) {
                ALOGE("no int32 in zoned value");
                return BAD_VALUE;
            }
            out.value.zoned_int32_value.zone = zonedValue.zone_or_window();
            out.value.zoned_int32_value.value = zonedValue.int32_value();
        } break;
        case VEHICLE_VALUE_TYPE_ZONED_FLOAT: {
            if (!in.has_zoned_value()) {
                ALOGE("no zoned value");
                return BAD_VALUE;
            }
            const ZonedValue& zonedValue = in.zoned_value();
            if (!zonedValue.has_float_value()) {
                ALOGE("no float in zoned value");
                return BAD_VALUE;
            }
            out.value.zoned_float_value.zone = zonedValue.zone_or_window();
            out.value.zoned_float_value.value = zonedValue.float_value();
        } break;
        default:
            return BAD_VALUE;
    }
    return NO_ERROR;
}

status_t VehicleNetworkProtoUtil::toVehiclePropValues(const List<vehicle_prop_value_t*>& in,
            VehiclePropValues& out) {
    status_t r;
    for (auto& v : in) {
        VehiclePropValue* value = out.add_values();
        r = toVehiclePropValue(*v, *value);
        if (r != NO_ERROR) {
            out.clear_values();
            return r;
        }
    }
    return NO_ERROR;
}

status_t VehicleNetworkProtoUtil::fromVehiclePropValues(const VehiclePropValues& in,
            List<vehicle_prop_value_t*>& out) {
    status_t r;
    for (int i = 0; i < in.values_size(); i++) {
        vehicle_prop_value_t* v =  new vehicle_prop_value_t();
        memset(v, 0, sizeof(vehicle_prop_value_t));
        ASSERT_OR_HANDLE_NO_MEMORY(v, r = NO_MEMORY;goto error);
        status_t r = fromVehiclePropValue(in.values(i), *v);
        if (r != NO_ERROR) {
            delete v;
            goto error;
        }
        out.push_back(v);
    }
    return NO_ERROR;
error:
    // clean up everything in List
    for (auto pv : out) {
        VehiclePropValueUtil::deleteMembers(pv);
    }
    return r;
}

status_t VehicleNetworkProtoUtil::toVehiclePropConfig(const vehicle_prop_config_t& in,
        VehiclePropConfig& out) {
    out.set_prop(in.prop);
    out.set_access(in.access);
    out.set_change_mode(in.change_mode);
    out.set_value_type(in.value_type);
    out.set_permission_model(in.permission_model);
    out.set_config_flags(in.config_flags);
    if (in.config_string.data != NULL && in.config_string.len != 0) {
        out.set_config_string((char*)in.config_string.data, in.config_string.len);
    } else {
        out.clear_config_string();
    }
    switch (in.value_type) {
        case VEHICLE_VALUE_TYPE_FLOAT:
        case VEHICLE_VALUE_TYPE_ZONED_FLOAT: {
            out.set_float_max(in.float_max_value);
            out.set_float_min(in.float_min_value);
        } break;
        case VEHICLE_VALUE_TYPE_INT64: {
            out.set_int64_max(in.int64_max_value);
            out.set_int64_min(in.int64_min_value);
        } break;
        case VEHICLE_VALUE_TYPE_INT32:
        case VEHICLE_VALUE_TYPE_ZONED_INT32: {
            out.set_int32_max(in.int32_max_value);
            out.set_int32_min(in.int32_min_value);
        } break;
    }
    out.set_sample_rate_max(in.max_sample_rate);
    out.set_sample_rate_min(in.min_sample_rate);
    return NO_ERROR;
}

status_t VehicleNetworkProtoUtil::fromVehiclePropConfig(const VehiclePropConfig& in,
        vehicle_prop_config_t& out) {
    out.prop = in.prop();
    out.access = in.access();
    out.change_mode = in.change_mode();
    out.value_type = in.value_type();
    out.permission_model = in.permission_model();
    out.config_flags = in.config_flags();
    if (in.has_config_string()) {
        status_t r = copyString(in.config_string(), &(out.config_string.data),
                &(out.config_string.len));
        if (r != NO_ERROR) {
            return r;
        }
    } else {
        out.config_string.data = NULL;
        out.config_string.len = 0;
    }
    switch (out.value_type) {
        case VEHICLE_VALUE_TYPE_FLOAT:
        case VEHICLE_VALUE_TYPE_ZONED_FLOAT: {
            if (in.has_float_max() && in.has_float_min()) {
                out.float_max_value = in.float_max();
                out.float_min_value = in.float_min();
            } else {
                ALOGW("no float max/min for property 0x%x", out.prop);
                out.float_max_value = 0;
                out.float_min_value = 0;
            }
        } break;
        case VEHICLE_VALUE_TYPE_INT64: {
            if (in.has_int64_max() && in.has_int64_min()) {
                out.int64_max_value = in.int64_max();
                out.int64_min_value = in.int64_min();
            } else {
                ALOGW("no int64 max/min for property 0x%x", out.prop);
                out.int64_max_value = 0;
                out.int64_min_value = 0;
            }
        } break;
        case VEHICLE_VALUE_TYPE_INT32:
        case VEHICLE_VALUE_TYPE_ZONED_INT32: {
            if (in.has_int32_max() && in.has_int32_min()) {
                out.int32_max_value = in.int32_max();
                out.int32_min_value = in.int32_min();
            } else {
                ALOGW("no int32 max/min for property 0x%x", out.prop);
                out.int32_max_value = 0;
                out.int32_min_value = 0;
            }
        } break;
    }
    out.max_sample_rate = in.sample_rate_max();
    out.min_sample_rate = in.sample_rate_min();
    return NO_ERROR;
}

status_t VehicleNetworkProtoUtil::toVehiclePropConfigs(List<vehicle_prop_config_t const*> &in,
        VehiclePropConfigs& out) {
    status_t r;
    for (auto& inEntry : in) {
        VehiclePropConfig* config = out.add_configs();
        r = toVehiclePropConfig(*inEntry, *config);
        if (r != NO_ERROR) {
            out.clear_configs();
            return r;
        }
    }
    return NO_ERROR;
}

status_t VehicleNetworkProtoUtil::fromVehiclePropConfigs(const VehiclePropConfigs& in,
        List<vehicle_prop_config_t const*>& out) {
    int32_t n = in.configs_size();
    status_t r;
    for (int32_t i = 0; i < n; i++) {
        vehicle_prop_config_t* entry = new vehicle_prop_config_t();
        ASSERT_OR_HANDLE_NO_MEMORY(entry, r = NO_MEMORY; goto error);
        memset(entry, 0, sizeof(vehicle_prop_config_t));
        r = fromVehiclePropConfig(in.configs(i), *entry);
        if (r != NO_ERROR) {
            goto error;
        }
        out.push_back(entry);
    }
    return NO_ERROR;
error:
    for (auto& e : out) {
        vehicle_prop_config_t* eDelete = const_cast<vehicle_prop_config_t*>(e);
        VehiclePropertiesUtil::deleteMembers(eDelete);
        delete eDelete;
    }
    out.clear();
    return r;
}

}; //namespace android

