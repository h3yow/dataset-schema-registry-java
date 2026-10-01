package com.github.h3yow.datasetschemaregistryjava.controller;

import com.github.h3yow.datasetschemaregistryjava.model.SchemaField;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaVersion;
import com.github.h3yow.datasetschemaregistryjava.service.SchemaRegistryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * schema 注册与查询的 REST 入口。
 */
@RestController
@RequestMapping("/api/datasets/{dataset}/schemas")
public class SchemaRegistryController {

    private final SchemaRegistryService service;

    public SchemaRegistryController(SchemaRegistryService service) {
        this.service = service;
    }

    public record RegisterRequest(int version, Integer parentVersion, List<SchemaField> fields) {
    }

    public record RollbackRequest(int targetVersion, int newVersion) {
    }

    @PostMapping
    public SchemaVersion register(@PathVariable String dataset, @RequestBody RegisterRequest request) {
        return service.register(dataset, request.version(), request.parentVersion(), request.fields());
    }

    @PostMapping("/rollback")
    public SchemaVersion rollback(@PathVariable String dataset, @RequestBody RollbackRequest request) {
        return service.rollback(dataset, request.targetVersion(), request.newVersion());
    }

    @GetMapping
    public List<SchemaVersion> list(@PathVariable String dataset) {
        return service.list(dataset);
    }

    @GetMapping("/latest")
    public SchemaVersion latest(@PathVariable String dataset) {
        return service.getLatest(dataset);
    }

    @GetMapping("/{version}")
    public SchemaVersion get(@PathVariable String dataset, @PathVariable int version) {
        return service.get(dataset, version);
    }
}
