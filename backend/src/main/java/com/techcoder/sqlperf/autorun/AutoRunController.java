package com.techcoder.sqlperf.autorun;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administration → Workflow settings → "Run now": track the current candidates and start their first round. */
@RestController
@RequestMapping("/api/auto-run")
public class AutoRunController {

    private final AutoRunService service;

    public AutoRunController(AutoRunService service) {
        this.service = service;
    }

    @PostMapping
    public AutoRunService.TrackResult run() {
        return service.trackCandidates();
    }

    public record ThemeFixRequest(String theme, String notes) {
    }

    /** Step 15: the theme's fix is released; retry every SQL waiting for it. */
    @PostMapping("/theme-fixes")
    public ThemeFix releaseThemeFix(@RequestBody ThemeFixRequest req) {
        return service.releaseThemeFix(req.theme(), req.notes());
    }

    @GetMapping("/theme-fixes")
    public java.util.List<ThemeFix> themeFixes() {
        return service.themeFixes();
    }
}
