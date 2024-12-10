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

# Car-lib Internal

Source code for the internal libraries for car-lib implementation.

## Coding Convention

Public methods in car-lib internal are not surfaced to APIs through
`packages/services/Car/car-lib/src/com/android/car/internal/package-info.java`.

We don't encourage you to add @hide annotation to make them a hidden API.

