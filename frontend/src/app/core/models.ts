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
  createdAt: string;
  rowKey: string | null;
  /** number of loads that contained this row (processed only by the first) */
  seenCount: number;
  lastSeenAt: string | null;
  lastSeenBatchId: number | null;
}

export interface LoadHistoryEntry {
  batchId: number;
  seenAt: string;
  first: boolean;
  sourceKind: string | null;
  sourceName: string | null;
  duplicateOfBatchId: number | null;
}

export interface SourceFile {
  id: number;
  contentHash: string;
  fileName: string | null;
  fileSize: number | null;
  loadCount: number;
  processedBatchId: number;
  lastBatchId: number;
  firstLoadedAt: string;
  lastLoadedAt: string;
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
  /** change from original to post-implementation in % (negative = less) */
  runDurationDeltaPct: number | null;
  executionTimeDeltaPct: number | null;
  teardownTimeDeltaPct: number | null;
  /** percentage points */
  teardownPctDeltaPts: number | null;
  cpuDeltaPct: number | null;
  rowsScannedDeltaPct: number | null;
  tablesScannedDeltaPct: number | null;
  peakMemoryDeltaPct: number | null;
  sqlEngine: string;
  ogCpuSeconds: number | null;
  postRunCpuSeconds: number | null;
  ogRowsScanned: number | null;
  postRunRowsScanned: number | null;
  ogTablesScanned: number | null;
  postRunTablesScanned: number | null;
  ogBytesScanned: number | null;
  postRunBytesScanned: number | null;
  ogPeakMemoryMb: number | null;
  postRunPeakMemoryMb: number | null;
  requestSource: RequestSource;
  requestedBy: string | null;
  environment: string | null;
  selectedIterationId: number | null;
  iterationCount: number;
  firstSeen: string | null;
  lastSeen: string | null;
  stageChangedAt: string | null;
  daysInStage: number | null;
  daysOpen: number | null;
  adoptedAt: string | null;
  closedAt: string | null;
  createdAt: string;
  createdBy: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
  version: number;
  customFields: Record<string, string>;
}

export const REQUEST_SOURCES = ['LOG_DETECTED', 'PROACTIVE_UAT', 'USER_REQUEST'] as const;
export type RequestSource = (typeof REQUEST_SOURCES)[number];

export interface Lookup {
  id: number;
  category: string;
  value: string;
  sortOrder: number;
  tone: 'ok' | 'warn' | 'bad' | 'info' | 'muted' | null;
  active: boolean;
}

export type IterationStatus = 'PROPOSED' | 'TESTED' | 'FAILED' | 'SELECTED' | 'ADOPTED' | 'REJECTED';

export interface Iteration {
  id: number;
  trackerId: number;
  iterationNo: number;
  source: 'AI_AGENT' | 'MANUAL';
  optimizationRunId: number | null;
  optimizedSql: string | null;
  changeNarrative: string | null;
  status: IterationStatus;
  runDurationMinutes: number | null;
  executionTimeSeconds: number | null;
  teardownTimeSeconds: number | null;
  cpuSeconds: number | null;
  rowsScanned: number | null;
  bytesScanned: number | null;
  tablesScanned: number | null;
  peakMemoryMb: number | null;
  resultRowCount: number | null;
  resultMatches: boolean | null;
  notes: string | null;
  testedAt: string | null;
  testedBy: string | null;
  createdAt: string;
  createdBy: string | null;
  version: number;
  best: boolean;
  selected: boolean;
  durationImprovementPct: number | null;
  cpuImprovementPct: number | null;
  rowsScannedReductionPct: number | null;
  tableScansAvoided: number | null;
}

export interface JourneyStep {
  stage: WorkflowStatus;
  state: 'done' | 'current' | 'pending' | 'skipped' | 'stopped';
  enteredAt: string | null;
  daysInStage: number | null;
}

export interface Journey {
  trackerId: number;
  detectedAt: string;
  requestSource: RequestSource;
  current: WorkflowStatus;
  currentSince: string | null;
  steps: JourneyStep[];
  totalDays: number;
  daysSinceDetected: number;
}

export interface BoardCard {
  trackerId: number;
  groupId: number;
  status: WorkflowStatus;
  priority: Priority;
  theme: string | null;
  devTeamLead: string | null;
  requestSource: RequestSource;
  sqlSnippet: string | null;
  groupSize: number;
  totalDurationMinutes: number | null;
  daysInStage: number | null;
  daysOpen: number | null;
  iterationCount: number;
  improvementPct: number | null;
  adoptedAt: string | null;
}

export interface SearchHit {
  type: 'TRACKER' | 'GROUP' | 'LOG';
  id: number;
  title: string;
  subtitle: string | null;
  status: WorkflowStatus | null;
  trackerId: number | null;
  groupId: number | null;
}

export interface UserDirectoryEntry {
  userId: string;
  displayName: string | null;
  userGroup: string | null;
  department: string | null;
  active: boolean;
  updatedAt: string;
  updatedBy: string | null;
}

// ---------- insights (Q1-Q15) ----------
export interface CountPoint {
  label: string;
  count: number;
}

export interface PatternCount {
  groupId: number;
  count: number;
  totalInstances: number;
  sqlSnippet: string | null;
  trackerId: number | null;
  status: WorkflowStatus | null;
  theme: string | null;
  recurring: boolean;
}

