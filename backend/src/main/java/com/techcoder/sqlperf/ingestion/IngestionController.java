package com.techcoder.sqlperf.ingestion;

import java.io.IOException;
import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/ingestion")
public class IngestionController {

    private final IngestionService service;
    private final IngestionBatchRepository batches;
    private final SourceFileRepository files;

    public IngestionController(IngestionService service, IngestionBatchRepository batches, SourceFileRepository files) {
        this.service = service;
        this.batches = batches;
        this.files = files;
    }

    /** Upload a CSV / Excel query log. */
    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestionBatch upload(@RequestPart("file") MultipartFile file,
                                 @RequestParam(required = false) String sqlEngine) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("The uploaded file is empty");
        }
        return service.submitFile(file.getOriginalFilename(), file, sqlEngine);
    }

    /** Pull new rows from a configured Oracle / Impala data source. */
    @PostMapping("/pull/{dataSourceId}")
    public IngestionBatch pull(@PathVariable Long dataSourceId) {
        return service.submitPull(dataSourceId);
    }

    @GetMapping("/batches")
    public List<IngestionBatch> batches() {
        return batches.findAll(Sort.by(Sort.Direction.DESC, "id"));
    }

    /** Progress / result of one import (poll while status is RUNNING). */
    @GetMapping("/batches/{id}")
    public IngestionBatch batch(@PathVariable Long id) {
        return batches.findById(id).orElseThrow(() -> new com.techcoder.sqlperf.common.NotFoundException("Import", id));
    }

    /** Stops a running import after the current row; what was loaded so far is kept and grouped. */
    @PostMapping("/batches/{id}/cancel")
    public IngestionBatch cancel(@PathVariable Long id) {
        return service.cancel(id);
    }

    /** Distinct files loaded so far with their load counts. */
    @GetMapping("/files")
    public List<SourceFile> files() {
        return files.findAll(Sort.by(Sort.Direction.DESC, "lastLoadedAt"));
    }
}
