package com.techcoder.sqlperf.diagnostics;

import com.techcoder.sqlperf.tracker.TrackerDto;
import com.techcoder.sqlperf.tracker.TrackerService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Starts the automatic explain / profile / DDL capture again for a tracker item; progress is on the item itself. */
@RestController
@RequestMapping("/api/tracker/{id}/capture")
public class DiagnosticsCaptureController {

    private final DiagnosticsCaptureService capture;
    private final TrackerService trackers;

    public DiagnosticsCaptureController(DiagnosticsCaptureService capture, TrackerService trackers) {
        this.capture = capture;
        this.trackers = trackers;
    }

    @PostMapping
    public TrackerDto start(@PathVariable Long id) {
        capture.request(id);
        return trackers.get(id);
    }
}
