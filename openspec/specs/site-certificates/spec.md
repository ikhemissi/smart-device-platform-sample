# site-certificates Specification

## Purpose

Lets Editors control, per site, which certificate authorities are trusted to onboard devices, with safe renewal, validation and an audit trail, and lets everyone see the trust status of each site.

## Requirements

### Requirement: Create a site
`POST /api/v1/sites` SHALL let Editors create a site with an id (lowercase letters, digits and single hyphens, 3–40 characters) and a display name (≤ 100 characters). A new site has no certificate and no device can join it.

#### Scenario: New site
- **WHEN** an Editor creates site `nice-01` named "Nice warehouse"
- **THEN** the response is 201 and the site appears in the site list with zero certificates

#### Scenario: Duplicate site
- **WHEN** an Editor creates a site whose id already exists
- **THEN** the response is 409

### Requirement: List site certificates
`GET /api/v1/sites/{siteId}/certificates` SHALL return the site's certificates with subject, issuer, SHA-256 fingerprint, validity start and end, slot (`primary` or `secondary`), status (`valid`, `expiring` when ending within 30 days, `expired`), and who added it and when.

#### Scenario: Demo site certificate
- **WHEN** a Reader lists the certificates of `paris-01` after deployment
- **THEN** one certificate is returned with status `valid` and slot `primary`

### Requirement: Add a site certificate
`POST /api/v1/sites/{siteId}/certificates` SHALL let Editors upload one X.509 certificate (PEM or DER). It SHALL be accepted only if it is a CA certificate (basic constraints CA=true), currently valid, contains no private key, is not already trusted by the site, and the site has fewer than two certificates. Devices issued by it SHALL be able to join within 5 minutes.

#### Scenario: First certificate
- **WHEN** an Editor uploads a valid CA certificate to site `nice-01`
- **THEN** the response is 201 with slot `primary`, and a device whose certificate it issued can provision within 5 minutes

#### Scenario: Renewal with a second certificate
- **WHEN** an Editor uploads a second valid CA certificate to a site holding one
- **THEN** it is stored in slot `secondary` and devices issued by either certificate can join

#### Scenario: Third certificate refused
- **WHEN** an Editor uploads a certificate to a site already holding two
- **THEN** the response is 409 explaining that one must be removed first

#### Scenario: Not a CA certificate
- **WHEN** an Editor uploads a device (leaf) certificate
- **THEN** the response is 400 stating a certificate authority certificate is required

#### Scenario: Private key included
- **WHEN** the upload contains a private key
- **THEN** the response is 400, the key is discarded and never stored or logged

#### Scenario: Expired certificate
- **WHEN** an Editor uploads an expired CA certificate
- **THEN** the response is 400 stating the certificate is expired

### Requirement: Remove a site certificate
`DELETE /api/v1/sites/{siteId}/certificates/{fingerprint}` SHALL let Editors remove a certificate; afterwards devices whose certificate it issued SHALL no longer be able to join or re-join (provision). Devices already on the platform keep sending until they re-join, unless the site has no certificate left. Measurements already received SHALL be kept.

#### Scenario: Remove one of two
- **WHEN** an Editor removes the primary certificate of a site holding two
- **THEN** the remaining certificate keeps working and devices issued only by the removed one can no longer provision

### Requirement: Last certificate removal
When the last certificate of a site is removed, the site SHALL remain, no device SHALL be able to join it, and all devices of that site SHALL be disabled so they cannot reconnect. When a certificate is later added to that site, the devices disabled this way SHALL be re-enabled.

#### Scenario: Site loses all trust
- **WHEN** an Editor removes the only certificate of `lyon-01`
- **THEN** devices of `lyon-01` are disabled and cannot send new measurements, and the site still appears in the list

#### Scenario: Trust restored
- **WHEN** an Editor then adds a valid certificate to `lyon-01`
- **THEN** the devices disabled by the removal are re-enabled

### Requirement: Consistent changes and audit
A site or certificate change SHALL be reported successful only once the device provisioning configuration is updated; otherwise it SHALL fail without changing provisioning. Platform records SHALL converge with the provisioning configuration within 1 hour. Each change SHALL record user, time, action and fingerprint, retrievable via `GET /api/v1/sites/{siteId}/audit`.

#### Scenario: Provisioning service unavailable
- **WHEN** an Editor adds a certificate while the provisioning service cannot be updated
- **THEN** the response is 503, device provisioning is unchanged and the certificate is not listed

#### Scenario: Records repaired after partial failure
- **WHEN** provisioning was updated but recording the change failed
- **THEN** within 1 hour the site's certificate list matches the provisioning configuration and the audit contains a reconciliation entry

#### Scenario: Audit entry
- **WHEN** an Editor removes a certificate
- **THEN** the site's audit contains an entry with the Editor, the time, action `CERTIFICATE_REMOVED` and the fingerprint

### Requirement: Reconciliation with deployment-created sites
Sites and certificates created outside the API (deployment bootstrap) SHALL appear in the site and certificate lists within 1 hour of deployment, or immediately after the provisioning API starts, and SHALL be manageable like any other site.

#### Scenario: Bootstrap sites visible
- **WHEN** the platform is deployed and the provisioning API has started
- **THEN** the demo sites' certificates are listed with added-by `deployment`

### Requirement: Sites in the portal
The portal SHALL provide a Sites section listing sites with device count and certificate status summary, and a site page listing certificates with expiry warnings. Editors SHALL be able to create sites, upload certificates (file picker) and remove certificates after explicit confirmation; these actions SHALL be hidden from Readers.

#### Scenario: Expiry warning
- **WHEN** a site's certificate expires in 10 days
- **THEN** the site list and site page show an "expiring" warning for it

#### Scenario: Removal confirmation
- **WHEN** an Editor clicks remove on the last certificate of a site
- **THEN** a confirmation states that all devices of the site will be disabled, and nothing happens unless confirmed
