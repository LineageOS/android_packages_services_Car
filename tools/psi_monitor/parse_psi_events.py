#!/usr/bin/env python

# Copyright (C) 2024 The Android Open Source Project
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

"""Parses PSI dump from psi_monitor to psi CSV and logcat to events CSV."""
import argparse
import datetime
import os
import pandas as pd
import re
import time

from typing import Any, Dict, List, Tuple

# Sample psi_monitor line:
# 13980	2024-09-06_10:18:38	cpu:some avg10=4.39	avg60=1.29 avg300=0.29 total=1462999 \
# cpu:full avg10=0.00 avg60=0.00 avg300=0.00	total=0	\
# io:some	avg10=3.29 avg60=0.87	avg300=0.19	total=737543 \
# io:full	avg10=2.05 avg60=0.54	avg300=0.12	total=455996 \
# irq:full avg10=0.32 avg60=0.06 avg300=0.01 total=90937 \
# memory:some avg10=0.00 avg60=0.00 avg300=0.00 total=0 \
# memory:full	avg10=0.00 avg60=0.00 avg300=0.00	total=0

TIMESTAMP_PATTERN = (r'(?P<uptime_millis>\d+)'
                     # Drop the nanoseconds part of the timestamp because the python datetime
                     # library supports parsing only up to microseconds.
                     r'\s+(?P<local_datetime>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}.\d{6})\d{3}')
CPU_SOME_PATTERN= (r'cpu:some\s+avg10=(?P<cpu_some_avg10>\d+\.?\d+)\s+'
                   r'avg60=(?P<cpu_some_avg60>\d+\.?\d+)\s+avg300=(?P<cpu_some_avg300>\d+\.?\d+)'
                   r'\s+total=(?P<cpu_some_total>\d+)')
CPU_FULL_PATTERN = (r'cpu:full\s+avg10=(?P<cpu_full_avg10>\d+\.?\d+)\s+'
                    r'avg60=(?P<cpu_full_avg60>\d+\.?\d+)\s+avg300=(?P<cpu_full_avg300>\d+\.?\d+)'
                    r'\s+total=(?P<cpu_full_total>\d+)')
IO_SOME_PATTERN = (r'io:some\s+avg10=(?P<io_some_avg10>\d+\.?\d+)\s+'
                   r'avg60=(?P<io_some_avg60>\d+\.?\d+)\s+avg300=(?P<io_some_avg300>\d+\.?\d+)'
                   r'\s+total=(?P<io_some_total>\d+)')
IO_FULL_PATTERN = (r'io:full\s+avg10=(?P<io_full_avg10>\d+\.?\d+)\s+'
                   r'avg60=(?P<io_full_avg60>\d+\.?\d+)\s+avg300=(?P<io_full_avg300>\d+\.?\d+)'
                   r'\s+total=(?P<io_full_total>\d+)')
IRQ_FULL_PATTERN = (r'irq:full\s+avg10=(?P<irq_full_avg10>\d+\.?\d+)\s+'
                   r'avg60=(?P<irq_full_avg60>\d+\.?\d+)\s+avg300=(?P<irq_full_avg300>\d+\.?\d+)'
                   r'\s+total=(?P<irq_full_total>\d+)')
MEMORY_SOME_PATTERN = (r'memory:some\s+avg10=(?P<memory_some_avg10>\d+\.?\d+)\s+'
                       r'avg60=(?P<memory_some_avg60>\d+\.?\d+)\s+'
                       r'avg300=(?P<memory_some_avg300>\d+\.?\d+)\s+'
                       r'total=(?P<memory_some_total>\d+)')
MEMORY_FULL_PATTERN = (r'memory:full\s+avg10=(?P<memory_full_avg10>\d+\.?\d+)\s+'
                       r'avg60=(?P<memory_full_avg60>\d+\.?\d+)\s+'
                       r'avg300=(?P<memory_full_avg300>\d+\.?\d+)\s+'
                       r'total=(?P<memory_full_total>\d+)')
EVENT_DESCRIPTION_PATTERN = (r'(?:\s+\"(?P<event_descriptions>.*)\")?')
PSI_LINE_RE = re.compile(r'^' + TIMESTAMP_PATTERN + r'\s+' + CPU_SOME_PATTERN
                         # CPU full is optional because it is not always reported by the Kernel.
                         + r'(?:\s+' + CPU_FULL_PATTERN + r')?\s+'
                         + IO_SOME_PATTERN + r'\s+' + IO_FULL_PATTERN
                         # IRQ full is optional because it is not always reported by the Kernel.
                         + r'(?:\s+' + IRQ_FULL_PATTERN + r')?\s+'
                         + MEMORY_SOME_PATTERN + r'\s+' + MEMORY_FULL_PATTERN
                         + EVENT_DESCRIPTION_PATTERN + '$')
