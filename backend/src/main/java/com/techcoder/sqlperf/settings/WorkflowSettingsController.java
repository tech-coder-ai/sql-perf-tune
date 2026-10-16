package com.techcoder.sqlperf.settings;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administration → Workflow settings. */
@RestController
@RequestMapping("/api/settings")
public class WorkflowSettingsController {

    private final WorkflowSettings settings;

    public WorkflowSettingsController(WorkflowSettings settings) {
        this.settings = settings;
    }

    @GetMapping
    public List<WorkflowSettings.Value> all() {
        return settings.all();
    }

    /** Body: {key: value}; an empty value restores the default. */
    @PutMapping
    public List<WorkflowSettings.Value> save(@RequestBody Map<String, String> values) {
        return settings.save(values);
    }
}
