# PRD-008 — M8: Site digest

| Field | Value |
|---|---|
| Status | Draft |
| Milestone | M8 |
| Depends on | M5 (alerts), M6 (fleet summaries, AI models), M7 (anomalies) |
| Overview | [PRD-000](PRD-000-product-overview.md) |

## 1. Goal

Give whoever opens a site in the portal **a one-page brief of the last day**: is every device reporting, did any metric behave differently from the previous week, which alerts and anomalies happened, and what needs attention — in a few sentences of plain language, with the figures it is based on.

The platform computes the figures; an AI model only writes them up. Nothing in the brief is invented, and every statement can be traced to the data.

## 2. Example situations

- *"Paris: 10 of 10 devices reporting. Temperature averaged 21.4 °C, as during the previous week; humidity was 6 points higher. One alert on paris-01-ruuvi5-001 (temperature above 8 °C, 12 minutes, closed). Two anomalies: a sensor fault on paris-01-ruuvi5-001 and a real event on three ruuvi3 devices at 12:15."*
- *"Lyon: 9 of 10 devices reporting — lyon-01-bthome-002 has been silent since 03:10. No alerts. The site's CA certificate expires in 12 days."*
- A site manager has just fixed a device and wants an up-to-date brief before a meeting → an Editor asks for a new digest, which appears a few minutes later.

## 3. User stories

| ID | As a… | I want… | So that… |
|---|---|---|---|
| M8-US1 | Data Viewer | a short brief of the last 24 hours on the site page, compared with the previous week | I know the site's state at a glance, without opening every device |
| M8-US2 | Data Viewer | the figures behind the brief (devices reporting, metric averages and changes, alerts, anomalies, rejected messages, certificate expiry) and links to the devices it mentions | I can check what it says and go further |
| M8-US3 | Data Editor | to ask for a new digest | the brief reflects what happened since the last one |
| M8-US4 | Data Viewer | to ask the AI assistant about a site's digest ("what happened at lyon-01 yesterday?") | I get the brief in the conversation |
| M8-US5 | Platform operator | to choose which sites get a digest | the cost covers the sites that matter |

## 4. Acceptance criteria

1. Every configured site has a digest generated **every day at 06:00 UTC**, covering the previous 24 hours compared with the 7 days before.
2. The site page shows the latest digest: when it was generated, the period, the brief (a few sentences, in English) and its figures.
3. Every number in the brief comes from the computed figures; the brief names the devices it mentions, which link to their pages.
4. An Editor can request a new digest from the site page; it replaces the shown one **within 10 minutes**. Readers cannot request one.
5. If the AI model fails, the figures are still shown, with a message that the brief is unavailable; the next run retries.
6. The AI assistant can return a site's latest digest within the user's permissions.
7. Digests can be switched off per environment without affecting the rest of the platform.

## 5. Out of scope

- Sending digests by email (a later step; the card comes first).
- Weekly or custom periods, other languages.
- The maintenance queue (M9) — added to the digest when it exists.
- Digests across several sites (a fleet digest).
