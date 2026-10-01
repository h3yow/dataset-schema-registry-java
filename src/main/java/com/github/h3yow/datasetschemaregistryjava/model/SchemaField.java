package com.github.h3yow.datasetschemaregistryjava.model;

import java.util.Objects;

/**
 * 数据集 schema 中的单个字段定义。
 *
 * @param name        字段名（同一 schema 内唯一）
 * @param type        字段类型
 * @param nullable    是否允许为空
 * @param defaultValue 默认值（无默认值为 null）
 */
public record SchemaField(String name, FieldType type, boolean nullable, String defaultValue) {

    public SchemaField {
        Objects.requireNonNull(name, "field name must not be null");
        Objects.requireNonNull(type, "field type must not be null");
    }
}
