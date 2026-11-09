package it.scandeletefiles.application;

public record OperationProgress(long completed, long total, String message) {
    public OperationProgress {
        if (completed < 0 || total < 0 || completed > total) {
            throw new IllegalArgumentException("Avanzamento non valido");
        }
        message = message == null ? "" : message;
    }
}
