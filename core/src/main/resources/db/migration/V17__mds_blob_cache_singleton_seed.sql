-- mds_blob_cache singleton row.
--
-- Phase 0 V1 created the table allowing multiple rows. Phase 0
-- followup notes (mds_blob_cache singleton policy section) chose
-- Option A: always use id=1, UPSERT instead of INSERT, do not use
-- mds_blob_cache_seq for new rows.
--
-- This seed creates the sentinel row so MdsBlobStore can MERGE
-- against it on every scheduler fire without checking existence.
-- The placeholder values (version=0, ancient dates, empty JSON
-- blob_jwt) are overwritten on the first successful FIDO MDS3 fetch.

MERGE INTO mds_blob_cache USING dual ON (id = 1)
WHEN NOT MATCHED THEN INSERT (id, version, next_update, fetched_at, blob_jwt)
  VALUES (1, 0, DATE '1970-01-01', TIMESTAMP '1970-01-01 00:00:00 +00:00', '{}');

COMMIT;
