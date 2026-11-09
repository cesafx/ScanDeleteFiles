package it.scandeletefiles.application;

import java.util.Objects;
import java.util.OptionalLong;

public record OperationProgress(long completed, OptionalLong total, String message) {
    public OperationProgress {
        if (completed < 0) {
            throw new IllegalArgumentException("Il progresso completato non può essere negativo");
        }
        total = Objects.requireNonNull(total, "total");
        if (total.isPresent() && (total.getAsLong() < 0 || completed > total.getAsLong())) {
            throw new IllegalArgumentException("Avanzamento non valido");
        }
        message = message == null ? "" : message;
    }

    public static OperationProgress indeterminate(long completed, String message) {
        return new OperationProgress(completed, OptionalLong.empty(), message);
    }

    public static OperationProgress determinate(long completed, long total, String message) {
        return new OperationProgress(completed, OptionalLong.of(total), message);
    }
}
