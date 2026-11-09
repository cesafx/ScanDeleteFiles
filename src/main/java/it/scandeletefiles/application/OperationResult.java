package it.scandeletefiles.application;

import java.util.Objects;

public record OperationResult(OperationStatus status, String message) {
    public OperationResult {
        Objects.requireNonNull(status, "status");
        message = message == null ? "" : message;
    }
}
