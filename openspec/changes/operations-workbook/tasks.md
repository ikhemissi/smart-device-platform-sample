# Tasks: Operations workbook

## 1. Workbook

- [x] 1.1 Add `infra/modules/monitoring` with `azurerm_application_insights_workbook` (deterministic name, `source_id` = Application Insights), the resource ids it needs (adding the PostgreSQL server and API Management id outputs) and the azd output `OPERATIONS_WORKBOOK_URL`; verify `azd provision --preview` only adds the workbook and outputs
- [x] 1.2 Write the key indicators section (time range parameter, tiles, measurements chart); verify each KQL query returns data against `sdp-dev` with `az monitor app-insights query` for 24 h and 7 days
- [x] 1.3 Write the latest errors section; verify the errors-appear scenario with an error produced on purpose (e.g. an unknown route on a service) appearing with its operation id
- [x] 1.4 Write the critical components section; verify every metric name exists in the resource's metric definitions (`az monitor metrics list-definitions`) and returns data for the period
- [ ] 1.5 Validate the rendered template (valid JSON, no unresolved placeholder, every query bound to the time range) in a script run before provisioning, and provision `sdp-dev`; verify the open-the-workbook and time-range scenarios in the Azure portal

- [x] 1.6 Move the key indicators to the top panel and add the site, device type and device id filters (device list limited by site and device type) to the device-level figures; verify with the query script for all values and for one site and one device that the figures change accordingly

## 2. Documentation

- [x] 2.1 Reference the workbook in the observability guidelines (§5), the README and the demo guides; verify the links and the `OPERATIONS_WORKBOOK_URL` output
