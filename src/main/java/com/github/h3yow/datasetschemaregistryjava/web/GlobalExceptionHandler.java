package com.github.h3yow.datasetschemaregistryjava.web;

import com.github.h3yow.datasetschemaregistryjava.exception.DatasetNotFoundException;
import com.github.h3yow.datasetschemaregistryjava.exception.DuplicateSchemaException;
import com.github.h3yow.datasetschemaregistryjava.exception.DuplicateVersionException;
import com.github.h3yow.datasetschemaregistryjava.exception.IncompatibleSchemaException;
import com.github.h3yow.datasetschemaregistryjava.exception.ParentVersionNotFoundException;
import com.github.h3yow.datasetschemaregistryjava.exception.RegistryException;
import com.github.h3yow.datasetschemaregistryjava.exception.VersionNotFoundException;
import com.github.h3yow.datasetschemaregistryjava.model.CompatibilityConflict;
import com.github.h3yow.datasetschemaregistryjava.web.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/** 把业务异常翻译成稳定、可区分的 HTTP 状态码与错误码。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DuplicateVersionException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateVersion(DuplicateVersionException ex) {
        return build(HttpStatus.CONFLICT, ex, null);
    }

    @ExceptionHandler(DuplicateSchemaException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateSchema(DuplicateSchemaException ex) {
        return build(HttpStatus.CONFLICT, ex, null);
    }

    @ExceptionHandler(IncompatibleSchemaException.class)
    public ResponseEntity<ErrorResponse> handleIncompatible(IncompatibleSchemaException ex) {
        List<String> conflicts = ex.getConflicts().stream()
                .map(this::formatConflict)
                .toList();
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex, conflicts);
    }

    @ExceptionHandler({ParentVersionNotFoundException.class, VersionNotFoundException.class,
            DatasetNotFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(RegistryException ex) {
        return build(HttpStatus.NOT_FOUND, ex, null);
    }

    @ExceptionHandler(RegistryException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(RegistryException ex) {
        // INVALID_EVOLUTION_CHAIN / INVALID_ROLLBACK / INVALID_SCHEMA
        return build(HttpStatus.BAD_REQUEST, ex, null);
    }

    private String formatConflict(CompatibilityConflict c) {
        return "位置=" + c.path() + ", 类型=" + c.reason() + ", 说明=" + c.detail();
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, RegistryException ex,
                                                List<String> conflicts) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(ex.getErrorCode(), ex.getMessage(),
                        conflicts == null ? List.of() : conflicts));
    }
}
