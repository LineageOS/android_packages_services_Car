#!/usr/bin/env python
#
# Copyright (C) 2017 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# A tool that can read diagnostic events from a Diagnostic JSON document
# and forward them to Vehicle HAL via vhal_emulator
# Use thusly:
# $ ./diagnostic_injector.py <path/to/diagnostic.json>

import sys
import json
import time

import vhal_consts_2_0 as c

from vhal_emulator import Vhal
from diagnostic_builder import DiagnosticEventBuilder

# these define the mapping between OBD2 sensors and VHAL indices
# TODO(egranata): can we autogenerate this?
intSensorsMapping = {
    0x03 : lambda builder,value: builder.addIntSensor(0, value),
    0x05 : lambda builder,value: builder.addFloatSensor(1, value),
    0x0A : lambda builder,value: builder.addIntSensor(22, value),
    0x0C : lambda builder,value: builder.addFloatSensor(8, value),
    0x0D : lambda builder,value: builder.addFloatSensor(9, value),
    0x1F : lambda builder,value: builder.addIntSensor(7, value),
    0x5C : lambda builder,value: builder.addIntSensor(23, value),
}

floatSensorsMapping = {
    0x04 : lambda builder, value: builder.addFloatSensor(0, value),
    0x06 : lambda builder, value: builder.addFloatSensor(2, value),
    0x07 : lambda builder, value: builder.addFloatSensor(3, value),
    0x08 : lambda builder, value: builder.addFloatSensor(4, value),
    0x09 : lambda builder, value: builder.addFloatSensor(5, value),
    0x11 : lambda builder, value: builder.addFloatSensor(12, value),
    0x2F : lambda builder, value: builder.addFloatSensor(42, value),
    0x46 : lambda builder, value: builder.addIntSensor(13, int(value)),
}

class DiagnosticHalWrapper(object):
    def __init__(self):
        self.vhal = Vhal(c.vhal_types_2_0)
        self.liveFrameConfig = self.chat(
            lambda hal: hal.getConfig(c.VEHICLE_PROPERTY_OBD2_LIVE_FRAME))
        self.eventTypeData = {
            1 : {
                'builder'  : lambda: DiagnosticEventBuilder(self.liveFrameConfig),
                'property' :  c.VEHICLE_PROPERTY_OBD2_LIVE_FRAME
            },
        }

    def chat(self, request):
        request(self.vhal)
        return self.vhal.rxMsg()

    def inject(self, file):
        data = json.load(open(file))
        lastTimestamp = 0
        for event in data:
            currentTimestamp = event['timestamp']
            # wait the delta between this event and the previous one
            # before sending it out; but on the first event, send now
            # or we'd wait for a long long long time
            if lastTimestamp != 0:
                # also, timestamps are in nanoseconds, but sleep() uses seconds
                time.sleep((currentTimestamp-lastTimestamp)/1000000000)
            lastTimestamp = currentTimestamp
            print ("Sending event at %d" % currentTimestamp),
            # now build the event
            eventTypeData = self.eventTypeData[event['type']]
            builder = eventTypeData['builder']()
            builder.setStringValue(event.get('stringValue', ''))
            for intValue in event['intValues']:
                intSensorsMapping[intValue['id']](builder, intValue['value'])
            for floatValue in event['floatValues']:
                floatSensorsMapping[floatValue['id']](builder, floatValue['value'])
            builtEvent = builder.build()
            # and send it
            print self.chat(
                lambda hal:
                    hal.setProperty(eventTypeData['property'],
                        0,
                        builtEvent))

if len(sys.argv) < 2:
    print("Syntax: diagnostic_injector.py <path/to/diagnostic.json>")
    sys.exit(1)

halWrapper = DiagnosticHalWrapper()

for arg in sys.argv[1:]:
    halWrapper.inject(arg)