PSI_OUT_FIELDS = ['uptime_millis', 'local_datetime', 'epoch_millis',
                  'monitor_start_relative_millis', 'cpu_some_avg10', 'cpu_some_avg60',
                  'cpu_some_avg300', 'cpu_some_total', 'cpu_full_avg10', 'cpu_full_avg60',
                  'cpu_full_avg300', 'cpu_full_total', 'io_some_avg10', 'io_some_avg60',
                  'io_some_avg300', 'io_some_total', 'io_full_avg10', 'io_full_avg60',
                  'io_full_avg300', 'io_full_total', 'irq_full_avg10', 'irq_full_avg60',
                  'irq_full_avg300', 'irq_full_total', 'memory_some_avg10', 'memory_some_avg60',
                  'memory_some_avg300', 'memory_some_total', 'memory_full_avg10',
                  'memory_full_avg60', 'memory_full_avg300', 'memory_full_total',
                  'event_descriptions']

# Supported logcat lines:
# 1. 2024-09-26 09:19:56.140  1627  1805 I ActivityTaskManager: START u0
#    {act=android.intent.action.MAIN cmp=com.android.car.cluster.home/.ClusterHomeActivityLightMode}
#    with LAUNCH_SINGLE_TASK from uid 1000 (BAL_ALLOW_ALLOWLISTED_UID) result code=0
# 2. 2024-09-26 09:19:56.675  1627  1811 I ActivityTaskManager: Displayed
#    com.android.car.cluster.home/.ClusterHomeActivityLightMode for user 0: +262ms
# 3. 2024-09-26 09:19:56.097  1627  1822 D SystemServerTimingAsync: ssm.UnlockingUser-0
# 4. 2024-09-26 09:19:56.144  1627  1822 V SystemServerTimingAsync: ssm.UnlockingUser-0 took to
#    complete: 47ms
LOGCAT_TIMESTAMP_PATTERN = r'(?P<local_datetime>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}.\d{3})'
PID_TID_LOGLEVEL_PATTERN = r'(?P<pid>\d+)\s+(?P<tid>\d+)\s+(?P<loglevel>[VDIWEFS])'
ACTIVITY_TASK_MANAGER_TAG = 'ActivityTaskManager'
ACTIVITY_TASK_MANAGER_START_PATTERN = (ACTIVITY_TASK_MANAGER_TAG + r':\s+START\s+u(?P<user_id>\d+)'
                                       r'\s+\{.*cmp=(?P<activity_name>[0-9A-Za-z\.\/]+)(:?\s+|\})'
                                       r'.*')
ACTIVITY_TASK_MANAGER_DISPLAYED_PATTERN = (ACTIVITY_TASK_MANAGER_TAG + r':\s+Displayed\s+'
                                           r'(?P<activity_name>.*)\s+for\s+user\s+'
                                           r'(?P<user_id>\d+):\s+\+(:?(?P<duration_secs>\d+)s)?'
                                           r'(?P<duration_millis>\d+)ms')
SYSTEM_SERVER_TIMING_ASYNC_TAG = 'SystemServerTimingAsync'
SYSTEM_SERVER_TIMING_ASYNC_PATTERN = (SYSTEM_SERVER_TIMING_ASYNC_TAG
                                      + r': (?P<event_description>.*)')
SYSTEM_SERVER_TIMING_ASYNC_COMPLETE_PATTERN = (SYSTEM_SERVER_TIMING_ASYNC_TAG
                                               + r': (?P<event_description>.*)'
                                               r'\s+took to complete:\s+(?P<duration_millis>\d+)ms')

ACTIVITY_TASK_MANAGER_START_LINE_RE = re.compile(r'^' + LOGCAT_TIMESTAMP_PATTERN + r'\s+'
                                                     + PID_TID_LOGLEVEL_PATTERN + r'\s+'
                                                     + ACTIVITY_TASK_MANAGER_START_PATTERN
                                                     + '$')
ACTIVITY_TASK_MANAGER_DISPLAYED_LINE_RE = re.compile(r'^' + LOGCAT_TIMESTAMP_PATTERN + r'\s+'
                                                     + PID_TID_LOGLEVEL_PATTERN + r'\s+'
                                                     + ACTIVITY_TASK_MANAGER_DISPLAYED_PATTERN
                                                     + '$')
SYSTEM_SERVER_TIMING_ASYNC_LINE_RE = re.compile(r'^' + LOGCAT_TIMESTAMP_PATTERN + r'\s+'
                                                + PID_TID_LOGLEVEL_PATTERN + r'\s+'
                                                + SYSTEM_SERVER_TIMING_ASYNC_PATTERN
                                                + '$')
