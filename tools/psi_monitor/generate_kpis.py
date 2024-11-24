#!/usr/bin/env python

import argparse
import pandas as pd
import re

COLUMNS=('Event', 'Type', 'Tag', 'Value', 'Unit')
PSI_TO_MONITOR='cpu_some_avg10'
CUJ_COMPLETED_EVENT = 'CUJ completed'
PSI_EXCEEDED_THRESHOLD_RE = re.compile(r'^PSI exceeded threshold: (?P<psi_value>\d+)%'
                                       + r'\s+(?P<psi_type>.*)$')
PSI_DROPPED_BELOW_THRESHOLD_RE = re.compile(r'^PSI dropped below threshold: (?P<psi_value>\d+)%'
                                            + r'\s+(?P<psi_type>.*)$')
PSI_REACHED_BASELINE_RE = re.compile(r'^PSI reached baseline across latest'
                                     r'\s+(?P<latest_psi_entries>\d+)\s+entries$')
PSI_MONITOR_TAG = 'psi_monitor'
PSI_EVENT_TYPE = 'psi_event'
LOGCAT_EVENT_TYPE = 'logcat_event'
LOGCAT_EVENT_UNIT = 'Duration millis'


class PsiKpi:
  def __init__(self, psi_to_monitor):
    self._cuj_completed_millis = None
    self._exceeded_threshold_millis = None
    self._exceeded_threshold_value = None
    self._exceeded_threshold_type = None
    self._dropped_below_threshold_millis = None
    self._dropped_below_threshold_value = None
    self._dropped_below_threshold_type = None
    self._reached_baseline_millis = None
    self._reached_baseline_latest_psi_entries = None
    self._max_psi_value = 0.0
    self._max_psi_value_after_dropped_below_threshold = 0.0
    self._psi_to_monitor = psi_to_monitor
    self._total_duration_above_80p_threshold_millis = 0.0
    self._total_duration_above_50p_threshold_millis = 0.0
    self._total_duration_above_30p_threshold_millis = 0.0


  def _do_match_exceeded_threshold_millis(self, monitor_start_relative_millis, event_description):
    m = PSI_EXCEEDED_THRESHOLD_RE.match(event_description)
    if not m:
      return False
    self._exceeded_threshold_millis = monitor_start_relative_millis
    matched = m.groupdict()
    self._exceeded_threshold_value = matched['psi_value']
    self._exceeded_threshold_type = matched['psi_type']
    return True


  def _do_match_dropped_below_threshold_millis(self, monitor_start_relative_millis,
                                               event_description):
    m = PSI_DROPPED_BELOW_THRESHOLD_RE.match(event_description)
    if not m:
      return False
    self._dropped_below_threshold_millis = monitor_start_relative_millis
    matched = m.groupdict()
    self._dropped_below_threshold_value = matched['psi_value']
    self._dropped_below_threshold_type = matched['psi_type']
    return True


  def _check_valid(self):
    if self._cuj_completed_millis is None:
      raise ValueError('CUJ completed event not found')
    if self._exceeded_threshold_millis is None:
      raise ValueError('PSI exceeded threshold event not found')
    if self._dropped_below_threshold_millis is None:
      raise ValueError('PSI dropped below threshold event not found')
    if self._reached_baseline_millis is None:
      raise ValueError('PSI reached baseline event not found')
    if self._dropped_below_threshold_type != self._exceeded_threshold_type:
      raise ValueError('PSI threshold type mismatch: {} != {}'.format(
          self._dropped_below_threshold_type, self._exceeded_threshold_type))
    if self._dropped_below_threshold_value != self._exceeded_threshold_value:
      raise ValueError('PSI threshold value mismatch: {} != {}'.format(
          self._dropped_below_threshold_value, self._exceeded_threshold_value))
    if self._exceeded_threshold_millis >= self._dropped_below_threshold_millis:
      raise ValueError(
        'PSI exceeded threshold millis ({}) >= dropped below threshold millis ({})'.format(
          self._exceeded_threshold_millis, self._dropped_below_threshold_millis))
    if self._cuj_completed_millis > self._exceeded_threshold_millis:
      raise ValueError('CUJ completed millis ({}) > exceeded threshold millis ({})'
                      .format(self._cuj_completed_millis, self._exceeded_threshold_millis))
    if self._cuj_completed_millis > self._reached_baseline_millis:
      raise ValueError('CUJ completed millis ({}) > reached baseline millis ({})'
                        .format(self._cuj_completed_millis, self._reached_baseline_millis))
    if self._reached_baseline_total_duration_millis <= 0:
      raise ValueError('Time taken to reach PSI baseline <= 0: {}'
                        .format(self._reached_baseline_total_duration_millis))

  def process_psi_df(self, psi_df):
    if psi_df.empty:
      return
    self._max_psi_value = psi_df[self._psi_to_monitor].max()
    prev_psi_value = 0.0
    prev_monitor_start_relative_millis = 0.0
    for index, row in psi_df.iterrows():
      monitor_start_relative_millis = row['monitor_start_relative_millis']
      event_descriptions = row['event_descriptions']
      psi_value = row[self._psi_to_monitor]
      cur_polling_duration_millis = (monitor_start_relative_millis
        - prev_monitor_start_relative_millis)

      if (psi_value >= 80.0 or prev_psi_value >= 80.0):
        self._total_duration_above_80p_threshold_millis += cur_polling_duration_millis

      if (psi_value >= 50.0 or prev_psi_value >= 50.0):
        self._total_duration_above_50p_threshold_millis += cur_polling_duration_millis

      if (psi_value >= 30.0 or prev_psi_value >= 30.0):
        self._total_duration_above_30p_threshold_millis += cur_polling_duration_millis

      for event_description in event_descriptions:
        if self._dropped_below_threshold_millis:
          self._max_psi_value_after_dropped_below_threshold = max(
            self._max_psi_value_after_dropped_below_threshold, psi_value)

        if self._cuj_completed_millis is None and event_description == CUJ_COMPLETED_EVENT:
          self._cuj_completed_millis = monitor_start_relative_millis
          continue

        if self._exceeded_threshold_millis is None and self._do_match_exceeded_threshold_millis(
          monitor_start_relative_millis, event_description):
          continue

        if (self._dropped_below_threshold_millis is None and
          self._do_match_dropped_below_threshold_millis(monitor_start_relative_millis,
            event_description)):
          continue

        if self._reached_baseline_millis is None:
          m = PSI_REACHED_BASELINE_RE.match(event_description)
          if not m:
            continue
          self._reached_baseline_millis = monitor_start_relative_millis
          matched = m.groupdict()
          self._reached_baseline_latest_psi_entries = int(matched['latest_psi_entries'])
          prev_entries = self._reached_baseline_latest_psi_entries
          if prev_entries > index:
            raise ValueError('Previous N PSI entries {} used in baseline calculation is greater '
                             + 'than index {}'.format(prev_entries, index))
          self._reached_baseline_total_duration_millis = (self._reached_baseline_millis -
            psi_df.loc[index - prev_entries, 'monitor_start_relative_millis'])

      prev_psi_value = psi_value
      prev_monitor_start_relative_millis = monitor_start_relative_millis


  def populate_kpi_df(self, kpi_df) -> pd.DataFrame:
    self._check_valid()
    psi_type = self._dropped_below_threshold_type
    psi_threshold = self._dropped_below_threshold_value
    kpi_df.loc[len(kpi_df)] = [CUJ_COMPLETED_EVENT, PSI_EVENT_TYPE, PSI_MONITOR_TAG,
      float(self._cuj_completed_millis/1000.0), 'Seconds since monitor start']
    kpi_df.loc[len(kpi_df)] = [
      'Time taken to reach \'{}\' PSI baseline (including baseline calculation duration)'.format(
        psi_type), PSI_EVENT_TYPE, PSI_MONITOR_TAG,
      float(self._reached_baseline_millis - self._cuj_completed_millis)/1000.0, 'Duration seconds']
    kpi_df.loc[len(kpi_df)] = [
      'Total number of \'{}\' PSI entries used in baseline calculation'.format(psi_type),
      PSI_EVENT_TYPE, PSI_MONITOR_TAG, self._reached_baseline_latest_psi_entries,
      'Number of entries']
    kpi_df.loc[len(kpi_df)] = ['Baseline calculation duration', PSI_MONITOR_TAG, PSI_EVENT_TYPE,
      float(self._reached_baseline_total_duration_millis/1000.0), 'Duration seconds']
    kpi_df.loc[len(kpi_df)] = ['Max \'{}\' PSI value'.format(psi_type), PSI_EVENT_TYPE,
      PSI_MONITOR_TAG, self._max_psi_value, 'Percent']
    kpi_df.loc[len(kpi_df)] = ['Time spent above the \'{}\' PSI threshold {}%'.format(psi_type,
      psi_threshold), PSI_EVENT_TYPE, PSI_MONITOR_TAG,
      float(self._dropped_below_threshold_millis - self._exceeded_threshold_millis)/1000.0,
      'Duration seconds']
    kpi_df.loc[len(kpi_df)] = ['Time taken to drop below the \'{}\' PSI threshold'.format(psi_type),
      PSI_EVENT_TYPE, PSI_MONITOR_TAG,
      (self._dropped_below_threshold_millis - self._cuj_completed_millis)/1000.0,
      'Duration seconds']
    kpi_df.loc[len(kpi_df)] = [
      'Max \'{}\' PSI value after dropped below threshold'.format(psi_type), PSI_EVENT_TYPE,
      PSI_MONITOR_TAG, self._max_psi_value_after_dropped_below_threshold, 'Percent']
    kpi_df.loc[len(kpi_df)] = ['Total time spent above the \'{}\' PSI threshold 80%'.format(
      psi_type), PSI_EVENT_TYPE, PSI_MONITOR_TAG,
      float(self._total_duration_above_80p_threshold_millis)/1000.0, 'Duration seconds']
    kpi_df.loc[len(kpi_df)] = ['Total time spent above the \'{}\' PSI threshold 50%'.format(
      psi_type), PSI_EVENT_TYPE, PSI_MONITOR_TAG,
      float(self._total_duration_above_50p_threshold_millis)/1000.0, 'Duration seconds']
    kpi_df.loc[len(kpi_df)] = ['Total time spent above the \'{}\' PSI threshold 30%'.format(
      psi_type), PSI_EVENT_TYPE, PSI_MONITOR_TAG,
      float(self._total_duration_above_30p_threshold_millis)/1000.0, 'Duration seconds']
    return kpi_df


