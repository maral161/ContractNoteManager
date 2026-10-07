-- Three match results: MATCHED, PARTIALLY_MATCHED, NO_MATCH (plus EXTRACTION_FAILED)
UPDATE contract_note SET status = 'NO_MATCH' WHERE status = 'UNMATCHED';
ALTER TABLE contract_note ADD COLUMN match_score INTEGER;
ALTER TABLE contract_note ADD COLUMN match_checks JSONB;
