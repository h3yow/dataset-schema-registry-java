package com.github.h3yow.datasetschemaregistryjava.exception;

/** 引用了不存在的父版本。 */
public class ParentVersionNotFoundException extends RegistryException {
    public ParentVersionNotFoundException(String dataset, Integer parentVersion) {
        super("PARENT_VERSION_NOT_FOUND",
                "父版本不存在: dataset=" + dataset + ", parentVersion=" + parentVersion);
    }
}
