#!/usr/bin/python

# Copyright (C) 2016 The Android Open Source Project
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
"""Tool to analyze logcat and dmesg logs.

bootanalyze read logcat and dmesg loga and determines key points for boot.
"""

import re
import os
import time
import datetime
import sys
import operator

SOURCE_DMESG = "dmesg"
SOURCE_LOGCAT = "logcat"
SOURCE_BOOTSTAT = "bootstat"

TIME_DMESG = "\[\s*(\d+\.\d+)\]"
TIME_LOGCAT = "^\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d\d\d"

EVENTS = {
  'kernel': [SOURCE_DMESG, '"Linux version"'],
  'Android init 1st stage': [SOURCE_DMESG, '"init first stage started"'],
  'Android init 2st stage': [SOURCE_DMESG, '"init second stage started"'],
  'ueventd': [SOURCE_DMESG, '"Starting service \'ueventd\'"'],
  '/system mounted': [SOURCE_DMESG, '"target=/system"'],
  '/data mounted': [SOURCE_DMESG, '"target=/data"'],
  'servicemanager_start_by_init': [SOURCE_DMESG,
                                   '"Starting service \'servicemanager\'"'],
  'zygote_start_by_init': [SOURCE_DMESG, '"Starting service \'zygote\'"'],
  'zygoteInit': [SOURCE_LOGCAT, '"START com.android.internal.os.ZygoteInit"'],
  'zygote_preload_classes_start': [SOURCE_LOGCAT,
                                   '"Zygote\s*:\s*begin preload"'],
  'zygote_preload_classes_end': [SOURCE_LOGCAT,
                                   '"Zygote\s*:\s*end preload"'],
  'zygote_create_system_server': [SOURCE_LOGCAT,
                                '"Zygote\s*:\s*System server process [0-9]* ' +
                                'has been created"'],
  'vns_start_by_init': [SOURCE_DMESG, '"Starting service \'vns\'"'],
  'SystemServer_start': [SOURCE_LOGCAT,
                    '"Entered the Android system server!"'],
  'system_server_ready': [SOURCE_LOGCAT,
                    '"Enabled StrictMode for system server main"'],
  'PackageManagerInit_start': [SOURCE_LOGCAT,
                    '"StartPackageManagerService"'],
  'PackageManagerInit_ready': [SOURCE_LOGCAT,
                    '"StartOtaDexOptService"'],
  'BluetoothService_start': [SOURCE_LOGCAT,
                       '"Starting com.android.server.BluetoothService"'],
  'SystemUi_start': [SOURCE_LOGCAT,
               '"for service com.android.systemui/."'],
  'LauncherReady': [SOURCE_LOGCAT,
                    '"Em.Overview:\s*onResume"'],
  'CarService_start': [SOURCE_LOGCAT,
                 '"for service com.android.car/.CarService"'],
  'BootComplete': [SOURCE_LOGCAT, '"Starting phase 1000"'],
  'BootComplete Broadcast': [SOURCE_LOGCAT, '"Collecting bootstat data"']
}

def main():
  printInit()
  res = {}
  currentDate, uptime = getDateAndUptime()
  #offset = datetimeToUnixTime(currentDate) - float(uptime)
  offset = getOffset(currentDate)

  for event, pattern in EVENTS.iteritems():
    printProgress()
    timePoint = getEvent(pattern[0], pattern[1], currentDate)
    if timePoint is None:
      continue

    if pattern[0] == SOURCE_LOGCAT:
      timePoint = timePoint - offset

    res[event] = timePoint

  print "Done"

  for item in sorted(res.items(), key=operator.itemgetter(1)):
    print item[0], item[1]

def printInit():
  sys.stdout.write("Getting boot time data for ")
  sys.stdout.write(getVersion())
  sys.stdout.write(".")
  sys.stdout.flush()

def printProgress():
  sys.stdout.write(".")
  sys.stdout.flush()

def getEvent(source, pattern, offsetDate):
  if source == SOURCE_DMESG:
    return getDmesgEvent(pattern)
  elif source == SOURCE_LOGCAT:
    return getLogcatEvent(pattern, offsetDate)
  elif source == SOURCE_BOOTSTAT:
    return getBootStatEvent(pattern)
  return None

def getLogcatEvent(pattern, offsetDate, source=""):
  command = "adb logcat -d " + source + "| grep " + pattern;
  fd = os.popen(command)
  for line in fd:
    found = re.findall(TIME_LOGCAT, line)
    if len(found) > 0:
      ndate = datetime.datetime.strptime(str(offsetDate.year) + '-' +
                                 found[0], '%Y-%m-%d %H:%M:%S.%f')
      return datetimeToUnixTime(ndate)
  return None

def datetimeToUnixTime(ndate):
  return time.mktime(ndate.timetuple()) + ndate.microsecond/1000000.0

def getDmesgEvent(pattern):
  command = "adb shell dmesg | grep " + pattern;
  fd = os.popen(command)
  for line in fd:
    found = re.findall(TIME_DMESG, line)
    if len(found) > 0:
      return float(found[0])
  return None

def getBootStatEvent(pattern):
  command = "adb shell bootstat -p | grep " + pattern;
  fd = os.popen(command)
  for line in fd:
    return float(line.split()[1])
  return None

def getDateAndUptime():
  command = "adb shell \"date +%D-%T;cat /proc/uptime\"";
  fd = os.popen(command)
  lnumber = 1;
  ndate = None
  uptime = None
  for line in fd:
    if lnumber == 1:
      ndate = datetime.datetime.strptime(line.strip(), '%m/%d/%y-%H:%M:%S')
    elif lnumber == 2:
      uptime = line.split()[0]
    lnumber += 1
  return ndate, uptime

def getVersion():
  command = "adb shell getprop ro.build.fingerprint"
  fd = os.popen(command)
  for line in fd:
    return line

def getOffset(currentDate):
  dmesgTime = getDmesgEvent('"Starting service \'thermal-engine\'"')
  logcatTime = getLogcatEvent('"Starting service \'thermal-engine\'"',
                              currentDate, source="-b all")
  return logcatTime - dmesgTime

if __name__ == '__main__':
  main()
