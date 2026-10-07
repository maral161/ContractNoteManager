package com.contractnotemanager.domain;

/** Result of matching a contract note with the orders (plan 4.4.2). */
public enum ContractNoteStatus {
    /** All six checks pass (settlement amount within ±1): the order is confirmed. */
    MATCHED,
    /** ISIN and Buy/Sell agree and 4–5 of the 6 checks pass: linked to the order, which can take the note's values. */
    PARTIALLY_MATCHED,
    /** ISIN or Buy/Sell differ, or 3 or fewer checks pass. */
    NO_MATCH,
    /** The PDF could not be read. */
    EXTRACTION_FAILED
}
