package com.github.h3yow.datasetschemaregistryjava.model;

import java.util.List;

/** 兼容性判定结论。 */
public record CompatibilityResult(boolean compatible, List<CompatibilityConflict> conflicts) {
}
