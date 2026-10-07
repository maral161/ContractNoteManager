-- The Sharpfin user the import ran as (for debugging visibility of orders)
ALTER TABLE import_run ADD COLUMN sharpfin_user VARCHAR(300);
