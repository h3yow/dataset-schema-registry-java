package com.github.h3yow.datasetschemaregistryjava.exception;

/** 演进链断裂：父版本不是当前最新版本等。 */
public class InvalidEvolutionChainException extends RegistryException {
    public InvalidEvolutionChainException(String message) {
        super("INVALID_EVOLUTION_CHAIN", message);
    }
}
