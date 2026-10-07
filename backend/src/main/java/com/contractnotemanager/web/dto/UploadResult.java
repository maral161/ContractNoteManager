package com.contractnotemanager.web.dto;

/** Result of one dropped PDF. */
public record UploadResult(String fileName, Outcome outcome, Long noteId, Long orderId, String orderLabel,
        String message) {

    public enum Outcome {
        MATCHED, UNMATCHED, EXTRACTION_FAILED, DUPLICATE, INVALID_FILE
    }
}
