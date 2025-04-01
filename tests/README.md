<!--
  Copyright (C) 2021 The Android Open Source Project

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

# Car Services Tests and Test Apps

This directory contains unit tests, instrumentation tests and sample apps.

## Structure

```
CarHiddenApiTest/            - Car hidden API tests, they use the real services
CarExtendedApiTest/          - Car API instrumentation tests that cannot be tested with CTS, they
                               use the real services
CarLibUnitTest/              - Car API unit tests
CarServiceTest/              - Car service instrumentation tests, mocks VHAL
CarServiceUnitTest/          - Car service unit tests
common_utils/                - Shared utility library

# The following test directories are located relative to $ANDROID_BUILD_TOP
cts/hostsidetests/car/                      - Host-driven CTS tests
cts/tests/tests/car/                        - CTS tests (prefer this over hostsidetests)
frameworks/hardware/interfaces/automotive/  - Contains `vts/` folders for tests
hardware/interfaces/automotive/             - Contains `vts/` folders for tests
test/vts-testcase/hal/automotive/           - Host-side VTS tests
```

## Where to add tests

Add necessary tests to all the test suits, and also don't forget to add ATS/CTS/VTS. See
https://source.android.com/compatibility/tests to learn more about CTS/VTS.

Try not to repeat the same test in multiple suits, as it creates unnecessary test maintenance.

Add tests using these priorities:

1. CTS/VTS
2. `CarSecurityPermissionTest`
3. `CarExtendedApiTest` - if CTS doesn't cover
4. `CarHiddenApiTest` - if APIs are hidden
5. `CarServiceTest`
