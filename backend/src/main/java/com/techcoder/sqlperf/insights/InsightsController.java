package com.techcoder.sqlperf.insights;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only analytics behind the Command Center and Insights screens. Dates are ISO (yyyy-MM-dd). */
@RestController
@RequestMapping("/api/insights")
public class InsightsController {

    private final InsightsService service;

    public InsightsController(InsightsService service) {
        this.service = service;
    }

    /** Q1, Q2, Q3, Q8: bad queries of one day (default today). */
    @GetMapping("/daily")
    public InsightsService.Daily daily(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.daily(date == null ? LocalDate.now() : date);
    }

    /** Q4 (prior day), Q6, Q9, Q15. */
    @GetMapping("/pipeline")
    public InsightsService.Pipeline pipeline(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate priorDay,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate today = LocalDate.now();
        return service.pipeline(priorDay == null ? today.minusDays(1) : priorDay,
                from == null ? today.minusDays(89) : from, to == null ? today : to);
    }

    /** Q5, Q11: adoption and savings for a period (default month to date). */
    @GetMapping("/savings")
    public InsightsService.Savings savings(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate today = LocalDate.now();
        return service.savings(from == null ? today.withDayOfMonth(1) : from, to == null ? today : to);
    }

    /** Q12: outstanding themes ranked by potential monthly savings. */
    @GetMapping("/themes")
    public List<InsightsService.ThemePriority> themes(@RequestParam(defaultValue = "30") int days) {
        return service.themePriorities(Math.clamp(days, 1, 365));
    }

    /** Q7, Q13: bad queries by user and user group. */
    @GetMapping("/users")
    public InsightsService.Users users(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate today = LocalDate.now();
        return service.users(from == null ? today.minusDays(29) : from, to == null ? today : to);
    }

    /** Q10, Q14: month over month trend and tuning request sources. */
    @GetMapping("/trends")
    public InsightsService.Trends trends(@RequestParam(defaultValue = "12") int months) {
        return service.trends(Math.clamp(months, 2, 36));
    }
}