SYSTEM_SERVER_TIMING_ASYNC_COMPLETE_LINE_RE = re.compile(r'^' + LOGCAT_TIMESTAMP_PATTERN + r'\s+'
                                                + PID_TID_LOGLEVEL_PATTERN + r'\s+'
                                                + SYSTEM_SERVER_TIMING_ASYNC_COMPLETE_PATTERN
                                                + '$')
ACTIVITY_DISPLAYED_DURATION_THRESHOLD_MILLIS = 1000
SS_EVENT_MIN_DURATION_TO_PLOT_MILLIS = 50
SS_EVENT_DURATION_THRESHOLD_MILLIS = 250
EVENTS_OUT_FIELDS = ['epoch_millis', 'monitor_start_relative_millis',
                     # event_tag is the logcat tag reported in the logcat. This can be used to
                     # filter events with the same tag.
                     # event_description contains the timing information for the event, which
                     # can be used when plotting the events in charts.
                     # event_key contains a unique key for the event, which can be used to
                     # gather statistics of events across multiple runs of a CUJ.
                     'event_tag', 'event_description', 'event_key', 'duration_value',
                     'is_high_latency_event', 'color', 'should_plot']
MIN_DURATION_MILLIS_DELTA_BEFORE_MONITOR_START = -600000 # 10 minutes


def parse_psi_line(line) -> Dict[str, str]:
  m = PSI_LINE_RE.match(line)
  assert m, 'Failed to parse PSI line: {}\n\nFormat: {}'.format(line, PSI_LINE_RE.pattern)
  return m.groupdict()


def on_psi_field_missing(field, line) -> str:
  if field.endswith('_avg10') or field.endswith('_avg60') or field.endswith('_avg300'):
    return str(0.0)
  elif field.endswith('_total'):
    return str(0)
  elif field == 'event_descriptions':
    return list()
  else:
    raise ValueError('Unknown field: {} in line: {}'.format(field, line))


def get_epoch_milliseconds(date_string, format='%Y-%m-%d %H:%M:%S.%f'):
  try:
    datetime_obj = datetime.datetime.strptime(date_string, format)
    return int((time.mktime(datetime_obj.timetuple()) * 1000) + datetime_obj.microsecond / 1000)
  except ValueError as e:
    print("Error parsing date '{}': {}".format(date_string, e))
    return 0


def parse_psi_dump(psi_dump_file) -> Tuple[pd.DataFrame, int]:
  with open(psi_dump_file, 'r') as f:
    dump = f.readlines()
    for i in range(len(dump)):
      dump[i] = dump[i].strip()
    monitor_start_uptime_millis = None
    monitor_start_epoch_millis = None
    psi_df = pd.DataFrame(columns=PSI_OUT_FIELDS)
    for line in dump:
      psi_dict = parse_psi_line(line)
      psi_dict['epoch_millis'] = str(get_epoch_milliseconds(psi_dict['local_datetime']))
      if not monitor_start_uptime_millis:
        monitor_start_uptime_millis = int(psi_dict['uptime_millis'])
        monitor_start_epoch_millis = int(psi_dict['epoch_millis'])
        psi_dict['monitor_start_relative_millis'] = str(0)
      else:
        psi_dict['monitor_start_relative_millis'] = str(
            int(psi_dict['uptime_millis']) - monitor_start_uptime_millis)
      if psi_dict['event_descriptions']:
        # Handle the case where multiple events are reported on the same line
        if '" "' in psi_dict['event_descriptions']:
          psi_dict['event_descriptions'] = psi_dict['event_descriptions'].replace(
            "\" \"", ",").split(",")
        else:
          psi_dict['event_descriptions'] = [psi_dict['event_descriptions']]
      out_line = [psi_dict[field] if field in psi_dict and psi_dict[field]
                  else on_psi_field_missing(field, line) for field in PSI_OUT_FIELDS]
      psi_df.loc[len(psi_df)] = out_line
  return psi_df, monitor_start_epoch_millis


