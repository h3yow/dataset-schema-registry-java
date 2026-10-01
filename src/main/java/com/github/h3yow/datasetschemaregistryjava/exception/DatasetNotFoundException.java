package com.github.h3yow.datasetschemaregistryjava.exception;

/** 数据集不存在。 */
public class DatasetNotFoundException extends RegistryException {
    public DatasetNotFoundException(String dataset) {
        super("DATASET_NOT_FOUND", "数据集不存在: " + dataset);
    }
}
