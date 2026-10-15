-- Client-supplied key for safe retries of POST /ads. NULLs don't conflict, so ads created
-- without a key are unaffected.
ALTER TABLE ads ADD COLUMN idempotency_key VARCHAR(255) UNIQUE;
