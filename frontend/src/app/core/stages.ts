import { RequestSource, WorkflowStatus } from './models';

export interface StageInfo {
  label: string;
  icon: string;
  hint: string;
  tone: 'ok' | 'warn' | 'bad' | 'info' | 'muted' | 'primary';
}

/** Business names for the workflow statuses (the database keeps the technical codes). */
export const STAGE: Record<WorkflowStatus, StageInfo> = {
  NEW: { label: 'Triage', icon: 'inbox', hint: 'On the tracker, waiting for analysis', tone: 'info' },
  DIAGNOSTICS_CAPTURED: { label: 'Diagnosed', icon: 'troubleshoot', hint: 'Explain plan / profile captured', tone: 'info' },
  OPTIMIZATION_REQUESTED: { label: 'Tuning', icon: 'tune', hint: 'Optimization in progress', tone: 'warn' },
  OPTIMIZED: { label: 'Candidate ready', icon: 'lightbulb', hint: 'At least one candidate iteration to test', tone: 'primary' },
  POST_RUN_VALIDATED: { label: 'Tested', icon: 'science', hint: 'Candidate tested; pick the best iteration', tone: 'primary' },
  SME_VALIDATION: { label: 'Awaiting adoption', icon: 'hourglass_top', hint: 'Best iteration is with users / SMEs', tone: 'warn' },
  ADOPTED: { label: 'Adopted', icon: 'verified', hint: 'Implemented by the users', tone: 'ok' },
  REJECTED: { label: 'Rejected', icon: 'block', hint: 'Closed without adoption', tone: 'bad' },
  ON_HOLD: { label: 'On hold', icon: 'pause_circle', hint: 'Parked', tone: 'muted' },
};

/** Happy path in order (board columns, journey steps). */
export const STAGE_ORDER: WorkflowStatus[] = [
  'NEW',
  'DIAGNOSTICS_CAPTURED',
  'OPTIMIZATION_REQUESTED',
  'OPTIMIZED',
  'POST_RUN_VALIDATED',
  'SME_VALIDATION',
  'ADOPTED',
];

export const REQUEST_SOURCE_LABEL: Record<RequestSource, string> = {
  LOG_DETECTED: 'Detected in logs',
  PROACTIVE_UAT: 'Proactive (UAT)',
  USER_REQUEST: 'User request',
};

export const stageLabel = (s: WorkflowStatus | null | undefined) => (s ? STAGE[s]?.label ?? s : '—');
