import { Tracker } from '../../core/models';

export type ColKind = 'id' | 'num' | 'minutes' | 'seconds' | 'pct' | 'sql' | 'text' | 'longtext' | 'status' | 'mono' | 'ts';

export interface TrackerColumn {
  key: string;
  label: string;
  kind: ColKind;
  /** server sort property, if sortable */
  sort?: string;
  visible: boolean;
}

/** Column order follows the tracking-screen specification. */
export const TRACKER_COLUMNS: TrackerColumn[] = [
  { key: 'groupId', label: 'Group ID', kind: 'id', sort: 'groupId', visible: true },
  { key: 'workflowStatus', label: 'Status', kind: 'status', sort: 'workflowStatus', visible: true },
  { key: 'priority', label: 'Priority', kind: 'status', sort: 'priority', visible: true },
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
  { key: 'devTeamStatus', label: 'Dev team status', kind: 'text', sort: 'devTeamStatus', visible: true },
  { key: 'clouderaTeamLead', label: 'Cloudera team lead', kind: 'text', sort: 'clouderaTeamLead', visible: true },
  { key: 'smeTeamLead', label: 'SME team lead', kind: 'text', sort: 'smeTeamLead', visible: false },
  { key: 'optimizedSqlStatus', label: 'Optimized SQL', kind: 'text', sort: 'optimizedSqlStatus', visible: true },
  { key: 'clouderaPostRunValidation', label: 'Cloudera post-run validation', kind: 'text', visible: false },
  { key: 'ogRunDurationMinutes', label: 'OG run duration', kind: 'minutes', sort: 'ogRunDurationMinutes', visible: true },
  { key: 'postRunDurationMinutes', label: 'Post-run duration', kind: 'minutes', sort: 'postRunDurationMinutes', visible: true },
  { key: 'improvementPct', label: 'Improvement', kind: 'pct', visible: true },
  { key: 'ogExecutionTimeSeconds', label: 'OG execution time', kind: 'seconds', visible: false },
  { key: 'postRunExecutionTimeSeconds', label: 'Post-run execution time', kind: 'seconds', visible: false },
  { key: 'ogTeardownTimeSeconds', label: 'OG teardown time', kind: 'seconds', visible: false },
  { key: 'postRunTeardownTimeSeconds', label: 'Post-run teardown time', kind: 'seconds', visible: false },
  { key: 'ogTeardownPct', label: 'OG teardown %', kind: 'pct', visible: false },
  { key: 'postRunTeardownPct', label: 'Post-run teardown %', kind: 'pct', visible: false },
  { key: 'theme', label: 'Theme', kind: 'text', sort: 'theme', visible: true },
  { key: 'smeValidation', label: 'SME validation', kind: 'text', visible: false },
  { key: 'installStatus', label: 'Install', kind: 'text', visible: false },
  { key: 'executeStatus', label: 'Execute', kind: 'text', visible: false },
  { key: 'validationStatus', label: 'Validation', kind: 'text', visible: false },
  { key: 'changes', label: 'Changes', kind: 'longtext', visible: false },
  { key: 'problem', label: 'Problem', kind: 'longtext', visible: false },
  { key: 'recommendations', label: 'Recommendations', kind: 'longtext', visible: false },
  { key: 'updatedAt', label: 'Updated', kind: 'ts', sort: 'updatedAt', visible: false },
  { key: 'updatedBy', label: 'Updated by', kind: 'text', visible: false },
];

export function cellValue(row: Tracker, key: string): unknown {
  if (key.startsWith('cf:')) return row.customFields?.[key.substring(3)];
  return (row as unknown as Record<string, unknown>)[key];
}
