package com.github.h3yow.datasetschemaregistryjava.exception;

/** 版本号已存在（冲突拒绝，不能静默覆盖）。 */
public class DuplicateVersionException extends RegistryException {
    public DuplicateVersionException(String dataset, int version) {
        super("DUPLICATE_VERSION",
                "版本号已存在: dataset=" + dataset + ", version=" + version);
    }
}
