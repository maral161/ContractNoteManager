package com.contractnotemanager.contractnote;

/** Reads the trade fields from a contract-note PDF. Implemented with Claude; replaced by a fake in tests. */
public interface ContractNoteExtractor {

    Result extract(byte[] pdf, String fileName);

    record Result(ContractNoteExtraction fields, String model) {
    }
}
