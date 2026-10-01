package com.github.h3yow.datasetschemaregistryjava.model;

/**
 * 一处兼容性冲突。
 *
 * @param path    冲突位置（字段名）
 * @param kind    冲突类型（FIELD_REMOVED / TYPE_CHANGED / NULLABILITY_NARROWED / REQUIRED_FIELD_ADDED）
 * @param message 人类可读的冲突说明
 */
public record CompatibilityConflict(String path, String kind, String message) {
}
