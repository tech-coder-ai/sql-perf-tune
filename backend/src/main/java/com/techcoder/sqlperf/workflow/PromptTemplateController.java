package com.techcoder.sqlperf.workflow;

import java.time.LocalDateTime;
import java.util.List;

import com.techcoder.sqlperf.common.CurrentUser;
import com.techcoder.sqlperf.common.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Prompt templates are append-only: saving creates a new version and deactivates older ones. */
@RestController
@RequestMapping("/api/prompt-templates")
public class PromptTemplateController {

    private final PromptTemplateRepository repo;

    public PromptTemplateController(PromptTemplateRepository repo) {
        this.repo = repo;
    }

    public record TemplateRequest(@NotBlank String name, String sqlEngine, @NotBlank String templateText, String notes) {
    }

    @GetMapping
    public List<PromptTemplate> list() {
        return repo.findAllByOrderByNameAscVersionNoDesc();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public PromptTemplate saveVersion(@Valid @RequestBody TemplateRequest req) {
        String name = req.name().trim();
        int next = repo.findFirstByNameOrderByVersionNoDesc(name).map(p -> p.getVersionNo() + 1).orElse(1);
        repo.findAllByOrderByNameAscVersionNoDesc().stream()
                .filter(p -> p.getName().equals(name))
                .forEach(p -> p.setActive(false));
        PromptTemplate t = new PromptTemplate();
        t.setName(name);
        t.setSqlEngine(req.sqlEngine() == null ? "IMPALA" : req.sqlEngine().trim().toUpperCase());
        t.setVersionNo(next);
        t.setTemplateText(req.templateText());
        t.setNotes(req.notes());
        t.setActive(true);
        t.setCreatedAt(LocalDateTime.now());
        t.setCreatedBy(CurrentUser.name());
        return repo.save(t);
    }

    @PostMapping("/{id}/activate")
    @Transactional
    public PromptTemplate activate(@PathVariable Long id) {
        PromptTemplate t = repo.findById(id).orElseThrow(() -> new NotFoundException("Prompt template", id));
        repo.findAllByOrderByNameAscVersionNoDesc().stream()
                .filter(p -> p.getName().equals(t.getName()))
                .forEach(p -> p.setActive(p.getId().equals(id)));
        return t;
    }
}
