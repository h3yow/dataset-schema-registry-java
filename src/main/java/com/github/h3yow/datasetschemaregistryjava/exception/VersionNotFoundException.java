package com.github.h3yow.datasetschemaregistryjava.exception;

/** 指定版本不存在。 */
public class VersionNotFoundException extends RegistryException {
    public VersionNotFoundException(String dataset, int version) {
        super("VERSION_NOT_FOUND", "数据集 [" + dataset + "] 不存在版本: " + version);
    }
}
