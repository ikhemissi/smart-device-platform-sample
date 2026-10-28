#!/usr/bin/env bash
# Removes a test site created through the provisioning API from the current azd environment: its DPS enrollment group,
# its DPS CA certificates, its devices in IoT Hub and its database records (sites, devices, their data, thresholds and alerts).
# The platform API deliberately cannot delete sites; this script is for test sites only (ids starting with e2e- or test-).
#   scripts/remove-test-site.sh <site-id>
set -euo pipefail
cd "$(dirname "$0")/.."
site="${1:?usage: $0 <site-id>}"
[[ "$site" =~ ^(e2e|test)-[a-z0-9-]+$ ]] || { echo "Refusing to remove '$site': only e2e-* and test-* sites can be removed" >&2; exit 1; }
eval "$(azd env get-values)"
export AZURE_EXTENSION_USE_DYNAMIC_INSTALL=yes_without_prompt

if az iot dps enrollment-group show --dps-name "$DPS_NAME" -g "$AZURE_RESOURCE_GROUP" --enrollment-id "site-$site" \
     --auth-type login -o none 2>/dev/null; then
  az iot dps enrollment-group delete --dps-name "$DPS_NAME" -g "$AZURE_RESOURCE_GROUP" --enrollment-id "site-$site" \
    --auth-type login -o none
  echo "[$site] DPS enrollment group deleted"
fi

for name in $(az iot dps certificate list --dps-name "$DPS_NAME" -g "$AZURE_RESOURCE_GROUP" \
                --query "value[?starts_with(name, 'site-$site-') || name == 'site-$site'].name" -o tsv); do
  etag="$(az iot dps certificate show --dps-name "$DPS_NAME" -g "$AZURE_RESOURCE_GROUP" --certificate-name "$name" --query etag -o tsv)"
  az iot dps certificate delete --dps-name "$DPS_NAME" -g "$AZURE_RESOURCE_GROUP" --certificate-name "$name" --etag "$etag" -o none
  echo "[$site] DPS certificate $name deleted"
done

for device in $(az iot hub device-identity list --hub-name "$IOTHUB_NAME" --auth-type login \
                  --query "[?tags.siteId == '$site'].deviceId" -o tsv); do
  az iot hub device-identity delete --hub-name "$IOTHUB_NAME" --auth-type login --device-id "$device" -o none
  echo "[$site] IoT Hub device $device deleted"
done

scripts/psql.sh -q -v site="$site" <<'SQL'
BEGIN;
DELETE FROM sdp.alert_notifications WHERE alert_id IN (SELECT id FROM sdp.alert_events WHERE site_id = :'site');
DELETE FROM sdp.alert_events WHERE site_id = :'site';
DELETE FROM sdp.alert_thresholds WHERE device_id IN (SELECT device_id FROM sdp.devices WHERE site_id = :'site');
DELETE FROM sdp.device_metrics WHERE device_id IN (SELECT device_id FROM sdp.devices WHERE site_id = :'site');
DELETE FROM sdp.metric_rollups_hourly WHERE site_id = :'site';
DELETE FROM sdp.measurements WHERE site_id = :'site';
DELETE FROM sdp.devices WHERE site_id = :'site';
DELETE FROM sdp.site_operations WHERE site_id = :'site';
DELETE FROM sdp.site_audit_events WHERE site_id = :'site';
DELETE FROM sdp.site_certificates WHERE site_id = :'site';
DELETE FROM sdp.sites WHERE site_id = :'site';
COMMIT;
SQL
echo "[$site] database records deleted"
