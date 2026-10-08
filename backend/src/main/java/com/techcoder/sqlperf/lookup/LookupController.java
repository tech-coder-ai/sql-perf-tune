package com.techcoder.sqlperf.lookup;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/lookups")
public class LookupController {

    private final LookupService service;

    public LookupController(LookupService service) {
        this.service = service;
    }

    /** category -> values (active and inactive; the UI offers only active ones for new input). */
    @GetMapping
    public Map<String, List<Lookup>> all() {
        return service.all();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Lookup create(@RequestBody LookupService.LookupRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public Lookup update(@PathVariable Long id, @RequestBody LookupService.LookupRequest req) {
        return service.update(id, req);
    }
}
