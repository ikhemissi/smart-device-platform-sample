-- Runs after every migration (Flyway callback). Idempotently creates the database principals of the
-- workload identities and grants them their group role. Empty placeholders are skipped (e.g. local runs).
-- On Azure, principals are Entra managed identities created with pgaadauth; elsewhere plain LOGIN roles.

DO $$
DECLARE
    p record;
BEGIN
    FOR p IN
        SELECT *
          FROM (VALUES
                ('${decoder_principal_name}', '${decoder_principal_object_id}', 'sdp_ingest')
               ) AS t(principal, object_id, group_role)
    LOOP
        CONTINUE WHEN p.principal = '';

        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = p.principal) THEN
            IF to_regprocedure('pgaadauth_create_principal_with_oid(text,text,text,boolean,boolean)') IS NOT NULL THEN
                IF p.object_id = '' THEN
                    RAISE EXCEPTION 'object id required to create Entra principal %', p.principal;
                END IF;
                PERFORM pgaadauth_create_principal_with_oid(p.principal, p.object_id, 'service', false, false);
            ELSE
                EXECUTE format('CREATE ROLE %I LOGIN', p.principal);
            END IF;
        END IF;

        EXECUTE format('GRANT %I TO %I', p.group_role, p.principal);
    END LOOP;
END
$$;
