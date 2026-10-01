package com.github.h3yow.datasetschemaregistryjava.exception;

import com.github.h3yow.datasetschemaregistryjava.model.CompatibilityConflict;

import java.util.List;

/** 新版本相对历史版本存在破坏性变更。 */
public class IncompatibleSchemaException extends RegistryException {

    private final transient List<CompatibilityConflict> conflicts;

    public IncompatibleSchemaException(List<CompatibilityConflict> conflicts) {
        super("INCOMPATIBLE_SCHEMA", "schema 存在不兼容变更: " + conflicts);
        this.conflicts = List.copyOf(conflicts);
    }

    public List<CompatibilityConflict> getConflicts() {
        return conflicts;
    }
}
