package com.github.h3yow.datasetschemaregistryjava.web.dto;

import java.util.List;

/** 统一错误响应。 */
public record ErrorResponse(String errorCode, String message, List<String> conflicts) {
}
