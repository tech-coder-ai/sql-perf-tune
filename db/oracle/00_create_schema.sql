-- One-time DBA script (run as a privileged user). Flyway creates every table afterwards.
-- Replace the passwords before running; never commit real credentials.
CREATE USER SPT_OWNER IDENTIFIED BY "change_me_owner"
    DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS;
GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE SEQUENCE TO SPT_OWNER;

-- Optional least-privilege runtime account. If used, run Flyway as SPT_OWNER
-- (SPT_DB_USER=SPT_OWNER for the migration job) and the service as SPT_APP with
-- SPT_DB_SCHEMA=SPT_OWNER and spring.flyway.enabled=false, then grant DML:
CREATE USER SPT_APP IDENTIFIED BY "change_me_app";
GRANT CREATE SESSION TO SPT_APP;
-- After the first migration, as SPT_OWNER:
--   BEGIN
--     FOR t IN (SELECT table_name FROM user_tables WHERE table_name LIKE 'SPT\_%' ESCAPE '\') LOOP
--       EXECUTE IMMEDIATE 'GRANT SELECT, INSERT, UPDATE, DELETE ON ' || t.table_name || ' TO SPT_APP';
--     END LOOP;
--     EXECUTE IMMEDIATE 'GRANT SELECT ON SPT_TRACKER_V TO SPT_APP';
--   END;
--   /
