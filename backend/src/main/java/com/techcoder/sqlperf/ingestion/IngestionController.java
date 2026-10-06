package com.techcoder.sqlperf.ingestion;

import java.io.IOException;
import java.io.InputStream;
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

    public IngestionController(IngestionService service, IngestionBatchRepository batches) {
        this.service = service;
        this.batches = batches;
    }

    /** Upload a CSV / Excel query log. */
    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestionBatch upload(@RequestPart("file") MultipartFile file,
                                 @RequestParam(required = false) String sqlEngine) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("The uploaded file is empty");
        }
        try (InputStream in = file.getInputStream()) {
            return service.importFile(file.getOriginalFilename(), in, sqlEngine);
        }
    }

    /** Pull new rows from a configured Oracle / Impala data source. */
    @PostMapping("/pull/{dataSourceId}")
    public IngestionBatch pull(@PathVariable Long dataSourceId) {
        return service.pull(dataSourceId);
    }

    @GetMapping("/batches")
    public List<IngestionBatch> batches() {
        return batches.findAll(Sort.by(Sort.Direction.DESC, "id"));
    }
}
