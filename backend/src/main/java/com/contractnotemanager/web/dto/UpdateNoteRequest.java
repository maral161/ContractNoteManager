package com.contractnotemanager.web.dto;

/** Corrections of the values read from an unmatched contract note (as text, parsed like Claude's answer). */
public record UpdateNoteRequest(
        String instrumentName,
        String isin,
        String currency,
        String quantity,
        String price,
        String settlementAmount,
        String broker,
        String commission,
        String side) {
}
