-- Singleton serialization gate for AuditLogService.append.
--
-- Two concurrent admin appends would both read the same predecessor row via
-- findLatestForUpdate's subquery, both compute prev_hash from it, and both
-- INSERT — producing two rows whose prev_hash point at the same predecessor
-- and breaking the hash chain.
--
-- Oracle's SERIALIZABLE isolation catches UPDATE/DELETE conflicts at commit
-- via ORA-08177, but disjoint INSERTs of new rows succeed on both
-- transactions because snapshot isolation does not detect "write skew" on
-- INSERT-only chains.
--
-- Fix: AuditLogService.append selects this row FOR UPDATE (READ_COMMITTED)
-- before reading the chain head.  The row-level lock is released at commit,
-- so only one appender can hold it at a time.  The scheduler_lease table
-- already exists and APP_ADMIN has SELECT/INSERT/UPDATE/DELETE on it.
--
-- expires_at is set to epoch so the row is obviously a sentinel (it does not
-- participate in scheduler-lease expiry logic; the scheduler never looks for
-- the AUDIT_CHAIN_LOCK name).
--
-- MERGE is idempotent: if Flyway history is repaired and this script is
-- re-applied, the duplicate-key error from a plain INSERT is avoided.

MERGE INTO scheduler_lease tgt
USING (SELECT 'AUDIT_CHAIN_LOCK' AS name FROM dual) src
ON (tgt.name = src.name)
WHEN NOT MATCHED THEN
  INSERT (name, holder, expires_at)
  VALUES ('AUDIT_CHAIN_LOCK', 'audit-system',
          TIMESTAMP '1970-01-01 00:00:00 UTC');
