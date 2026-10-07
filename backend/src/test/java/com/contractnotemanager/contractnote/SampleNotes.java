package com.contractnotemanager.contractnote;

import java.util.List;

/**
 * What the sample PDFs in docs/samples/contract-notes contain, in the shape Claude returns it
 * (numbers without thousands separators and without sign).
 */
public final class SampleNotes {

    private SampleNotes() {
    }

    /** Sell ABB: 1,864 × 435.52 − 1,200 = 810,609.28 ≈ 810,609.00. */
    public static final ContractNoteExtraction ABN_AMRO_ABB = new ContractNoteExtraction(
            "ABB", "CH0012221716", "SEK", "1864", "435.52", "810609.00", "ABN Amro", "1200.00", "sell", "", List.of());

    /** As printed: settlement 12,934.00 – a typo for 102,934.00, so the note does not add up. */
    public static final ContractNoteExtraction UBS_APPLE = new ContractNoteExtraction(
            "Apple Inc", "US0378331005", "USD", "473", "219.65", "12934.00", "UBS", "960.00", "sell", "", List.of());

    /** The UBS note with the settlement amount corrected: 473 × 219.65 − 960 = 102,934.45. */
    public static final ContractNoteExtraction UBS_APPLE_CORRECTED = new ContractNoteExtraction(
            "Apple Inc", "US0378331005", "USD", "473", "219.65", "102934.00", "UBS", "960.00", "sell", "", List.of());

    /** Buy Barclays: 98 × 123.47 + 134 = 12,234.06. */
    public static final ContractNoteExtraction SWEDBANK_BARCLAYS = new ContractNoteExtraction(
            "Barclays Bank", "GB0031348658", "GBP", "98", "123.47", "12234.06", "Swedbank", "134.00", "buy", "", List.of());
}
