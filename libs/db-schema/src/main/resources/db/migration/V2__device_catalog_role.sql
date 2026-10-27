-- M2: privileges of the device API (device catalog, ADR-0007). Read access to reference data and telemetry; updates
-- limited to the editable device information and its audit fields.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sdp_catalog') THEN
        CREATE ROLE sdp_catalog NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA sdp TO sdp_catalog;
GRANT SELECT ON sdp.sites, sdp.devices, sdp.metric_definitions, sdp.measurements TO sdp_catalog;
GRANT UPDATE (display_name, description, location, updated_at, updated_by) ON sdp.devices TO sdp_catalog;
