package com.techcoder.sqlperf.settings;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.techcoder.sqlperf.audit.AuditService;
import com.techcoder.sqlperf.common.CurrentUser;
import com.techcoder.sqlperf.common.Texts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workflow settings that administrators change at runtime (Administration → Workflow settings). Each has a
 * default; a saved value overrides it. Read on every use (a few rows, cached for a few seconds), so a change applies
 * at once without a restart.
 */
@Service
public class WorkflowSettings {

    /** Type of a setting, for validation and the admin form. */
    public enum Kind { NUMBER, BOOLEAN, TEXT, EMAILS }

    /** One setting: key, label and help for the admin form, default. */
    public record Definition(String key, String group, String label, String help, Kind kind, String defaultValue) {
    }

    public static final String BAD_MIN_MINUTES = "bad.min-minutes";
    public static final String CANDIDATE_MIN_RUNS = "candidate.min-runs";
    public static final String AUTO_ENABLED = "auto.enabled";
    public static final String AUTO_MAX_PER_RUN = "auto.max-per-run";
    public static final String ADOPTION_THRESHOLD_PCT = "adoption.threshold-pct";
    public static final String EMAIL_ENABLED = "email.enabled";
    public static final String EMAIL_ADOPTION_TO = "email.adoption-recipients";
    public static final String EMAIL_TUNING_TO = "email.tuning-recipients";
    public static final String FOLLOW_UP_WEEKS = "follow-up.weeks";
    public static final String ADHOC_CLOSE_DAYS = "adhoc.close-days";
    public static final String APP_BASE_URL = "app.base-url";

    public static final List<Definition> DEFINITIONS = List.of(
            new Definition(BAD_MIN_MINUTES, "Bad queries", "Bad query when longer than (minutes)",
                    "A query that runs longer than this is a bad query; shorter ones are normal. Query logs, Query "
                            + "groups and the dashboards show bad queries by default.", Kind.NUMBER, "5"),
            new Definition(CANDIDATE_MIN_RUNS, "Bad queries", "Tune a pattern after this many bad runs",
                    "A pattern becomes a tuning candidate once it had at least this many bad runs (seen more than "
                            + "once). Patterns with a single bad run are Adhoc-only.", Kind.NUMBER, "2"),
            new Definition(ADHOC_CLOSE_DAYS, "Bad queries", "Close Adhoc-only patterns after (days)",
                    "An Adhoc-only pattern that has not run again for this many days is Adhoc-only – closed.",
                    Kind.NUMBER, "45"),
            new Definition(AUTO_ENABLED, "Automatic first run", "Run the first tuning round automatically",
                    "New candidates are tracked, their diagnostics captured, the optimization agent run and its "
                            + "iteration tested without anyone clicking; the result is routed by the threshold below.",
                    Kind.BOOLEAN, "true"),
            new Definition(AUTO_MAX_PER_RUN, "Automatic first run", "Candidates tracked per import (max)",
                    "Upper bound of new candidates tracked automatically after one import (largest run time first).",
                    Kind.NUMBER, "25"),
            new Definition(ADOPTION_THRESHOLD_PCT, "Routing", "Send to users when faster by more than (%)",
                    "A tested iteration faster than the original by more than this (with matching results) is ready "
                            + "for adoption and goes to the users; otherwise it goes to the tuning team.",
                    Kind.NUMBER, "15"),
            new Definition(EMAIL_ENABLED, "E-mail", "Send e-mails",
                    "Off: notifications are still written to the outbox (Administration → Notifications) but not sent.",
                    Kind.BOOLEAN, "false"),
            new Definition(EMAIL_ADOPTION_TO, "E-mail", "Users e-mail group (ready for adoption)",
                    "Comma-separated addresses that get the 'ready for adoption' e-mail.", Kind.EMAILS, ""),
            new Definition(EMAIL_TUNING_TO, "E-mail", "Tuning team e-mail group",
                    "Comma-separated addresses that get the 'needs review' e-mail when the gain is below the threshold.",
                    Kind.EMAILS, ""),
            new Definition(FOLLOW_UP_WEEKS, "Follow-up", "Follow up when not adopted after (weeks)",
                    "Items waiting for adoption longer than this appear on the Client Development follow-up list.",
                    Kind.NUMBER, "3"),
            new Definition(APP_BASE_URL, "E-mail", "Link base URL in e-mails",
                    "Address of this application, used for the links in e-mails.", Kind.TEXT, "http://localhost:4200"));

    private static final Map<String, Definition> BY_KEY = DEFINITIONS.stream()
            .collect(Collectors.toMap(Definition::key, Function.identity()));
    private static final long CACHE_MILLIS = 5_000;

    private final SettingRepository repo;
    private final AuditService audit;
    private volatile Map<String, String> cache;
    private volatile long cachedAt;

    private final org.springframework.core.env.Environment env;

