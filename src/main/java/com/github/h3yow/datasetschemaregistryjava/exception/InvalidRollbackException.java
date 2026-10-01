package com.github.h3yow.datasetschemaregistryjava.exception;

/** 无效的回滚请求。 */
public class InvalidRollbackException extends RegistryException {
    public InvalidRollbackException(String message) {
        super("INVALID_ROLLBACK", message);
    }
}