def process_events_df(events_df, kpi_df) -> pd.DataFrame:
  if events_df.empty:
    return kpi_df
  filtered_events_df = events_df.dropna(subset=['event_key', 'duration_value'])
  filtered_events_df = filtered_events_df[filtered_events_df['duration_value'] > 0]
  for index, row in filtered_events_df.iterrows():
    if row['event_key'] is None:
      continue
    kpi_df.loc[len(kpi_df)] = [row['event_key'], LOGCAT_EVENT_TYPE, row['event_tag'],
      row['duration_value'], LOGCAT_EVENT_UNIT]
  return kpi_df


def get_kpi_df(psi_df, events_df) -> pd.DataFrame:
  kpi_df = pd.DataFrame(columns=COLUMNS)
  psi_kpi = PsiKpi(PSI_TO_MONITOR)
  psi_kpi.process_psi_df(psi_df)
  kpi_df = psi_kpi.populate_kpi_df(kpi_df)

  if events_df is None:
    return kpi_df

  return process_events_df(events_df, kpi_df)


def main():
  parser = argparse.ArgumentParser(
      description='Plot PSI csv dump from PSI monitor.',
  )
  parser.add_argument('--psi_csv', action='store', type=str, required=True, dest='psi_csv',
                      help='PSI csv dump from psi_monitor.sh')
  parser.add_argument('--events_csv', action='store', type=str, dest='events_csv',
                      help='Events csv dump from run_cuj.sh')
  parser.add_argument('--out_kpi_csv', action='store', type=str, dest='kpi_csv',
                      default='kpis.csv', help='Output KPI CSV file')
  args = parser.parse_args()

  psi_df = pd.read_csv(args.psi_csv, converters={'event_descriptions': pd.eval})
  events_df = None
  if args.events_csv:
    events_df = pd.read_csv(args.events_csv).drop_duplicates()

  kpi_df = get_kpi_df(psi_df, events_df)

  kpi_df.to_csv(args.kpi_csv, index=False)

if __name__ == '__main__':
  main()
