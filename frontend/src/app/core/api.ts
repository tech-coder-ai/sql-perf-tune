import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AuditEvent,
  CustomEntityType,
  CustomField,
  DashboardSummary,
  DataSource,
  Feedback,
  IngestionBatch,
  LoadHistoryEntry,
  OptimizationRun,
  Page,
  PromptTemplate,
  QueryGroup,
  QueryLog,
  SourceFile,
  SqlDiagnostic,
  TableDdl,
  Tracker,
} from './models';

export type Params = Record<string, string | number | boolean | readonly string[] | null | undefined>;

function toParams(p: Params): HttpParams {
  let params = new HttpParams();
  for (const [k, v] of Object.entries(p)) {
    if (v === null || v === undefined || v === '') continue;
    if (Array.isArray(v)) {
      for (const item of v) params = params.append(k, item);
    } else {
      params = params.set(k, String(v));
    }
  }
  return params;
}

/** Thin typed client for the Spring Boot API (served under /api, proxied in dev). */
@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);

  // ----- logs
  logs(p: Params): Observable<Page<QueryLog>> {
    return this.http.get<Page<QueryLog>>('/api/logs', { params: toParams(p) });
  }
  log(id: number): Observable<QueryLog> {
    return this.http.get<QueryLog>(`/api/logs/${id}`);
  }
  logHistory(id: number): Observable<LoadHistoryEntry[]> {
    return this.http.get<LoadHistoryEntry[]>(`/api/logs/${id}/history`);
  }

  // ----- ingestion
  upload(file: File, sqlEngine: string): Observable<IngestionBatch> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post<IngestionBatch>('/api/ingestion/upload', form, { params: toParams({ sqlEngine }) });
  }
  pull(dataSourceId: number): Observable<IngestionBatch> {
    return this.http.post<IngestionBatch>(`/api/ingestion/pull/${dataSourceId}`, null);
  }
  batches(): Observable<IngestionBatch[]> {
    return this.http.get<IngestionBatch[]>('/api/ingestion/batches');
  }
  loadedFiles(): Observable<SourceFile[]> {
    return this.http.get<SourceFile[]>('/api/ingestion/files');
  }

  // ----- groups
  groups(p: Params): Observable<Page<QueryGroup>> {
    return this.http.get<Page<QueryGroup>>('/api/groups', { params: toParams(p) });
  }
  group(id: number): Observable<QueryGroup> {
    return this.http.get<QueryGroup>(`/api/groups/${id}`);
  }
  groupLogs(id: number, p: Params): Observable<Page<QueryLog>> {
    return this.http.get<Page<QueryLog>>(`/api/groups/${id}/logs`, { params: toParams(p) });
  }
  saveGroupCustomFields(id: number, values: Record<string, string>): Observable<QueryGroup> {
    return this.http.put<QueryGroup>(`/api/groups/${id}/custom-fields`, values);
  }
  rebuildGroups(): Observable<{ groups: number }> {
    return this.http.post<{ groups: number }>('/api/groups/rebuild', null);
  }

  // ----- tracker
  trackers(p: Params): Observable<Page<Tracker>> {
    return this.http.get<Page<Tracker>>('/api/tracker', { params: toParams(p) });
  }
  tracker(id: number): Observable<Tracker> {
    return this.http.get<Tracker>(`/api/tracker/${id}`);
  }
  trackGroups(groupIds: number[]): Observable<Tracker[]> {
    return this.http.post<Tracker[]>('/api/tracker', { groupIds });
  }
  updateTracker(id: number, body: Partial<Tracker>): Observable<Tracker> {
    return this.http.put<Tracker>(`/api/tracker/${id}`, body);
  }
  trackerHistory(id: number): Observable<AuditEvent[]> {
    return this.http.get<AuditEvent[]>(`/api/tracker/${id}/history`);
  }
  exportTracker(p: Params): Observable<HttpResponse<Blob>> {
    return this.http.get('/api/tracker/export', { params: toParams(p), responseType: 'blob', observe: 'response' });
  }

  // ----- workflow
  diagnostics(groupId: number): Observable<SqlDiagnostic[]> {
    return this.http.get<SqlDiagnostic[]>(`/api/groups/${groupId}/diagnostics`);
  }
  captureDiagnostic(groupId: number, body: object): Observable<SqlDiagnostic> {
    return this.http.post<SqlDiagnostic>(`/api/groups/${groupId}/diagnostics`, body);
  }
  ddls(groupId: number): Observable<TableDdl[]> {
    return this.http.get<TableDdl[]>(`/api/groups/${groupId}/ddls`);
  }
  addDdl(groupId: number, body: object): Observable<TableDdl> {
    return this.http.post<TableDdl>(`/api/groups/${groupId}/ddls`, body);
  }
  deleteDdl(groupId: number, ddlId: number): Observable<void> {
    return this.http.delete<void>(`/api/groups/${groupId}/ddls/${ddlId}`);
  }
  runs(groupId: number): Observable<OptimizationRun[]> {
    return this.http.get<OptimizationRun[]>(`/api/groups/${groupId}/optimization-runs`);
  }
  startRun(groupId: number, promptTemplateId?: number): Observable<OptimizationRun> {
    return this.http.post<OptimizationRun>(`/api/groups/${groupId}/optimization-runs`, { promptTemplateId });
  }
  submitRunResponse(groupId: number, runId: number, responseRaw: string, modelName: string): Observable<OptimizationRun> {
    return this.http.post<OptimizationRun>(`/api/groups/${groupId}/optimization-runs/${runId}/response`, {
      responseRaw,
      modelName,
    });
  }
  feedback(groupId: number): Observable<Feedback[]> {
    return this.http.get<Feedback[]>(`/api/groups/${groupId}/feedback`);
  }
  addFeedback(groupId: number, body: object): Observable<Feedback> {
    return this.http.post<Feedback>(`/api/groups/${groupId}/feedback`, body);
  }

  // ----- admin
  dataSources(): Observable<DataSource[]> {
    return this.http.get<DataSource[]>('/api/data-sources');
  }
  saveDataSource(ds: Partial<DataSource>): Observable<DataSource> {
    return ds.id
      ? this.http.put<DataSource>(`/api/data-sources/${ds.id}`, ds)
      : this.http.post<DataSource>('/api/data-sources', ds);
  }
  testDataSource(id: number): Observable<{ ok: boolean; message: string }> {
    return this.http.post<{ ok: boolean; message: string }>(`/api/data-sources/${id}/test`, null);
  }
  customFields(entityType: CustomEntityType): Observable<CustomField[]> {
    return this.http.get<CustomField[]>('/api/custom-fields', { params: toParams({ entityType }) });
  }
  saveCustomField(f: Partial<CustomField>): Observable<CustomField> {
    return f.id
      ? this.http.put<CustomField>(`/api/custom-fields/${f.id}`, f)
      : this.http.post<CustomField>('/api/custom-fields', f);
  }
  prompts(): Observable<PromptTemplate[]> {
    return this.http.get<PromptTemplate[]>('/api/prompt-templates');
  }
  savePrompt(body: object): Observable<PromptTemplate> {
    return this.http.post<PromptTemplate>('/api/prompt-templates', body);
  }
  activatePrompt(id: number): Observable<PromptTemplate> {
    return this.http.post<PromptTemplate>(`/api/prompt-templates/${id}/activate`, null);
  }
  dashboard(): Observable<DashboardSummary> {
    return this.http.get<DashboardSummary>('/api/dashboard/summary');
  }
}
