# Fake VHAL mode in Car Service

### How to enable fake VHAL mode.

**Note**: Fake VHAL can only be enabled in eng or userdebug build.

#### Push ENABLE file and optional custom config files to device

1.  Get read-write permission on device

    ```
    $ adb root
    $ adb remount
    $ adb reboot
    $ adb wait-for-device # wait until the device is connected.
    $ adb root
    $ adb remount
    ```

2.  Push ENABLE file and optional custom config files.

    Option A. Use the default config file only. Push an empty ENABLE file to device.

    ```
    $ adb push <path-to-file>/ENABLE /data/system/car/fake-vhal-config/ENABLE
    ```

    Option B. Add custom config file names to ENABLE file. Push both ENABLE file and custom config files to device.

    ```
    $ echo <custom-config-file-name> > ENABLE
    $ adb push <path-to-file>/ENABLE /data/system/car/fake-vhal-config/ENABLE
    $ adb push <path-to-file>/<custom-config-file-name> \
      /data/system/car/fake-vhal-config/<custom-config-file-name>
    ```

#### Activate fake VHAL mode.

1.  Reboot device

    ```
    $ adb shell stop && adb shell start
    $ adb wait-for-device # wait until the device is connected.
    ```

2.  Check if Car Service is connecting to fake VHAL.

    ```
    $ adb shell cmd car_service check-fake-vhal
    Car Service connects to FakeVehicleStub: true
    ```

3.  Delete ENABLE file from device and restart service to disable fake VHAL.

    ```
    $ adb shell
    $ cd data/system/car/fake-vhal-config
    $ rm ENABLE
    $ exit
    $ adb shell stop && adb shell start
    ```
