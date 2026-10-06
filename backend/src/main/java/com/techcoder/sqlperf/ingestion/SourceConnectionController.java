package com.techcoder.sqlperf.ingestion;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.techcoder.sqlperf.common.CurrentUser;
import com.techcoder.sqlperf.common.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/data-sources")
public class SourceConnectionController {

    private final SourceConnectionRepository repo;
    private final JdbcLogPuller puller;

    public SourceConnectionController(SourceConnectionRepository repo, JdbcLogPuller puller) {
        this.repo = repo;
        this.puller = puller;
    }

    public record SourceRequest(@NotBlank String name, @NotNull SourceConnection.SourceType sourceType,
                                String sqlEngine, @NotBlank String jdbcUrl, String driverClass, String username,
                                String passwordRef, @NotBlank String logQuery, Integer fetchSize, Boolean active,
                                String description) {
    }

    @GetMapping
    public List<SourceConnection> list() {
        return repo.findAll(Sort.by("name"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public SourceConnection create(@Valid @RequestBody SourceRequest req) {
        SourceConnection s = new SourceConnection();
        apply(s, req);
        s.setCreatedAt(LocalDateTime.now());
        s.setCreatedBy(CurrentUser.name());
        return repo.save(s);
    }

    @PutMapping("/{id}")
    @Transactional
    public SourceConnection update(@PathVariable Long id, @Valid @RequestBody SourceRequest req) {
        SourceConnection s = repo.findById(id).orElseThrow(() -> new NotFoundException("Data source", id));
        apply(s, req);
        s.setUpdatedAt(LocalDateTime.now());
        s.setUpdatedBy(CurrentUser.name());
        return s;
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable Long id) {
        SourceConnection s = repo.findById(id).orElseThrow(() -> new NotFoundException("Data source", id));
        try {
            puller.test(s);
            return Map.of("ok", true, "message", "Connection successful");
        } catch (Exception e) {
            return Map.of("ok", false, "message", String.valueOf(e.getMessage()));
        }
    }

    private static void apply(SourceConnection s, SourceRequest r) {
        s.setName(r.name().trim());
        s.setSourceType(r.sourceType());
        s.setSqlEngine(r.sqlEngine() == null ? "IMPALA" : r.sqlEngine().trim().toUpperCase());
        s.setJdbcUrl(r.jdbcUrl().trim());
        s.setDriverClass(r.driverClass());
        s.setUsername(r.username());
        s.setPasswordRef(r.passwordRef());
        s.setLogQuery(r.logQuery());
        if (r.fetchSize() != null) {
            s.setFetchSize(r.fetchSize());
        }
        if (r.active() != null) {
            s.setActive(r.active());
        }
        s.setDescription(r.description());
    }
}
