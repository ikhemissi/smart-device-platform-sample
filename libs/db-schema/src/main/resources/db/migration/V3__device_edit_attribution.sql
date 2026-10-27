-- M2: edits are attributed with the editor's Entra object id (updated_by) and display name (updated_by_name).

ALTER TABLE sdp.devices ADD COLUMN updated_by_name text;

GRANT UPDATE (updated_by_name) ON sdp.devices TO sdp_catalog;
