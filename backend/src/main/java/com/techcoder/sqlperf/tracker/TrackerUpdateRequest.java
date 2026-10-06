package com.techcoder.sqlperf.tracker;

import java.util.Map;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Full replacement of the editable tracker fields (null clears a value). {@code version} must be the
 * version the client loaded; a mismatch returns 409 so concurrent edits are never silently lost.
 * Every component name must match a {@link TuningTracker} property (copied generically).
 */
public record TrackerUpdateRequest(
        @NotNull Integer version,
        TuningTracker.WorkflowStatus workflowStatus,
        TuningTracker.Priority priority,
        String sampleQueryFormatted,
        String cleansedQuery,
        String optimizedQuery,
        String devTeamLead,
        String devTeamStatus,
        String clouderaTeamLead,
        String smeTeamLead,
        String optimizedSqlStatus,
        String clouderaPostRunValidation,
        @PositiveOrZero Double ogRunDurationMinutes,
        @PositiveOrZero Double postRunDurationMinutes,
        @PositiveOrZero Double ogExecutionTimeSeconds,
        @PositiveOrZero Double postRunExecutionTimeSeconds,
        @PositiveOrZero Double ogTeardownTimeSeconds,
        @PositiveOrZero Double postRunTeardownTimeSeconds,
        @PositiveOrZero Double ogTeardownPct,
        @PositiveOrZero Double postRunTeardownPct,
        String theme,
        String smeValidation,
        String installStatus,
        String executeStatus,
        String validationStatus,
        String changes,
        String problem,
        String recommendations,
        Map<String, String> customFields) {
}
