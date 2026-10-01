package com.github.h3yow.datasetschemaregistryjava.exception;

/**
 * 注册中心所有业务异常的基类，携带可互相区分的错误码。
 */
public class RegistryException extends RuntimeException {

    public enum Code {
        DATASET_NOT_FOUND,
        VERSION_NOT_FOUND,
        DUPLICATE_VERSION,
        IDENTICAL_CONTENT,
        UNKNOWN_PARENT_VERSION,
        INCOMPATIBLE_SCHEMA,
        BROKEN_CHAIN,
        INVALID_ROLLBACK,
        INVALID_REQUEST
    }

    private final Code code;

    public RegistryException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
