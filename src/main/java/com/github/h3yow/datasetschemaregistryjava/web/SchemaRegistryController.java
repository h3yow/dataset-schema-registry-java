package com.github.h3yow.datasetschemaregistryjava.web;

import com.github.h3yow.datasetschemaregistryjava.exception.InvalidSchemaException;
import com.github.h3yow.datasetschemaregistryjava.model.FieldType;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaDefinition;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaField;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaVersion;
import com.github.h3yow.datasetschemaregistryjava.service.SchemaRegistryService;
import com.github.h3yow.datasetschemaregistryjava.web.dto.FieldDto;
import com.github.h3yow.datasetschemaregistryjava.web.dto.RegisterSchemaRequest;
import com.github.h3yow.datasetschemaregistryjava.web.dto.RollbackRequest;
import com.github.h3yow.datasetschemaregistryjava.web.dto.SchemaVersionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * Schema 注册 REST 接口。
 *
 * <ul>
 *   <li>POST   /api/datasets/{dataset}/versions          注册新版本</li>
 *   <li>GET    /api/datasets/{dataset}/versions          列出全部版本</li>
 *   <li>GET    /api/datasets/{dataset}/versions/latest   查询当前生效版本</li>
 *   <li>GET    /api/datasets/{dataset}/versions/{v}      查询指定版本</li>
 *   <li>POST   /api/datasets/{dataset}/versions/rollback 回滚 head 指针</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/datasets/{dataset}/versions")
public class SchemaRegistryController {

    private final SchemaRegistryService service;

    public SchemaRegistryController(SchemaRegistryService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<SchemaVersionResponse> register(@PathVariable String dataset,
                                                          @RequestBody RegisterSchemaRequest request) {
        SchemaVersion saved = service.register(dataset, request.version(),
                request.parentVersion(), toDefinition(request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(toResponse(saved, saved.version()));
    }

    @GetMapping
    public List<SchemaVersionResponse> list(@PathVariable String dataset) {
        int head = service.getLatest(dataset).version();
        return service.listVersions(dataset).stream()
                .map(v -> toResponse(v, head))
                .toList();
    }

    @GetMapping("/latest")
    public SchemaVersionResponse getLatest(@PathVariable String dataset) {
        SchemaVersion latest = service.getLatest(dataset);
        return toResponse(latest, latest.version());
    }

    @GetMapping("/{version}")
    public SchemaVersionResponse getVersion(@PathVariable String dataset,
                                            @PathVariable int version) {
        SchemaVersion target = service.getVersion(dataset, version);
        return toResponse(target, service.getLatest(dataset).version());
    }

    @PostMapping("/rollback")
    public SchemaVersionResponse rollback(@PathVariable String dataset,
                                          @RequestBody RollbackRequest request) {
        if (request == null || request.version() == null) {
            throw new InvalidSchemaException("回滚请求必须指定 version");
        }
        SchemaVersion target = service.rollback(dataset, request.version());
        return toResponse(target, target.version());
    }

    private SchemaDefinition toDefinition(RegisterSchemaRequest request) {
        if (request == null || request.fields() == null) {
            throw new InvalidSchemaException("注册请求必须包含 fields");
        }
        List<SchemaField> fields = request.fields().stream()
                .map(dto -> new SchemaField(dto.name(), parseType(dto),
                        Optional.ofNullable(dto.nullable()).orElse(false), dto.comment()))
                .toList();
        return new SchemaDefinition(fields);
    }

    private FieldType parseType(FieldDto dto) {
        if (dto.type() == null) {
            throw new InvalidSchemaException("字段 [" + dto.name() + "] 缺少类型");
        }
        try {
            return FieldType.valueOf(dto.type().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidSchemaException("字段 [" + dto.name() + "] 类型非法: " + dto.type()
                    + "，支持: " + List.of(FieldType.values()));
        }
    }

    private SchemaVersionResponse toResponse(SchemaVersion version, int headVersion) {
        List<FieldDto> fields = version.schema().fields().stream()
                .map(f -> new FieldDto(f.name(), f.type().name(), f.nullable(), f.comment()))
                .toList();
        return new SchemaVersionResponse(version.dataset(), version.version(),
                version.parentVersion(), version.contentHash(), version.createdAt(),
                version.version() == headVersion, fields);
    }
}
