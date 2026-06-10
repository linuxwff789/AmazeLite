LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := zpaq715_fixed
LOCAL_SRC_FILES := zpaq_jni.cpp zpaq_official_jni.cpp zpaq/libzpaq.cpp
LOCAL_C_INCLUDES := $(LOCAL_PATH)/zpaq $(LOCAL_PATH)/zpaqcli
LOCAL_CPPFLAGS := -std=c++17 -fexceptions -frtti -Dunix
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)