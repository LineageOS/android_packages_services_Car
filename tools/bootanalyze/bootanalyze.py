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

import yaml
import argparse
import re
import os
import subprocess
import time
import datetime
import sys
import operator

TIME_DMESG = "\[\s*(\d+\.\d+)\]"
TIME_LOGCAT = "^\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d\d\d"

def main():
  args = init_arguments()

  if args.reboot:
    reboot()

  cfg = yaml.load(args.config)

  search_events = {key: re.compile(pattern)
                   for key, pattern in cfg['events'].iteritems()}

  if 'stop_event' not in cfg:
    raise Exception('Logcat stop_event is missing in config');

  logcat_events = collect_events(
    search_events, 'adb logcat -b all', cfg['stop_event'])

  dmesg_events = collect_events(search_events, 'adb shell dmesg')

  logcat_event_time = extract_time(
    logcat_events, TIME_LOGCAT, logcat_time_func(2016));
  logcat_original_time = extract_time(
    logcat_events, TIME_LOGCAT, str);
  dmesg_event_time = extract_time(
    dmesg_events, TIME_DMESG, float);

  events = {}
  diff_time = 0
  max_time = 0
  events_to_correct = []
  replaced_from_dmesg = set()
  for k, v in logcat_event_time.iteritems():
    events[k] = v
    if k in dmesg_event_time:
      if dmesg_event_time[k] > max_time:
        max_time = dmesg_event_time[k]
        diff_time = v - max_time
      events[k] = dmesg_event_time[k]
      replaced_from_dmesg.add(k)
    else:
      events_to_correct.append(k)

  for k in events_to_correct:
    if events[k] - diff_time > 0:
      events[k] = events[k] - diff_time

  for item in sorted(events.items(), key=operator.itemgetter(1)):
    print '{0:30}: {1:<7.5} {2:1} ({3})'.format(
      item[0], item[1], '*' if item[0] in replaced_from_dmesg else '',
      logcat_original_time[item[0]])

  print '\n', '* - event time was obtained from dmesg log'

def init_arguments():
  parser = argparse.ArgumentParser(description='Measures boot time.')
  parser.add_argument('-r', '--reboot', dest='reboot',
                      action='store_true',
                      help='adb reboot device for measurement', )
  parser.add_argument('-c', '--config', dest='config',
                      default='config.yaml', type=argparse.FileType('r'),
                      help='config file for the tool', )
  return parser.parse_args()

def collect_events(search_events, command, stop_event=None):
  events = {}
  process = subprocess.Popen(command, shell=True,
                             stdout=subprocess.PIPE);
  out = process.stdout
  data_available = stop_event is None
  for line in out:
    if not data_available:
      print "Collecting data samples. Please wait...\n"
      data_available = True
    event = get_boot_event(line, search_events);
    if event:
      events[event] = line
    if stop_event and stop_event in line:
      break;
  process.terminate()
  return events

def get_boot_event(line, events):
  for event_key, event_pattern in events.iteritems():
    if event_pattern.search(line):
      return event_key
  return None

def extract_time(events, pattern, date_transform_function):
  result = {}
  for event, data in events.iteritems():
    found = re.findall(pattern, data)
    if len(found) > 0:
      result[event] = date_transform_function(found[0])
    else:
      print "Failed to find time for event: ", event, data
  return result

def reboot():
  print 'Rebooting the device'
  subprocess.Popen('adb reboot', shell=True).wait()

def logcat_time_func(offset_year):
  def f(date_str):
    ndate = datetime.datetime.strptime(str(offset_year) + '-' +
                                 date_str, '%Y-%m-%d %H:%M:%S.%f')
    return datetime_to_unix_time(ndate)
  return f


def datetime_to_unix_time(ndate):
  return time.mktime(ndate.timetuple()) + ndate.microsecond/1000000.0

if __name__ == '__main__':
  main()
