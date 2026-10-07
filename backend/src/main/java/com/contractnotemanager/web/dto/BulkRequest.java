package com.contractnotemanager.web.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record BulkRequest(@NotEmpty List<Long> ids, @NotNull Action action) {
    public enum Action {
        DELETE, ADVANCE_STATUS
    }
}
