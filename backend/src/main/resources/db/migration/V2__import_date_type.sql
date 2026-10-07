-- Which order date the import's date range applied to (BOOKED | TRADED | SETTLED)
ALTER TABLE import_run ADD COLUMN date_type VARCHAR(20);

-- The orders-list URL that was called in Sharpfin (first page), for debugging
ALTER TABLE import_run ADD COLUMN request_url TEXT;
