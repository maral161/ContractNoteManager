package com.contractnotemanager.web.dto;

import java.util.List;

public record BulkResult(int done, int skipped, List<Item> items) {
    public record Item(Long id, String label, boolean done, String message) {
    }
}
