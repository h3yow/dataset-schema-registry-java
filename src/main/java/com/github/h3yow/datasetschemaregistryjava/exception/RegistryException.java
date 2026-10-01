package com.github.h3yow.datasetschemaregistryjava.exception;

/** 注册中心业务异常基类，携带可解释的错误码。 */
public class RegistryException extends RuntimeException {

    private final String errorCode;

    public RegistryException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
