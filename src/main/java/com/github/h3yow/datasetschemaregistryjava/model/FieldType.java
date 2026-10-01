package com.github.h3yow.datasetschemaregistryjava.model;

/**
 * 字段类型枚举。兼容性提升规则在 CompatibilityChecker 中定义。
 */
public enum FieldType {
    STRING,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    BOOLEAN,
    BYTES,
    DATE,
    TIMESTAMP
}
