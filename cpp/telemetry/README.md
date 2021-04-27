# Automotive Telemetry Service

A structured log collection service for CarTelemetryService. See ARCHITECTURE.md to learn internals.

## Useful Commands

**Dump service information**

`adb shell dumpsys android.automotive.telemetry.internal.ICarTelemetryInternal/default`

**Starting emulator**

`aae emulator run -selinux permissive -writable-system`

**Running tests**

`atest cartelemetryd_impl_test:CarTelemetryInternalImplTest#TestSetListenerReturnsOk`

`atest cartelemetryd_impl_test`
