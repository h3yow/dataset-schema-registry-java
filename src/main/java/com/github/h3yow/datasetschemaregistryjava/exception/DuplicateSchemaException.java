package com.github.h3yow.datasetschemaregistryjava.exception;

/** 内容与已有版本完全相同。 */
public class DuplicateSchemaException extends RegistryException {
    public DuplicateSchemaException(String dataset, String contentHash) {
        super("DUPLICATE_SCHEMA",
                "schema 内容与已有版本完全相同: dataset=" + dataset + ", hash=" + contentHash);
    }
}
