package com.github.h3yow.datasetschemaregistryjava.controller;

import com.github.h3yow.datasetschemaregistryjava.exception.RegistryException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 把业务异常映射为带错误码的 HTTP 响应，保证失败原因可互相区分。
 */
@RestControllerAdvice
public class ApiErrorHandler {

    @ExceptionHandler(RegistryException.class)
    public ResponseEntity<Map<String, Object>> handle(RegistryException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case DATASET_NOT_FOUND, VERSION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DUPLICATE_VERSION, IDENTICAL_CONTENT, UNKNOWN_PARENT_VERSION,
                 INCOMPATIBLE_SCHEMA, BROKEN_CHAIN, INVALID_ROLLBACK -> HttpStatus.CONFLICT;
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of(
                "code", ex.getCode().name(),
                "message", ex.getMessage()));
    }
}
