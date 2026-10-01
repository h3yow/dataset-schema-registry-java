package com.github.h3yow.datasetschemaregistryjava.model;

import java.util.List;

/**
 * 兼容性判定结果。
 *
 * @param compatible 是否兼容
 * @param conflicts  冲突列表（兼容时为空）
 */
public record CompatibilityResult(boolean compatible, List<CompatibilityConflict> conflicts) {

    public static CompatibilityResult ok() {
        return new CompatibilityResult(true, List.of());
    }

    public static CompatibilityResult incompatible(List<CompatibilityConflict> conflicts) {
        return new CompatibilityResult(false, List.copyOf(conflicts));
    }
}
