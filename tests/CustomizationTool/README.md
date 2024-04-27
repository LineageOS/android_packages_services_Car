# Customization Tool

This is a privileged app for testing UI customizations (e.g. RROs) on AAOS.

## Usage

**Build and install**

```
m -j CustomizationTool && adb install $ANDROID_PRODUCT_OUT/system/priv-app/CustomizationTool/CustomizationTool.apk
```

**Start the service**

The easiest way to start the service is through KitchenSink through the "Customization Tool" option
in the menu. Otherwise adb can be used:

```
adb shell settings put secure enabled_accessibility_services com.android.car.customization.tool/com.android.car.customization.tool.CustomizationToolService
```

**Stop the service**

The service can be stopped using KitchenSink or using adb:

```
adb shell settings put secure enabled_accessibility_services null
```
