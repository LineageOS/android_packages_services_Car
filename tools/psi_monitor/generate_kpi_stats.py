#!/usr/bin/env python

import argparse
import glob
import pandas as pd
import re

KPI_STATS_COLUMNS=('Event', 'Type', 'Unit', 'Min', 'Max', 'Q1', 'Median', 'Q3', 'Stddev', 'Size',
  'Count_non_na', 'Low outlier run ids', 'High outlier run ids', 'Outliers')


def merge_dfs(kpi_dfs_dict):
  merged_df = None
  for run_id, df in kpi_dfs_dict.items():
    df['RunId'] = run_id
    if merged_df is None:
      merged_df = df
    else:
      merged_df = pd.merge(merged_df, df, how='outer')
  return merged_df.reset_index() if merged_df is not None else None


def convert_millis_to_secs(merged_df):
  merged_df['Value'] = merged_df.apply(lambda x: float(x['Value']) / 1000.0
    if x['Unit'] == 'Duration millis' and x['Type'] == 'psi_event' else x['Value'], axis=1)
  merged_df['Unit'] = merged_df.apply(lambda x: 'Duration seconds'
    if x['Unit'] == 'Duration millis' and x['Type'] == 'psi_event' else x['Unit'], axis=1)
  return merged_df

def get_outliers(df, q1, q3):
  iqr = q3 - q1
  lower_bound = q1 - 1.5 * iqr
  upper_bound = q3 + 1.5 * iqr
  low_outliers = df[df['Value'] < lower_bound]
  high_outliers = df[df['Value'] > upper_bound]
  outliers = df[(df['Value'] < lower_bound) | (df['Value'] > upper_bound)]
  return (low_outliers['RunId'].tolist(), high_outliers['RunId'].tolist(),
          outliers['Value'].tolist())


def get_kpi_stats_df(merged_df):
  kpi_stats_df = pd.DataFrame(columns=KPI_STATS_COLUMNS)
  for name, group in merged_df.groupby(['Event', 'Type', 'Unit']):
    kpi = group['Value']
    q1 = kpi.quantile(0.25)
    q3 = kpi.quantile(0.75)
    low_outlier_run_ids, high_outlier_run_ids, outliers = get_outliers(group, q1, q3)

    kpi_stats_df.loc[len(kpi_stats_df)] = [name[0], name[1], name[2], kpi.min(), kpi.max(),
    q1, kpi.median(), q3, kpi.std(), kpi.size, kpi.count(), low_outlier_run_ids,
    high_outlier_run_ids, outliers]
  return kpi_stats_df


def main():
  parser = argparse.ArgumentParser(
    description='Generate KPI statistics from KPIs gathered across multiple runs of a CUJ')
  parser.add_argument('--kpi_csv_files', action='store', type=str, required=True,
                      dest='kpi_csv_files',
                      help='Comma separated filepaths of kpis.csv from multiple iterations of a CUJ'
                      )
  parser.add_argument('--run_id_re', action='store', type=str,
                      default=r".*\/run_(\d+)\/processed\/kpis.csv", dest='run_id_re',
                      help="Regex pattern to extract run id from the filepaths")
  parser.add_argument('--out_file', action='store', type=str, required=True,
                      dest='out_file', help='Output file to store the KPI stats')
  args = parser.parse_args()
  kpi_csv_files=args.kpi_csv_files.split(",")

  run_id_re=re.compile(args.run_id_re)
  kpi_dfs_dict={}
  for filepath in kpi_csv_files:
    m = run_id_re.match(filepath)
    if m:
      kpi_dfs_dict[m.group(1)]=pd.read_csv(filepath)

  merged_df = merge_dfs(kpi_dfs_dict)
  if merged_df is None or merged_df.empty:
    raise ValueError("Failed to parse kpi_dfs from {}".format(kpi_csv_files))
  kpi_stats_df = get_kpi_stats_df(convert_millis_to_secs(merged_df))
  kpi_stats_df.to_csv(args.out_file, index=False)

if __name__ == '__main__':
  main()