    public WorkflowSettings(SettingRepository repo, AuditService audit, org.springframework.core.env.Environment env) {
        this.repo = repo;
        this.audit = audit;
        this.env = env;
    }

    /** The code default, unless the deployment overrides it with the property {@code spt.settings.<key>}. */
    private String defaultOf(Definition d) {
        return env.getProperty("spt.settings." + d.key(), d.defaultValue());
    }

    /** Current value of every setting (saved or default), in form order. */
    public record Value(Definition definition, String value, boolean isDefault) {
    }

    @Transactional(readOnly = true)
    public List<Value> all() {
        Map<String, String> saved = saved();
        return DEFINITIONS.stream().map(d -> new Value(d, saved.getOrDefault(d.key(), defaultOf(d)),
                !saved.containsKey(d.key()))).toList();
    }

    /** Saves the given keys (blank = back to the default); unknown keys and invalid values are rejected. */
    @Transactional
    public List<Value> save(Map<String, String> values) {
        Map<String, String> before = saved();
        for (Map.Entry<String, String> e : values.entrySet()) {
            Definition d = BY_KEY.get(e.getKey());
            if (d == null) {
                throw new IllegalArgumentException("Unknown setting " + e.getKey());
            }
            String v = e.getValue() == null ? "" : e.getValue().trim();
            validate(d, v);
            String old = before.getOrDefault(d.key(), defaultOf(d));
            if (v.isEmpty()) {
                repo.deleteById(d.key());
            } else {
                Setting s = repo.findById(d.key()).orElseGet(Setting::new);
                s.setKey(d.key());
                s.setValue(v);
                s.setUpdatedAt(LocalDateTime.now());
                s.setUpdatedBy(CurrentUser.name());
                repo.save(s);
            }
            String now = v.isEmpty() ? defaultOf(d) : v;
            if (!now.equals(old)) {
                audit.changed("SETTING", 0L, d.key(), old, now);
            }
        }
        cache = null;
        return all();
    }

    private static void validate(Definition d, String v) {
        if (v.isEmpty()) {
            return;
        }
        switch (d.kind()) {
            case NUMBER -> {
                try {
                    if (Double.parseDouble(v) < 0) {
                        throw new IllegalArgumentException(d.label() + " cannot be negative");
                    }
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException(d.label() + " must be a number");
                }
            }
            case BOOLEAN -> {
                if (!v.equals("true") && !v.equals("false")) {
                    throw new IllegalArgumentException(d.label() + " must be true or false");
                }
            }
            case EMAILS -> {
                for (String a : emails(v)) {
                    if (!a.matches("[^@\\s]+@[^@\\s]+")) {
                        throw new IllegalArgumentException("Not an e-mail address: " + a);
                    }
                }
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ typed access

    public double badMinMinutes() {
        return number(BAD_MIN_MINUTES);
    }

    public int candidateMinRuns() {
        return (int) number(CANDIDATE_MIN_RUNS);
    }

    public int adhocCloseDays() {
        return (int) number(ADHOC_CLOSE_DAYS);
    }

    public boolean autoEnabled() {
        return bool(AUTO_ENABLED);
    }

    public int autoMaxPerRun() {
        return (int) number(AUTO_MAX_PER_RUN);
    }

    public double adoptionThresholdPct() {
        return number(ADOPTION_THRESHOLD_PCT);
    }

    public boolean emailEnabled() {
        return bool(EMAIL_ENABLED);
    }

    public List<String> adoptionRecipients() {
        return emails(text(EMAIL_ADOPTION_TO));
    }

    public List<String> tuningRecipients() {
        return emails(text(EMAIL_TUNING_TO));
    }

    public int followUpWeeks() {
        return (int) number(FOLLOW_UP_WEEKS);
    }

    public String appBaseUrl() {
        String u = text(APP_BASE_URL);
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    public String text(String key) {
        String v = saved().get(key);
        return v != null ? v : defaultOf(BY_KEY.get(key));
    }

    private double number(String key) {
        return Double.parseDouble(text(key));
    }

    private boolean bool(String key) {
        return Boolean.parseBoolean(text(key));
    }

    /** Splits a comma / semicolon / space separated list of addresses. */
    public static List<String> emailList(String v) {
        return emails(v);
    }

    static List<String> emails(String v) {
        return Texts.isBlank(v) ? List.of()
                : Arrays.stream(v.split("[,;\\s]+")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private Map<String, String> saved() {
        Map<String, String> c = cache;
        if (c == null || System.currentTimeMillis() - cachedAt > CACHE_MILLIS) {
            Map<String, String> m = new LinkedHashMap<>();
            repo.findAll().forEach(s -> m.put(s.getKey(), s.getValue()));
            cache = c = m;
            cachedAt = System.currentTimeMillis();
        }
        return c;
    }
}
