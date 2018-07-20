# Copyright (C) 2015 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
#

#disble build in PDK, missing aidl import breaks build
ifneq ($(TARGET_BUILD_PDK),true)

LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

car_lib_sources := $(call all-java-files-under, src)
car_lib_sources += $(call all-java-files-under, src_feature_future)
car_lib_sources += $(call all-Iaidl-files-under, src)

ifeq ($(BOARD_IS_AUTOMOTIVE), true)
full_classes_jar := $(call intermediates-dir-for,JAVA_LIBRARIES,android.car,,COMMON)/classes.jar
$(call dist-for-goals,dist_files,$(full_classes_jar):android.car.jar)
endif

# API Check
# ---------------------------------------------
car_module := android.car
car_module_src_files := $(car_lib_sources)
car_module_api_dir := $(LOCAL_PATH)/api
car_module_java_libraries := framework
car_module_include_systemapi := true
car_module_java_packages := android.car*
include $(CAR_API_CHECK)

include $(CLEAR_VARS)

LOCAL_MODULE := android.car7
LOCAL_SRC_FILES := $(car_lib_sources)
LOCAL_JAVA_LANGUAGE_VERSION := 1.7
LOCAL_AIDL_INCLUDES += system/bt/binder

ifeq ($(EMMA_INSTRUMENT_FRAMEWORK),true)
LOCAL_EMMA_INSTRUMENT := true
endif

include $(BUILD_JAVA_LIBRARY)
$(call dist-for-goals,dist_files,$(full_classes_jar):$(LOCAL_MODULE).jar)

endif #TARGET_BUILD_PDK
