# Demo guide — M2: the device list in the web portal

Shows that people sign in with their organisational account, see only what their permission allows, find devices quickly and, as Editors, maintain device information safely ([PRD-002](../prd/PRD-002-m2-device-list.md)). Duration: about 15 minutes.

## Before the demo

The environment must have M1 running (devices reporting) and M2 deployed:

```bash
azd env select sdp-dev
azd provision && azd deploy        # or: azd up
azd env get-values | grep -E 'WEB_URL|API_BASE_URL|API_SCOPE|API_CLIENT_ID'
```

- **Who presents:** the presenter's account holds both permissions, which are assigned to the deployer by Terraform. To show the Reader and no-permission views, use a second account, or change your own assignment as in section 5.
- **Browser:** a normal desktop browser (Edge, Chrome, Firefox or Safari) with the developer tools open on the Console tab; it should stay free of security-policy errors throughout.

Useful variables:

```bash
WEB=$(azd env get-value WEB_URL)
API=$(azd env get-value API_BASE_URL)
SCOPE=$(azd env get-value API_SCOPE)
TOKEN=$(az account get-access-token --scope "$SCOPE" --query accessToken -o tsv)   # Azure CLI is pre-authorized (dev)
```

## 1. Signing in

Open `$WEB/devices?siteId=lyon-01` in a private window.
- The portal redirects to Microsoft Entra ID; after sign-in you're back on the device list, already filtered on `lyon-01`.
- The header shows your name and a **Sign out** action.

## 2. Finding devices

- **Columns:** each device shows its name (or id while unnamed), site, payload format, first seen and last seen (relative and exact time), and a **Reporting** or **Silent** badge. Silent means no data for 15 minutes.
- **Site filter:** choose another site; the address bar changes. Copy the address into another tab, and the same view opens.
- **Search:** type `cayenne`; after a short pause only Cayenne LPP devices remain. The search is case-insensitive and covers ids and names.
- **Sorting:** click **Last seen** twice to sort ascending, then descending.
- **Status filter:** choose **Silent**. In the default fleet every device reports, so the list is empty; the empty state offers **Clear filters**. To have silent devices, lower the fleet at least 15 minutes before the demo; the last device of each site then stops reporting:

  ```bash
  az containerapp update -n "$(azd env get-value SIMULATOR_APP_NAME)" -g "$(azd env get-value AZURE_RESOURCE_GROUP)" \
    --set-env-vars SDP_SIM_DEVICES_PER_SITE=9      # the next `azd provision` restores 10
  ```
- **Paging:** with **All sites**, use **Next** and **Previous** (25 devices per page).

## 3. Editing a device (Editor)

1. Click the pencil next to a device, enter a display name such as "Lyon cold room", a description and a location (for example 45.7640, 4.8357, "Lyon HQ"), then **Save**.
2. The list shows the new name with the device id underneath, and the change survives a reload.
3. Validation matches the API: a 101-character name, latitude 91, or a label without coordinates are refused before anything is sent.

The change is attributed to the editor. Check through the API:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$API/devices/<deviceId>" | python3 -m json.tool
```

`updatedBy` contains your object id and name; `updatedAt` is the time of the change.

## 4. Two editors at the same time

1. Open the same device's edit dialog in two tabs.
2. Save a change in the first tab.
3. Save a different change in the second tab. The dialog explains that someone else changed the device, shows the latest values, and keeps nothing from the outdated version.

## 5. Permissions

Each layer enforces permissions on its own: the portal hides actions, the gateway refuses the call, and the API refuses it again.

```bash
curl -s -o /dev/null -w '%{http_code}\n' "$API/devices"                                    # 401: no token
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" "$API/devices"  # 200
BACKEND="https://$(az containerapp show -n "$(azd env get-value DEVICE_API_APP_NAME)" \
  -g "$(azd env get-value AZURE_RESOURCE_GROUP)" --query properties.configuration.ingress.fqdn -o tsv)"
curl -s -o /dev/null -w '%{http_code}\n' "$BACKEND/api/v1/devices"                         # 401: gateway bypass
```

To show the Reader and no-permission views with a single account, remove your own assignment in **Entra ID → Enterprise applications → Smart Device Platform API (<environment>) → Users and groups**:
- **Reader:** remove **Edit device data**, sign out and sign in again. The pencil disappears, and a direct `PATCH` with a fresh token gets 403.
- **No permission:** remove **Query device data** too. The portal shows the access-denied page naming the permission to request, and no device data is loaded.

Restore the assignments afterwards with `azd provision`.

## 6. Behind the scenes

Every portal request is traced from API Management to the device API and the database:

```kusto
requests
| where timestamp > ago(30m) and cloud_RoleName has "apim"
| project timestamp, name, resultCode, duration, operation_Id
| join kind=leftouter (dependencies | where cloud_RoleName == "sdp-device-api" | summarize sql=count() by operation_Id) on operation_Id
| order by timestamp desc
```

Run this in Application Insights → Logs. Gateway latency for a 100-device page is about 100–150 ms at the 95th percentile.
