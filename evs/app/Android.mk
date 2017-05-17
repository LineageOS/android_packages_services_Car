LOCAL_PATH:= $(call my-dir)

##################################
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    evs_app.cpp \
    EvsStateControl.cpp \
    RenderBase.cpp \
    RenderDirectView.cpp \
    RenderTopView.cpp \
    ConfigManager.cpp \
    glError.cpp \
    shader.cpp \
    TexWrapper.cpp \
    VideoTex.cpp \
    StreamHandler.cpp \
    WindowSurface.cpp \
    FormatConvert.cpp \

LOCAL_C_INCLUDES += \
    frameworks/base/include \
    packages/services/Car/evs/app \

LOCAL_SHARED_LIBRARIES := \
    libcutils \
    liblog \
    libutils \
    libui \
    libgui \
    libhidlbase \
    libhidltransport \
    libEGL \
    libGLESv2 \
    libhardware \
    libpng \
    android.hardware.automotive.evs@1.0 \
    android.hardware.automotive.vehicle@2.0 \

LOCAL_STATIC_LIBRARIES := \
    libmath \
    libjsoncpp \

LOCAL_STRIP_MODULE := keep_symbols

LOCAL_MODULE:= evs_app
LOCAL_MODULE_TAGS := optional

LOCAL_CFLAGS += -DGL_GLEXT_PROTOTYPES -DEGL_EGLEXT_PROTOTYPES -DLOG_TAG=\"EVSAPP\"
LOCAL_CFLAGS += -Wall -Werror -Wunused -Wunreachable-code

include $(BUILD_EXECUTABLE)
