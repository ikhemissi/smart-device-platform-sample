# Tasks: M4 — Site certificate management

## 1. Data and service scaffold

- [x] 1.1 Add Flyway migration (`site_certificates`, `site_audit_events`, `site_operations`, `devices.disabled_by_platform`, provisioning-api principal and grants); verify with Testcontainers that grants are least-privilege
- [x] 1.2 Scaffold `services/provisioning-api` with shared security, passwordless datasource and DPS/IoT Hub clients using `TokenCredential`; verify it starts locally and lists enrollment groups with the developer's identity

## 2. Site and certificate operations

- [x] 2.1 Implement certificate parsing/validation (PEM/DER, CA check, validity, private-key rejection without logging, duplicates); verify unit tests for every rejection scenario using generated test certificates
- [x] 2.2 Implement `POST /api/v1/sites`; verify new-site and duplicate scenarios
- [x] 2.3 Implement `GET /api/v1/sites/{id}/certificates` with status computation; verify the demo-site and expiring scenarios
- [x] 2.4 Implement add-certificate with slot logic, DPS ETag update then DB commit; verify first-certificate, renewal, third-certificate, 503 and records-repaired scenarios (DPS client and DB failures injected)
- [x] 2.5 Implement remove-certificate including primary/secondary promotion and enrollment disable; verify remove-one-of-two scenario
- [x] 2.6 Implement device disable/re-enable on last removal/first addition; verify against the dev environment that a simulated device of the site stops sending and resumes after re-adding
- [x] 2.7 Implement audit recording and `GET /api/v1/sites/{id}/audit`; verify the audit-entry scenario
- [x] 2.8 Implement start-up/hourly reconciliation with advisory lock; verify the bootstrap-sites-visible scenario on a fresh environment (verified on the first start of the service in `sdp-dev`, where no trust records existed yet: the project keeps a single long-lived environment)
- [x] 2.9 Document the service in `services/provisioning-api/README.md`; verify documented endpoints match the OpenAPI document

## 3. Infrastructure and gateway

- [x] 3.1 Add the provisioning-api identity, roles (DPS data contributor, IoT Hub registry contributor) and container app in Terraform and `azure.yaml`; verify with `az role assignment list` and a healthy deployment
- [x] 3.2 Implement OpenAPI merge and operation-level backend routing in APIM; verify each provisioning operation reaches provisioning-api and others reach device-api
- [x] 3.3 Verify Reader gets 403 on site creation, certificate add/remove through APIM and directly on the service

## 4. Portal

- [x] 4.1 Add the Sites list and site page (certificates table, status badges, expiry warnings); verify component tests for the expiry-warning scenario
- [x] 4.2 Add Editor-only create-site, upload-certificate and remove-certificate (with last-certificate confirmation wording) dialogs; verify component tests for confirmation and reader-hidden actions
- [x] 4.3 Playwright smoke test: create a site, upload a generated CA, remove it; verify it passes against the environment

## 5. Integration

- [x] 5.1 Write the M4 demo guide `docs/demo/m4-site-certificates.md` (incl. rotation with two certificates and last-certificate removal effect on the simulator); verify a dry run follows it as written

## Workflow follow-up

- Archive the change after review so its specs become the baseline in `openspec/specs/`.
