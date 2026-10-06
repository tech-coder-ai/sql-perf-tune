package com.techcoder.sqlperf.customfield;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/custom-fields")
public class CustomFieldController {

    private final CustomFieldService service;

    public CustomFieldController(CustomFieldService service) {
        this.service = service;
    }

    @GetMapping
    public List<CustomField> list(@RequestParam CustomField.EntityType entityType) {
        return service.list(entityType);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomField create(@RequestBody CustomFieldService.FieldRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public CustomField update(@PathVariable Long id, @RequestBody CustomFieldService.FieldRequest req) {
        return service.update(id, req);
    }
}
