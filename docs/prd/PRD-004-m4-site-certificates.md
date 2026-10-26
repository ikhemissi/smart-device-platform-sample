# PRD-004 — M4: Site certificate management

| Field | Value |
|---|---|
| Status | Draft |
| Milestone | M4 |
| Depends on | M2 |
| Overview | [PRD-000](PRD-000-product-overview.md) |

## 1. Goal

Let Editors **control which devices may join the platform, site by site**, by adding or removing the certificates that devices of a site must be signed with — including replacing a certificate without interrupting the devices of that site.

## 2. Business context

Each site has a certificate authority (typically owned by the site or the installer). Devices installed on a site carry a certificate issued by that authority. Trusting the site's certificate means trusting all its devices; removing it stops new connections from devices of that site. A site can trust **up to two certificates at a time** so that a certificate can be renewed while devices are progressively updated.

## 3. User stories

| ID | As a… | I want… | So that… |
|---|---|---|---|
| M4-US1 | Data Viewer | to see the list of sites, and for each one the trusted certificates with their expiry date | I know whether a site is operational |
| M4-US2 | Data Editor | to create a site | new locations can be onboarded |
| M4-US3 | Data Editor | to add a certificate to a site by uploading it | devices of that site can join |
| M4-US4 | Data Editor | to add a second certificate while the first is still valid | I can renew certificates without downtime |
| M4-US5 | Data Editor | to remove a certificate from a site | devices relying on it can no longer join |
| M4-US6 | Data Editor | to be warned about certificates expiring within 30 days | I renew them in time |
| M4-US7 | Platform operator | each change to be recorded with who did it and when | changes are traceable |

## 4. Acceptance criteria

1. The site list shows each site with its certificates: subject, fingerprint, validity dates and status (valid, expiring within 30 days, expired).
2. Uploading a valid certificate makes devices whose certificate it issued able to join within **5 minutes**.
3. A site holds at most two certificates; adding a third is refused with a clear message.
4. Uploading something that is not a certificate authority certificate, or an expired one, is refused with a clear message.
5. Removing a certificate is confirmed by the user, then devices relying solely on it can no longer join or re-join the platform; devices already on the platform keep working until they re-join; already-received data is kept.
6. Removing the last certificate of a site leaves the site in place, with no device able to join, and stops all devices of that site from sending data; adding a certificate again restores them.
7. Only Editors can add/remove certificates or create sites; Viewers can see sites and certificates.
8. The demo sites created during deployment are visible and manageable like any other site.

## 5. Out of scope

- Issuing certificates (the platform only trusts certificates issued elsewhere; the demo tooling generates demo certificates).
- Revoking individual device certificates.
- Disconnecting devices that are already connected when a certificate is removed.
