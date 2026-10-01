package com.github.h3yow.datasetschemaregistryjava.exception;

/** schema 定义本身非法（字段为空、重名字段等）。 */
public class InvalidSchemaException extends RegistryException {
    public InvalidSchemaException(String message) {
        super("INVALID_SCHEMA", message);
    }
}
