LOCAL_PATH:= $(call my-dir)

##################################
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    evs_test.cpp \
    EvsStateControl.cpp \
    StreamHandler.cpp \

LOCAL_C_INCLUDES += \
    frameworks/base/include \
    packages/services/Car/evs/test \

LOCAL_SHARED_LIBRARIES := \
    android.hardware.automotive.evs@1.0 \
    liblog \
    libutils \
    libui \
    libhidlbase \
    libhidltransport \

LOCAL_STRIP_MODULE := keep_symbols

LOCAL_MODULE:= evs_test
LOCAL_MODULE_TAGS := optional

LOCAL_CFLAGS += -Wall -Werror -Wunused -Wunreachable-code
LOCAL_CFLAGS += -O0 -ggdb

include $(BUILD_EXECUTABLE)
