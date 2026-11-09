package it.scandeletefiles.platform;

public record TrashResult(Status status, String detail) {
    public enum Status {
        MOVED,
        UNAVAILABLE,
        FAILED
    }

    public TrashResult {
        if (status == null) {
            throw new IllegalArgumentException("status obbligatorio");
        }
        detail = detail == null ? "" : detail;
    }
}
