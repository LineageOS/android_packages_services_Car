# SampleRearViewService

This reference application demonstrates how to implement a rear-view camera service in Android Automotive OS using the Camera2 API.

**Note:** This is the modern, Camera2-based implementation of a rear-view camera. For the legacy, EVS-based implementation, see `tests/SampleRearViewCamera`.

It monitors the vehicle gear state and displays a camera feed overlay when the vehicle is in reverse.

## Project Structure

*   `src/.../SampleRearViewService.java`: The background service that monitors the `GEAR_SELECTION` property using `CarPropertyManager`. It launches the rear-view activity when the gear is shifted to reverse and stops it when shifted out of reverse.
*   `src/.../SampleRearViewActivity.java`: The activity responsible for displaying the camera preview. It is configured as a system overlay (`TYPE_VOLUME_OVERLAY`) to ensure it appears on top of other applications, including the lock screen.
*   `AndroidManifest.xml`: Declares the application components and necessary permissions, such as `INTERNAL_SYSTEM_WINDOW` for the overlay and `CAMERA` for camera access.

## Prerequisites

For the SampleRearViewService to function correctly, the following prerequisites must be met:

### 1. Camera2 HAL Configuration

The device must have a Camera2 HAL (Hardware Abstraction Layer) that is
configured to expose a rear-view camera. The camera characteristics must
include:

- `AUTOMOTIVE_LENS_FACING`: `AUTOMOTIVE_LENS_FACING_REAR`
- `AUTOMOTIVE_LOCATION`: `AUTOMOTIVE_LOCATION_EXTERIOR_REAR`

The service will not be able to find the camera and will not function if these
characteristics are not present.

### 2. RRO Overlay for Early Startup

The SampleRearViewService needs to be started early in the boot process. To
achieve this, the SampleRearViewService is included in the `config_earlyStartupServices`
string-array within an RRO (Runtime ResourceOverlay) configuration. OEMs can include their own services in this array.

Typically, this file is located at:
`vendor/auto/embedded/products/rro_overlay/CarServiceOverlay/res/values/config.xml`

The following item is included in the `config_earlyStartupServices` array:

```xml
<item> com.google.android.car.samplerearviewservice/.SampleRearViewService#bind=start,user=system,trigger=asap</item>
```

This configuration ensures that the service is started as soon as possible for the
system user.

## Testing

You can simulate gear changes using `adb` to trigger the rear-view camera service.

1.  **Start the Service** (if not already started by boot configuration):
    ```bash
    adb shell am start-service com.google.android.car.samplerearviewservice/.SampleRearViewService
    ```

2.  **Inject Reverse Gear Event**:
    This mimics shifting the car into reverse, which should trigger the camera overlay.
    ```bash
    # Property ID: 289408000 (GEAR_SELECTION)
    # Value: 2 (GEAR_REVERSE)
    adb shell cmd car_service inject-vhal-event 289408000 2
    ```

3.  **Inject Drive/Park Event**:
    This mimics shifting out of reverse, which should close the camera overlay.
    ```bash
    # Value: 4 (GEAR_PARK)
    adb shell cmd car_service inject-vhal-event 289408000 4
    ```

## Guidelines for Implementing a Rear-View Camera Activity

This sample demonstrates a way to implement a rear-view camera feed that
acts as a system overlay. The key implementation details are outlined below.

### 1. Displaying as a System Overlay

To ensure the camera feed appears reliably on top of all other applications, including
the lock screen, the Activity should be added directly to the `WindowManager`
instead of using `setContentView()`.

- **Window Type**: Use a high-level window type like `WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY`.
  This ensures the view is displayed above most other UI elements. This requires the
  `INTERNAL_SYSTEM_WINDOW` permission.
- **Window Flags**:
    - `FLAG_DIM_BEHIND`: Dims the background behind this window.
    - `FLAG_NOT_TOUCH_MODAL`: Allows touches outside the window to be sent to the windows behind it.
- **Activity Flag**: Use `setShowWhenLocked(true)` in `onCreate()` to allow the
  activity to be displayed on top of the lockscreen.

### 2. Identifying the Rear-View Camera

A rear-view camera should be identified by the following automotive characteristics:

- Use `CameraCharacteristics.AUTOMOTIVE_LENS_FACING` and check for the value
  `AUTOMOTIVE_LENS_FACING_EXTERIOR_REAR`.
- Additionally, verify the camera's physical location with
  `CameraCharacteristics.AUTOMOTIVE_LOCATION`, checking for the value
  `AUTOMOTIVE_LOCATION_EXTERIOR_REAR`.

### 3. Handling the Activity and Service Lifecycle

- The `Service` is responsible for monitoring the vehicle's gear state using the
  `CarPropertyManager`.
- When the gear is shifted to `VehicleGear.GEAR_REVERSE`, the service launches the
  camera `Activity`. The `Intent` should include `FLAG_ACTIVITY_NEW_TASK` and
  `FLAG_ACTIVITY_NO_ANIMATION`.
- The `Activity` should be launched for the system user (`UserHandle.SYSTEM`) to
  ensure it runs with the correct permissions and context.
- When the gear is shifted out of reverse, the service broadcasts a custom "stop"
  intent (e.g., `ACTION_STOP_REARVIEW`).
- The `Activity` registers a `BroadcastReceiver` to listen for this intent and calls
  `finish()` on itself when the intent is received.

### 4. Robust Error Handling

- **Camera Service Initialization**: The Android Camera Service may not be ready
  immediately at boot. If `CameraManager.getCameraIdList()` returns an empty list,
  it's a good practice to briefly delay and retry.
- **Camera Disconnection**: If the camera disconnects during operation (e.g., in
  the `onDisconnected` or `onError` callbacks), a robust recovery mechanism is
  to attempt to re-establish the connection.

### 5. Further Reading

For more detailed official guidelines and best practices on implementing camera
solutions in Android Automotive OS, refer to the Android documentation:

*   **Camera2 Migration and Usage in AAOS**: [https://source.android.com/docs/automotive/camera/acs/camera2-migration#camera2_4](https://source.android.com/docs/automotive/camera/acs/camera2-migration#camera2_4)
