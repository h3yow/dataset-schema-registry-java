package com.github.h3yow.datasetschemaregistryjava.web.dto;

/** 字段定义入参。 */
public record FieldDto(String name, String type, Boolean nullable, String comment) {
}
