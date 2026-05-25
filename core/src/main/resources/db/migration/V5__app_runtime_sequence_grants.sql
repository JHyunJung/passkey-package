-- Companion to V4: APP_RUNTIME also needs SELECT on the platform sequence
-- mds_blob_cache_seq for Hibernate's hibernate.ddl-auto=validate to
-- confirm the sequence exists. The MdsBlobCache entity is in :core and
-- thus part of both apps' entity scan. APP_RUNTIME does NOT receive
-- INSERT/UPDATE on mds_blob_cache itself — only the admin scheduler
-- writes to it — so granting SELECT on the sequence is safe.
--
-- credential_seq already has SELECT for APP_RUNTIME via V2.
-- mds_blob_cache_seq and scheduler_lease were missed in the original
-- migration set; this catches them up.

GRANT SELECT ON mds_blob_cache_seq TO APP_RUNTIME;
