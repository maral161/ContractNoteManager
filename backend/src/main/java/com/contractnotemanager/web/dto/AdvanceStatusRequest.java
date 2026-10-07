package com.contractnotemanager.web.dto;

import com.contractnotemanager.domain.OrderStatus;

import jakarta.validation.constraints.NotNull;

/** The status the user saw, so that a double click cannot skip a step. */
public record AdvanceStatusRequest(@NotNull OrderStatus expectedStatus) {
}
