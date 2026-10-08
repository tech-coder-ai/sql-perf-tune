import { Tracker } from '../../core/models';

export type ColKind =
  | 'id'
  | 'num'
  | 'minutes'
  | 'seconds'
  | 'pct'
  | 'delta'
  | 'deltaPts'
  | 'sql'
  | 'text'
  | 'longtext'
  | 'status'
  | 'lookup'
  | 'mono'
  | 'ts'
  | 'days';

/** Before / after tuning: drives the header colour (original, post-implementation, change). */
export type Phase = 'pre' | 'post' | 'delta';

export interface TrackerColumn {
  key: string;
  label: string;
  kind: ColKind;
  /** server sort property, if sortable */
  sort?: string;
  visible: boolean;
  phase?: Phase;
  /** dropdown category for 'lookup' columns (pill coloured by the value's tone) */
  category?: string;
}

/** Column order follows the tracking-screen specification; each metric shows original, post-run and Δ% together. */
export const TRACKER_COLUMNS: TrackerColumn[] = [
  { key: 'groupId', label: 'Group ID', kind: 'id', sort: 'groupId', visible: true },
  { key: 'workflowStatus', label: 'Stage', kind: 'status', sort: 'workflowStatus', visible: true },
  { key: 'daysInStage', label: 'Days in stage', kind: 'days', visible: true },
  { key: 'priority', label: 'Priority', kind: 'status', sort: 'priority', visible: true },
  { key: 'requestSource', label: 'Source', kind: 'status', sort: 'requestSource', visible: true },
  { key: 'iterationCount', label: 'Iterations', kind: 'num', visible: true },
  { key: 'groupSize', label: 'Group size', kind: 'num', sort: 'group.groupSize', visible: true },
  { key: 'distinctUsers', label: 'Distinct users', kind: 'num', sort: 'group.distinctUsers', visible: true },
  { key: 'fingerprint', label: 'Fingerprint', kind: 'mono', visible: false },
  { key: 'avgDurationMinutes', label: 'Avg duration', kind: 'minutes', sort: 'group.avgDurationMinutes', visible: true },
  { key: 'minDurationMinutes', label: 'Min duration', kind: 'minutes', sort: 'group.minDurationMinutes', visible: false },
  { key: 'maxDurationMinutes', label: 'Max duration', kind: 'minutes', sort: 'group.maxDurationMinutes', visible: true },
  { key: 'totalDurationMinutes', label: 'Total duration', kind: 'minutes', sort: 'group.totalDurationMinutes', visible: true },
  { key: 'sampleQuerySeqId', label: 'Sample seq ID', kind: 'num', visible: false },
  { key: 'sampleQueryRaw', label: 'Sample query', kind: 'sql', visible: true },
  { key: 'sampleQueryFormatted', label: 'Sample query (formatted)', kind: 'sql', visible: false },
  { key: 'rowIndices', label: 'Row indices', kind: 'mono', visible: false },
  { key: 'cleansedQuery', label: 'Cleansed query', kind: 'sql', visible: false },
  { key: 'optimizedQuery', label: 'Optimized query', kind: 'sql', visible: false },
  { key: 'devTeamLead', label: 'Dev team lead', kind: 'text', sort: 'devTeamLead', visible: true },
  { key: 'devTeamStatus', label: 'Dev team status', kind: 'lookup', category: 'DEV_TEAM_STATUS', sort: 'devTeamStatus', visible: true },
  { key: 'clouderaTeamLead', label: 'Cloudera team lead', kind: 'text', sort: 'clouderaTeamLead', visible: true },
  { key: 'smeTeamLead', label: 'SME team lead', kind: 'text', sort: 'smeTeamLead', visible: false },
  { key: 'optimizedSqlStatus', label: 'Optimized SQL', kind: 'lookup', category: 'OPTIMIZED_SQL_STATUS', sort: 'optimizedSqlStatus', visible: true },
  { key: 'clouderaPostRunValidation', label: 'Cloudera post-run validation', kind: 'lookup', category: 'CLOUDERA_POST_RUN_VALIDATION', visible: false },
  // ---- before / after tuning
  { key: 'ogRunDurationMinutes', label: 'OG run duration', kind: 'minutes', sort: 'ogRunDurationMinutes', visible: true, phase: 'pre' },
  { key: 'postRunDurationMinutes', label: 'Post-run duration', kind: 'minutes', sort: 'postRunDurationMinutes', visible: true, phase: 'post' },
  { key: 'runDurationDeltaPct', label: 'Δ run duration %', kind: 'delta', visible: true, phase: 'delta' },
  { key: 'ogExecutionTimeSeconds', label: 'OG execution time', kind: 'seconds', visible: true, phase: 'pre' },
  { key: 'postRunExecutionTimeSeconds', label: 'Post-run execution time', kind: 'seconds', visible: true, phase: 'post' },
  { key: 'executionTimeDeltaPct', label: 'Δ execution %', kind: 'delta', visible: true, phase: 'delta' },
  { key: 'ogTeardownTimeSeconds', label: 'OG teardown time', kind: 'seconds', visible: false, phase: 'pre' },
  { key: 'postRunTeardownTimeSeconds', label: 'Post-run teardown time', kind: 'seconds', visible: false, phase: 'post' },
  { key: 'teardownTimeDeltaPct', label: 'Δ teardown %', kind: 'delta', visible: false, phase: 'delta' },
  { key: 'ogTeardownPct', label: 'OG teardown %', kind: 'pct', visible: false, phase: 'pre' },
  { key: 'postRunTeardownPct', label: 'Post-run teardown %', kind: 'pct', visible: false, phase: 'post' },
  { key: 'teardownPctDeltaPts', label: 'Δ teardown share (pts)', kind: 'deltaPts', visible: false, phase: 'delta' },
  { key: 'ogCpuSeconds', label: 'OG CPU time', kind: 'seconds', visible: true, phase: 'pre' },
  { key: 'postRunCpuSeconds', label: 'Post-run CPU time', kind: 'seconds', visible: true, phase: 'post' },
  { key: 'cpuDeltaPct', label: 'Δ CPU %', kind: 'delta', visible: true, phase: 'delta' },
  { key: 'ogRowsScanned', label: 'OG rows scanned', kind: 'num', visible: false, phase: 'pre' },
  { key: 'postRunRowsScanned', label: 'Post-run rows scanned', kind: 'num', visible: false, phase: 'post' },
  { key: 'rowsScannedDeltaPct', label: 'Δ rows scanned %', kind: 'delta', visible: false, phase: 'delta' },
  { key: 'ogTablesScanned', label: 'OG tables scanned', kind: 'num', visible: false, phase: 'pre' },
  { key: 'postRunTablesScanned', label: 'Post-run tables scanned', kind: 'num', visible: false, phase: 'post' },
  { key: 'tablesScannedDeltaPct', label: 'Δ tables scanned %', kind: 'delta', visible: false, phase: 'delta' },
  { key: 'ogPeakMemoryMb', label: 'OG peak memory (MB)', kind: 'num', visible: false, phase: 'pre' },
  { key: 'postRunPeakMemoryMb', label: 'Post-run peak memory (MB)', kind: 'num', visible: false, phase: 'post' },
  { key: 'peakMemoryDeltaPct', label: 'Δ peak memory %', kind: 'delta', visible: false, phase: 'delta' },
  { key: 'improvementPct', label: 'Improvement (faster %)', kind: 'pct', visible: false, phase: 'delta' },
  // ----
  { key: 'theme', label: 'Theme', kind: 'lookup', category: 'THEME', sort: 'theme', visible: true },
  { key: 'smeValidation', label: 'SME validation', kind: 'lookup', category: 'SME_VALIDATION', visible: false },
  { key: 'installStatus', label: 'Install', kind: 'lookup', category: 'INSTALL_STATUS', visible: false },
  { key: 'executeStatus', label: 'Execute', kind: 'lookup', category: 'EXECUTE_STATUS', visible: false },
  { key: 'validationStatus', label: 'Validation', kind: 'lookup', category: 'VALIDATION_STATUS', visible: false },
  { key: 'changes', label: 'Changes', kind: 'longtext', visible: false },
  { key: 'problem', label: 'Problem', kind: 'longtext', visible: false },
  { key: 'recommendations', label: 'Recommendations', kind: 'longtext', visible: false },
  { key: 'requestedBy', label: 'Requested by', kind: 'text', sort: 'requestedBy', visible: false },
  { key: 'environment', label: 'Environment', kind: 'lookup', category: 'ENVIRONMENT', sort: 'environment', visible: false },
  { key: 'firstSeen', label: 'First seen', kind: 'ts', visible: false },
  { key: 'adoptedAt', label: 'Adopted', kind: 'ts', sort: 'adoptedAt', visible: true },
  { key: 'daysOpen', label: 'Days open', kind: 'days', visible: false },
  { key: 'updatedAt', label: 'Updated', kind: 'ts', sort: 'updatedAt', visible: false },
  { key: 'updatedBy', label: 'Updated by', kind: 'text', visible: false },
];

export function cellValue(row: Tracker, key: string): unknown {
  if (key.startsWith('cf:')) return row.customFields?.[key.substring(3)];
  return (row as unknown as Record<string, unknown>)[key];
}