class LogcatParser:
  def __init__(self, logcat_file, monitor_start_epoch_millis):
    self._event_counts_dict = {}
    self._logcat_file = logcat_file
    self._monitor_start_epoch_millis = monitor_start_epoch_millis


  def _get_list(self, epoch_millis, event_tag, event_description, color, event_key=None,
                duration_millis=None, is_high_latency_event=False, should_plot=True):
    if event_key:
      if event_key in self._event_counts_dict:
        self._event_counts_dict[event_key] += 1
        event_key += ': Occurrence {}'.format(self._event_counts_dict[event_key])
      else:
        self._event_counts_dict[event_key] = 1
    monitor_start_relative_millis = int(epoch_millis - self._monitor_start_epoch_millis)
    # Some logcat entries will have incorrect timestamps. Discard such entries to avoid downstream
    # issues.
    if monitor_start_relative_millis < MIN_DURATION_MILLIS_DELTA_BEFORE_MONITOR_START:
      return None
    return [int(epoch_millis), monitor_start_relative_millis, event_tag,
            event_description, event_key, duration_millis, is_high_latency_event, color,
            should_plot]


  def _parse_line(self, line) -> List[Any]:
    m = ACTIVITY_TASK_MANAGER_START_LINE_RE.match(line)
    if m:
      matched = m.groupdict()
      return self._get_list(get_epoch_milliseconds(matched['local_datetime']),
        ACTIVITY_TASK_MANAGER_TAG,
        ('Started ' + matched['activity_name'] + ' for user ' + matched['user_id']), 'lime')
    m = ACTIVITY_TASK_MANAGER_DISPLAYED_LINE_RE.match(line)
    if m:
      matched = m.groupdict()
      if 'duration_secs' in matched and matched['duration_secs']:
        duration_millis = (int(matched['duration_secs']) * 1000) + int(matched['duration_millis'])
      else:
        duration_millis = int(matched['duration_millis'])
      event_key = 'Displayed ' + matched['activity_name'] + ' for user ' + matched['user_id']
      return self._get_list(get_epoch_milliseconds(matched['local_datetime']),
        ACTIVITY_TASK_MANAGER_TAG, (event_key + ' took ' + str(duration_millis) + 'ms'),
        'orangered' if duration_millis >= ACTIVITY_DISPLAYED_DURATION_THRESHOLD_MILLIS else 'green',
        event_key, duration_millis,
        is_high_latency_event=(duration_millis >= ACTIVITY_DISPLAYED_DURATION_THRESHOLD_MILLIS))
    m = SYSTEM_SERVER_TIMING_ASYNC_COMPLETE_LINE_RE.match(line)
    if m:
      matched = m.groupdict()
      duration_millis = int(matched['duration_millis'])
      event_key = 'SystemServer event ' + matched['event_description']
      return self._get_list(get_epoch_milliseconds(matched['local_datetime']),
        SYSTEM_SERVER_TIMING_ASYNC_TAG, (event_key + ' took ' + str(duration_millis) + 'ms'),
        'red' if duration_millis >= SS_EVENT_DURATION_THRESHOLD_MILLIS else 'saddlebrown',
        event_key, duration_millis,
        is_high_latency_event=duration_millis >= SS_EVENT_DURATION_THRESHOLD_MILLIS,
        should_plot=duration_millis >= SS_EVENT_MIN_DURATION_TO_PLOT_MILLIS)
    m = SYSTEM_SERVER_TIMING_ASYNC_LINE_RE.match(line)
    if m:
      matched = m.groupdict()
      return self._get_list(get_epoch_milliseconds(matched['local_datetime']),
        SYSTEM_SERVER_TIMING_ASYNC_TAG, ('SystemServer event ' + matched['event_description']),
        'yellow')
    return None


  def get_events_df(self) -> pd.DataFrame:
    events_df = pd.DataFrame(columns=EVENTS_OUT_FIELDS)
    with open(self._logcat_file, 'r') as f:
      logcat = f.readlines()
      for line in logcat:
        row = self._parse_line(line.strip())
        if row:
          events_df.loc[len(events_df)] = row
      return events_df


def main():
  parser = argparse.ArgumentParser(description='Parse PSI dump from psi_monitor.')
  parser.add_argument('--psi_dump', action='store', type=str, required=True, dest='psi_dump',
                      help='PSI dump from psi_monitor.sh')
  parser.add_argument('--logcat', action='store', type=str, required=False, dest='logcat',
                      help='Logcat from run_cuj.sh')
  parser.add_argument('--psi_csv', action='store', type=str, required=True, dest='psi_csv',
                      help='Output CSV file')
  parser.add_argument('--events_csv', action='store', type=str, dest='events_csv',
                      help='Output events CSV file. Generated only when logcat is provided.')
  args = parser.parse_args()
  print('Converting PSI dump {} to CSV {}'.format(args.psi_dump, args.psi_csv))
  psi_df, monitor_start_epoch_millis = parse_psi_dump(args.psi_dump)
  psi_df.to_csv(args.psi_csv, index=False)

  if args.logcat:
    events_csv = args.events_csv if args.events_csv else (os.path.dirname(args.psi_csv)
                                                          + '/events.csv')
    print('Parsing events from logcat {} to CSV {}'.format(args.logcat, events_csv))
    logcat_parser = LogcatParser(args.logcat, monitor_start_epoch_millis)
    events_df = logcat_parser.get_events_df()
    events_df.to_csv(events_csv, index=False)

if __name__ == '__main__':
  main()
