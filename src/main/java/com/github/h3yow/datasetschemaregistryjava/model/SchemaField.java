package com.github.h3yow.datasetschemaregistryjava.model;

/** 字段定义：名称、类型、是否可空、备注。 */
public record SchemaField(String name, FieldType type, boolean nullable, String comment) {
}