export interface DailyInsight {
  date: string;
  badQueries: number;
  badQueriesPreviousDay: number;
  patterns: number;
  newPatterns: number;
  recurringPatterns: number;
  recurringQueries: number;
  repeatedTodayPatterns: number;
  totalMinutes: number;
  byHour: CountPoint[];
  last14Days: CountPoint[];
  topPatterns: PatternCount[];
  instanceBuckets: CountPoint[];
  topUsers: CountPoint[];
  peakHour: number | null;
  /** most recent day that has any bad query (null when nothing is loaded) */
  latestDataDay: string | null;
}

export interface PipelineInsight {
  stages: { status: WorkflowStatus; count: number }[];
  outstanding: {
    patterns: number;
    badQueries: number;
    untrackedPatterns: number;
    inProgressPatterns: number;
    awaitingAdoptionPatterns: number;
    onHoldOrRejectedPatterns: number;
    /** the figures above are the active backlog: bad queries in the last activeDays days up to asOf */
    activeDays: number;
    asOf: string;
    allTimePatterns: number;
    allTimeBadQueries: number;
    allTimeUntrackedPatterns: number;
  };
  priorDay: {
    date: string;
    patterns: number;
    badQueries: number;
    optimizedPatterns: number;
    optimizedQueries: number;
    notOptimizedPatterns: number;
    notOptimizedQueries: number;
    categorizedPatterns: number;
    uncategorizedPatterns: number;
    untrackedPatterns: number;
    themes: { theme: string; patterns: number; badQueries: number }[];
  };
  awaitingAdoption: {
    trackerId: number;
    groupId: number;
    sqlSnippet: string | null;
    theme: string | null;
    daysWaiting: number | null;
    improvementPct: number | null;
    devTeamLead: string | null;
  }[];
  rejections: {
    total: number;
    inaccurate: number;
    byReason: CountPoint[];
    latest: {
      groupId: number;
      trackerId: number | null;
      iterationId: number | null;
      reason: string | null;
      comments: string | null;
      role: string;
      by: string | null;
      at: string;
    }[];
  };
  turnaround: {
    adoptedItems: number;
    avgDays: number | null;
    medianDays: number | null;
    p90Days: number | null;
    avgDaysFromDetection: number | null;
    byStage: { stage: WorkflowStatus; avgDays: number | null }[];
    openItems: number;
    avgOpenAgeDays: number | null;
  };
}

export interface SavingsItem {
  trackerId: number;
  groupId: number;
  theme: string | null;
  sqlSnippet: string | null;
  adoptedAt: string;
  perRunMinutesSaved: number | null;
  perRunCpuSecondsSaved: number | null;
  perRunRowsReduced: number | null;
  perRunTablesAvoided: number | null;
  baselineRunsPerDay: number;
  runsPerDayAfter: number;
  adoptedDaysInPeriod: number;
  minutesSaved: number;
  cpuSecondsSaved: number;
  rowsReduced: number;
  tableScansAvoided: number;
  improvementPct: number | null;
}

export interface SavingsInsight {
  from: string;
  to: string;
  tunedItems: number;
  adoptedTotal: number;
  adoptedInPeriod: number;
  awaitingAdoption: number;
  adoptionRatePct: number | null;
  minutesSaved: number;
  cpuSecondsSaved: number;
  rowsScannedReduced: number;
  tableScansAvoided: number;
  byWeek: { label: string; minutesSaved: number; cpuSecondsSaved: number; adopted: number }[];
  themes: {
    theme: string;
    adoptedItems: number;
    avgImprovementPct: number | null;
    minutesSaved: number;
    cpuSecondsSaved: number;
    baselineRunsPerDay: number;
    runsPerDayAfter: number;
  }[];
  items: SavingsItem[];
  method: string;
}

export interface ThemePriority {
  rank: number;
  theme: string;
  outstandingPatterns: number;
  badQueries: number;
  minutes: number;
  expectedImprovementPct: number;
  improvementBasis: string;
  potentialMinutesPerMonth: number;
}

export interface UsersInsight {
  from: string;
  to: string;
  totalBadQueries: number;
  daysInRange: number;
  users: {
    userId: string;
    displayName: string | null;
    userGroup: string | null;
    badQueries: number;
    patterns: number;
    activeDays: number;
    consistencyPct: number;
    minutes: number;
    avgMinutes: number | null;
    sharePct: number;
    repeatOffender: boolean;
  }[];
  groups: { userGroup: string; users: number; badQueries: number; patterns: number; minutes: number; sharePct: number }[];
  unmappedUsers: number;
}

export interface TrendsInsight {
  months: {
    month: string;
    badQueries: number;
    newPatterns: number;
    adoptedPatterns: number;
    outstandingPatterns: number;
    outstandingBadQueries: number;
  }[];
  outstandingDirection: 'up' | 'down' | 'flat';
  outstandingChangePct: number | null;
  requestsByMonth: { month: string; created: Record<RequestSource, number> }[];
  requestsTotal: Record<RequestSource, number>;
  requestsAdopted: Record<RequestSource, number>;
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
  rowsDuplicate: number;
  groupsAffected: number;
  contentHash: string | null;
  sourceFileId: number | null;
  fileLoadNumber: number | null;
  duplicateOfBatchId: number | null;
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
  iterationId: number | null;
  cpuSeconds: number | null;
  rowsScanned: number | null;
  tablesScanned: number | null;
  peakMemoryMb: number | null;
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

/** A prompt rendered for a group without starting a run. */
export interface PromptPreview {
  promptTemplateId: number;
  templateName: string;
  versionNo: number;
  promptText: string;
  /** template placeholders whose input is not captured yet, e.g. "ddl", "explain" */
  missingInputs: string[];
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
  iterationId: number | null;
  rejectionReason: string | null;
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
