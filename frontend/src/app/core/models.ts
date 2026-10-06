export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface QueryLog {
  id: number;
  batchId: number;
  seqId: number | null;
  executedQuery: string | null;
  userQuery: string | null;
  errorCode: string | null;
  errorCategory: string | null;
  errorMessage: string | null;
  userId: string | null;
  startTime: string | null;
  endTime: string | null;
  durationMinutes: number | null;
  sqlEngine: string;
  fingerprint: string | null;
  groupId: number | null;
}

export interface QueryGroup {
  groupId: number;
  fingerprint: string;
  sqlEngine: string;
  groupSize: number;
  distinctUsers: number;
  userIds: string | null;
  durationCount: number;
  avgDurationMinutes: number | null;
  minDurationMinutes: number | null;
  maxDurationMinutes: number | null;
  totalDurationMinutes: number | null;
  errorCount: number;
  sampleLogId: number | null;
  sampleQuerySeqId: number | null;
  sampleQuery: string | null;
  normalizedQuery: string | null;
  rowIndices: string | null;
  firstSeen: string | null;
  lastSeen: string | null;
  trackerId: number | null;
  workflowStatus: WorkflowStatus | null;
  customFields: Record<string, string>;
}

export const WORKFLOW_STATUSES = [
  'NEW',
  'DIAGNOSTICS_CAPTURED',
  'OPTIMIZATION_REQUESTED',
  'OPTIMIZED',
  'POST_RUN_VALIDATED',
  'SME_VALIDATION',
  'ADOPTED',
  'REJECTED',
  'ON_HOLD',
] as const;
export type WorkflowStatus = (typeof WORKFLOW_STATUSES)[number];

export const PRIORITIES = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const;
export type Priority = (typeof PRIORITIES)[number];

export interface Tracker {
  trackerId: number;
  groupId: number;
  groupSize: number;
  distinctUsers: number;
  fingerprint: string;
  avgDurationMinutes: number | null;
  minDurationMinutes: number | null;
  maxDurationMinutes: number | null;
  totalDurationMinutes: number | null;
  sampleQuerySeqId: number | null;
  sampleQueryRaw: string | null;
  sampleQueryFormatted: string | null;
  rowIndices: string | null;
  cleansedQuery: string | null;
  optimizedQuery: string | null;
  devTeamLead: string | null;
  devTeamStatus: string | null;
  clouderaTeamLead: string | null;
  smeTeamLead: string | null;
  optimizedSqlStatus: string | null;
  clouderaPostRunValidation: string | null;
  ogRunDurationMinutes: number | null;
  postRunDurationMinutes: number | null;
  ogExecutionTimeSeconds: number | null;
  postRunExecutionTimeSeconds: number | null;
  ogTeardownTimeSeconds: number | null;
  postRunTeardownTimeSeconds: number | null;
  ogTeardownPct: number | null;
  postRunTeardownPct: number | null;
  theme: string | null;
  smeValidation: string | null;
  installStatus: string | null;
  executeStatus: string | null;
  validationStatus: string | null;
  changes: string | null;
  problem: string | null;
  recommendations: string | null;
  workflowStatus: WorkflowStatus;
  priority: Priority;
  improvementPct: number | null;
  sqlEngine: string;
  createdAt: string;
  createdBy: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
  version: number;
  customFields: Record<string, string>;
}

export interface IngestionBatch {
  id: number;
  sourceKind: 'CSV' | 'EXCEL' | 'JDBC';
  dataSourceId: number | null;
  sourceName: string | null;
  sqlEngine: string;
  status: 'RUNNING' | 'COMPLETED' | 'COMPLETED_WITH_ERRORS' | 'FAILED';
  rowsRead: number;
  rowsLoaded: number;
  rowsRejected: number;
  groupsAffected: number;
  message: string | null;
  startedAt: string;
  completedAt: string | null;
  createdBy: string | null;
}

export interface DataSource {
  id: number;
  name: string;
  sourceType: 'ORACLE' | 'IMPALA' | 'GENERIC_JDBC';
  sqlEngine: string;
  jdbcUrl: string;
  driverClass: string | null;
  username: string | null;
  passwordRef: string | null;
  logQuery: string;
  fetchSize: number;
  lastWatermark: string | null;
  active: boolean;
  description: string | null;
}

export type CustomEntityType = 'TRACKER' | 'GROUP' | 'LOG';
export type CustomDataType = 'TEXT' | 'LONG_TEXT' | 'NUMBER' | 'DATE' | 'BOOLEAN' | 'ENUM';

export interface CustomField {
  id: number;
  entityType: CustomEntityType;
  fieldKey: string;
  label: string;
  dataType: CustomDataType;
  optionsCsv: string | null;
  displayOrder: number;
  required: boolean;
  active: boolean;
}

export interface AuditEvent {
  id: number;
  action: string;
  fieldName: string | null;
  oldValue: string | null;
  newValue: string | null;
  changedAt: string;
  changedBy: string | null;
}

export interface SqlDiagnostic {
  id: number;
  groupId: number;
  phase: 'ORIGINAL' | 'OPTIMIZED';
  optimizationRunId: number | null;
  queryId: string | null;
  sqlText: string | null;
  rowCount: number | null;
  runDurationSeconds: number | null;
  explainPlan: string | null;
  profileSummary: string | null;
  execSummary: string | null;
  status: 'CAPTURED' | 'FAILED' | 'TIMEOUT';
  errorMessage: string | null;
  capturedAt: string;
  capturedBy: string | null;
}

export interface TableDdl {
  id: number;
  tableName: string;
  ddlText: string | null;
  rowCount: number | null;
  capturedAt: string;
}

export interface OptimizationRun {
  id: number;
  groupId: number;
  promptTemplateId: number | null;
  modelName: string | null;
  promptText: string | null;
  responseRaw: string | null;
  changeNarrative: string | null;
  optimizedSql: string | null;
  status: 'PENDING' | 'COMPLETED' | 'FAILED';
  outcome: 'ACCEPTED' | 'REJECTED' | null;
  errorMessage: string | null;
  requestedAt: string;
  completedAt: string | null;
  requestedBy: string | null;
}

export interface Feedback {
  id: number;
  optimizationRunId: number | null;
  sourceRole: 'BUSINESS_USER' | 'CLIENT_DEV' | 'CLOUDERA' | 'SME';
  decision: 'ADOPTED' | 'REJECTED' | 'COMMENT';
  comments: string | null;
  createdAt: string;
  createdBy: string | null;
}

export interface PromptTemplate {
  id: number;
  name: string;
  sqlEngine: string;
  versionNo: number;
  templateText: string;
  active: boolean;
  notes: string | null;
  createdAt: string;
  createdBy: string | null;
}

export interface DashboardSummary {
  logCount: number;
  groupCount: number;
  trackedCount: number;
  totalDurationMinutes: number | null;
  trackerByStatus: Record<string, number>;
  topGroups: {
    groupId: number;
    groupSize: number;
    avgDurationMinutes: number | null;
    totalDurationMinutes: number | null;
    sampleQuery: string | null;
  }[];
  savedMinutesPerRun: number | null;
}
