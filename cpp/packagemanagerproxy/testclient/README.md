<!--
  Copyright (C) 2024 The Android Open Source Project

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License
  -->

# PackageManagerProxyd Test Client

This client can be used to test the IPackageManagerProxy interface from the `/vendor` partition. To do so, build and start the VM:

```
lunch sdv_ivi_cf-trunk_staging-userdebug
m
cvd start
```

After the VM is started, launch a vendor shell:

```
abd shell /vendor/bin/sh
```

You will not get a shell prompt, so on the blank line that appears run the following command:

```
/vendor/bin/packagemanagerproxyd_testclient <package name>
```

`<package name>` should be replaced with a valid package name, for example:

```
/vendor/bin/packagemanagerproxyd_testclient com.google.android.car.evs
```


You should receive output similar to the following (the Uid and Version Code may diff from the example given):

```
Fetching package info for "com.google.android.car.evs"
Uid: 10122
Version Code: 35
```

If the output is incomplete, check logcat for selinux denials.
