package com.github.h3yow.datasetschemaregistryjava.model;

/** 一处不兼容冲突的定位与说明。 */
public record CompatibilityConflict(String path, String reason, String detail) {
}
