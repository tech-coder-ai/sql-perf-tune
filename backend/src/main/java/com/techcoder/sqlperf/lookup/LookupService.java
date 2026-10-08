package com.techcoder.sqlperf.lookup;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.techcoder.sqlperf.common.NotFoundException;
import com.techcoder.sqlperf.common.Texts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dropdown values for tracker fields. A field whose category has values only accepts those values
 * (inactive ones stay valid for existing records so history never breaks).
 */
@Service
public class LookupService {

    /** Tracker property -> lookup category. */
    public static final Map<String, String> TRACKER_FIELDS = Map.of(
            "theme", "THEME",
            "devTeamStatus", "DEV_TEAM_STATUS",
            "optimizedSqlStatus", "OPTIMIZED_SQL_STATUS",
            "clouderaPostRunValidation", "CLOUDERA_POST_RUN_VALIDATION",
            "smeValidation", "SME_VALIDATION",
            "installStatus", "INSTALL_STATUS",
            "executeStatus", "EXECUTE_STATUS",
            "validationStatus", "VALIDATION_STATUS",
            "environment", "ENVIRONMENT");

    public static final Set<String> TONES = Set.of("ok", "warn", "bad", "info", "muted");

    private final LookupRepository repo;

    public LookupService(LookupRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public Map<String, List<Lookup>> all() {
        Map<String, List<Lookup>> out = new LinkedHashMap<>();
        for (Lookup l : repo.findAllByOrderByCategoryAscSortOrderAscIdAsc()) {
            out.computeIfAbsent(l.getCategory(), k -> new java.util.ArrayList<>()).add(l);
        }
        return out;
    }

    /** Throws when {@code value} is not a known value of {@code category} (blank / unknown category passes). */
    @Transactional(readOnly = true)
    public void validate(String category, String label, String value) {
        if (Texts.isBlank(value)) {
            return;
        }
        List<Lookup> values = repo.findByCategoryOrderBySortOrderAscIdAsc(category);
        if (!values.isEmpty() && values.stream().noneMatch(l -> l.getValue().equals(value))) {
            throw new IllegalArgumentException("'" + value + "' is not an allowed " + label
                    + " (manage values in Administration -> Dropdown values)");
        }
    }

    public record LookupRequest(String category, String value, Integer sortOrder, String tone, Boolean active) {
    }

    @Transactional
    public Lookup create(LookupRequest r) {
        if (Texts.isBlank(r.category()) || Texts.isBlank(r.value())) {
            throw new IllegalArgumentException("category and value are required");
        }
        String category = r.category().trim().toUpperCase();
        if (repo.existsByCategoryAndValue(category, r.value().trim())) {
            throw new IllegalArgumentException("'" + r.value() + "' already exists in " + category);
        }
        Lookup l = new Lookup();
        l.setCategory(category);
        l.setValue(r.value().trim());
        l.setCreatedAt(LocalDateTime.now());
        apply(l, r);
        return repo.save(l);
    }

    /** The value text is immutable (records store it); deactivate and add a new one to rename. */
    @Transactional
    public Lookup update(Long id, LookupRequest r) {
        Lookup l = repo.findById(id).orElseThrow(() -> new NotFoundException("Lookup", id));
        apply(l, r);
        return l;
    }

    private static void apply(Lookup l, LookupRequest r) {
        if (r.sortOrder() != null) {
            l.setSortOrder(r.sortOrder());
        }
        if (r.tone() != null) {
            if (!TONES.contains(r.tone())) {
                throw new IllegalArgumentException("tone must be one of " + TONES);
            }
            l.setTone(r.tone());
        }
        if (r.active() != null) {
            l.setActive(r.active());
        }
    }
}
