package com.techcoder.sqlperf.tracker;

import java.time.LocalDateTime;
import java.util.Map;

import com.techcoder.sqlperf.group.QueryGroup;

/**
 * Tracking-screen row: tracker fields plus live metrics of its group (column order follows the screen spec).
 */
public record TrackerDto(
        Long trackerId,
        Long groupId,
        int groupSize,
        int distinctUsers,
        String fingerprint,
        Double avgDurationMinutes,
        Double minDurationMinutes,
        Double maxDurationMinutes,
        Double totalDurationMinutes,
        Long sampleQuerySeqId,
        String sampleQueryRaw,
        String sampleQueryFormatted,
        String rowIndices,
        String cleansedQuery,
        String optimizedQuery,
        String devTeamLead,
        String devTeamStatus,
        String clouderaTeamLead,
        String smeTeamLead,
        String optimizedSqlStatus,
        String clouderaPostRunValidation,
        Double ogRunDurationMinutes,
        Double postRunDurationMinutes,
        Double ogExecutionTimeSeconds,
        Double postRunExecutionTimeSeconds,
        Double ogTeardownTimeSeconds,
        Double postRunTeardownTimeSeconds,
        Double ogTeardownPct,
        Double postRunTeardownPct,
        String theme,
        String smeValidation,
        String installStatus,
        String executeStatus,
        String validationStatus,
        String changes,
        String problem,
        String recommendations,
        TuningTracker.WorkflowStatus workflowStatus,
        TuningTracker.Priority priority,
        Double improvementPct,
        String sqlEngine,
        LocalDateTime createdAt,
        String createdBy,
        LocalDateTime updatedAt,
        String updatedBy,
        int version,
        Map<String, String> customFields) {

    public static TrackerDto of(TuningTracker t, QueryGroup g, Map<String, String> custom) {
        return new TrackerDto(t.getId(), g.getId(), g.getGroupSize(), g.getDistinctUsers(), g.getFingerprint(),
                g.getAvgDurationMinutes(), g.getMinDurationMinutes(), g.getMaxDurationMinutes(),
                g.getTotalDurationMinutes(), g.getSampleQuerySeqId(), g.getSampleQuery(), t.getSampleQueryFormatted(),
                g.getRowIndices(), t.getCleansedQuery(), t.getOptimizedQuery(), t.getDevTeamLead(),
                t.getDevTeamStatus(), t.getClouderaTeamLead(), t.getSmeTeamLead(), t.getOptimizedSqlStatus(),
                t.getClouderaPostRunValidation(), t.getOgRunDurationMinutes(), t.getPostRunDurationMinutes(),
                t.getOgExecutionTimeSeconds(), t.getPostRunExecutionTimeSeconds(), t.getOgTeardownTimeSeconds(),
                t.getPostRunTeardownTimeSeconds(), t.getOgTeardownPct(), t.getPostRunTeardownPct(), t.getTheme(),
                t.getSmeValidation(), t.getInstallStatus(), t.getExecuteStatus(), t.getValidationStatus(),
                t.getChanges(), t.getProblem(), t.getRecommendations(), t.getWorkflowStatus(), t.getPriority(),
                improvement(t.getOgRunDurationMinutes(), t.getPostRunDurationMinutes()), g.getSqlEngine(),
                t.getCreatedAt(), t.getCreatedBy(), t.getUpdatedAt(), t.getUpdatedBy(), t.getVersion(),
                custom == null ? Map.of() : custom);
    }

    /** Run-time reduction in percent (positive = faster). */
    static Double improvement(Double before, Double after) {
        if (before == null || after == null || before <= 0) {
            return null;
        }
        return Math.round((before - after) / before * 1000d) / 10d;
    }
}
