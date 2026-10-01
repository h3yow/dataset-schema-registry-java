package com.github.h3yow.datasetschemaregistryjava.model;

import java.util.List;

/** 一个 schema 的完整字段定义。 */
public record SchemaDefinition(List<SchemaField> fields) {
}
