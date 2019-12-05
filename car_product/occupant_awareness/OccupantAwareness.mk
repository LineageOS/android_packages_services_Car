# Occupant Awareness SELinux policy variable definitions
LOCAL_PATH:= $(call my-dir)

BOARD_PLAT_PUBLIC_SEPOLICY_DIR += $(LOCAL_PATH)/sepolicy/public
BOARD_PLAT_PRIVATE_SEPOLICY_DIR += $(LOCAL_PATH)/sepolicy/private

BOARD_SEPOLICY_DIRS += $(LOCAL_PATH)/sepolicy
