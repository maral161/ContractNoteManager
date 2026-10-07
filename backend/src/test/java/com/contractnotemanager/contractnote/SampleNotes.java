package com.contractnotemanager.contractnote;

import java.util.List;

/**
 * What the three sample PDFs in docs/samples/contract-notes contain, in the shape Claude returns it
 * (numbers already without thousands separators).
 */
public final class SampleNotes {

    private SampleNotes() {
    }

    public static final ContractNoteExtraction ABN_AMRO_ABB = new ContractNoteExtraction(
            "ABB", "CH0012221716", "SEK", "1864", "435.52", "811809.00", "ABN Amro", "1200.00", "", "", List.of());

    public static final ContractNoteExtraction UBS_APPLE = new ContractNoteExtraction(
            "Apple Inc", "US0378331005", "USD", "473", "219.65", "103894.00", "UBS", "1200.00", "", "", List.of());

    public static final ContractNoteExtraction SWEDBANK_BARCLAYS = new ContractNoteExtraction(
            "Barclays Bank", "GB0031348658", "GBP", "98", "123.47", "12100.10", "Swedbank", "134.00", "", "", List.of());
}
