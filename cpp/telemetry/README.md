# Automotive Telemetry Service

A structured log collection service for CarTelemetryService. See ARCHITECTURE.md to learn internals.

## Useful Commands

**Dumping the service information**

`adb shell dumpsys android.automotive.telemetry.internal.ICarTelemetryInternal/default`

**Enabling VERBOSE logs**

```
adb shell setprop log.tag.android.automotive.telemetryd@1.0 V
adb shell setprop log.tag.cartelemetryd_impl_test V
```

**Starting emulator with cold boot**

`emulator -verbose -show-kernel -selinux permissive -writable-system -no-snapshot -wipe-data`

**Running the tests**

`atest cartelemetryd_impl_test:CarTelemetryInternalImplTest#TestSetListenerReturnsOk`

`atest cartelemetryd_impl_test`
