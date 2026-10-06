package com.techcoder.sqlperf.group;

import java.time.LocalDateTime;
import java.util.Map;

/** Group row for the grouping screen, with the drill-down count and the drill-up link to its tracker. */
public record QueryGroupDto(
        Long groupId,
        String fingerprint,
        String sqlEngine,
        int groupSize,
        int distinctUsers,
        String userIds,
        int durationCount,
        Double avgDurationMinutes,
        Double minDurationMinutes,
        Double maxDurationMinutes,
        Double totalDurationMinutes,
        int errorCount,
        Long sampleLogId,
        Long sampleQuerySeqId,
        String sampleQuery,
        String normalizedQuery,
        String rowIndices,
        LocalDateTime firstSeen,
        LocalDateTime lastSeen,
        Long trackerId,
        String workflowStatus,
        Map<String, String> customFields) {

    public static QueryGroupDto of(QueryGroup g, Long trackerId, String workflowStatus, Map<String, String> custom) {
        return new QueryGroupDto(g.getId(), g.getFingerprint(), g.getSqlEngine(), g.getGroupSize(), g.getDistinctUsers(),
                g.getUserIds(), g.getDurationCount(), g.getAvgDurationMinutes(), g.getMinDurationMinutes(),
                g.getMaxDurationMinutes(), g.getTotalDurationMinutes(), g.getErrorCount(), g.getSampleLogId(),
                g.getSampleQuerySeqId(), g.getSampleQuery(), g.getNormalizedQuery(), g.getRowIndices(),
                g.getFirstSeen(), g.getLastSeen(), trackerId, workflowStatus, custom == null ? Map.of() : custom);
    }
}
