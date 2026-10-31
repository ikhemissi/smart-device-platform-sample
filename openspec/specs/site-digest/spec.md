# site-digest Specification

## Purpose

Gives each configured site a daily brief of its last 24 hours compared with the previous week: figures computed by the platform, written up in a few sentences by a language model that only uses those figures, shown on the site page and regenerated on an Editor's request.

## Requirements

### Requirement: Digested sites
The platform SHALL produce digests for the sites configured for the environment (default: all sites) and no others.

#### Scenario: Site not configured
- **WHEN** a site is not in the configured list
- **THEN** no digest is generated for it, and its site page says that the site has no digest

### Requirement: Daily digest
Every day, once 06:00 UTC has passed, the platform SHALL generate one digest per configured site covering the 24 hours before the generation, compared with the 7 days before that period; a site that already has that day's scheduled digest SHALL NOT get another one.

#### Scenario: Morning digest
- **WHEN** a user opens the page of a configured site after 06:10 UTC
- **THEN** the site's digest of the day is shown, with its period and generation time

### Requirement: Figures
Each digest SHALL hold figures computed from the platform's data, never by a model, covering the site's devices (reporting and silent), each reported metric among the configured ones (average over the period and over the comparison period, and the change; battery metrics are not compared by default, the maintenance queue covers batteries), alerts, anomalies, rejected messages, site certificates expiring within 30 days and, when maintenance is enabled, the site's maintenance queue.

#### Scenario: Silent device
- **WHEN** a device of the site has not reported for more than 15 minutes at generation time
- **THEN** the figures list it as silent with its last report time

#### Scenario: Alerts and anomalies
- **WHEN** alerts opened and anomalies were recorded at the site during the period
- **THEN** the figures list each alert (device, metric, status) and the anomalies by status (device, metric, classification, confidence)

#### Scenario: Maintenance queue
- **WHEN** devices of the site are in the maintenance queue at generation time
- **THEN** the figures give the number of items by status and the most urgent devices with their probability and days left

#### Scenario: Batteries not compared
- **WHEN** a digest is generated with the default settings
- **THEN** its metric figures hold no battery voltage or level, and batteries appear only through the maintenance queue

### Requirement: Brief
Each digest SHALL include a brief of at most six sentences, in English, generated from the digest's figures only: every number it contains SHALL appear in the figures, and every device it mentions SHALL be named by its id.

#### Scenario: Grounded brief
- **WHEN** a digest is generated
- **THEN** its brief states the devices reporting, the notable metric changes, alerts and anomalies, using only values present in its figures

#### Scenario: Model unavailable
- **WHEN** the language model fails during generation
- **THEN** the digest is stored with its figures and without a brief, the site page shows the figures with a message that the brief is unavailable, and a later run adds the brief

### Requirement: Digest API
The platform API SHALL return, to Readers, whether a site has digests in the environment, its latest digest (brief, period, generation time, figures, model) if any, and the pending regeneration request if any; and SHALL let Editors request a new digest of a configured site. Readers SHALL receive 403 when requesting one; a request while another is pending for the same site SHALL return the pending one instead of creating a second.

#### Scenario: No digest yet
- **WHEN** a Reader asks for the digest of a configured site that has none yet
- **THEN** the response says the site has digests but none yet, with any pending request

#### Scenario: Request for a site without digests
- **WHEN** an Editor requests a digest for a site that is not configured
- **THEN** the request is rejected with a clear message (409)

#### Scenario: Reader cannot request
- **WHEN** a Reader requests a digest
- **THEN** the response is 403

### Requirement: Regeneration
A digest requested by an Editor SHALL replace the shown digest within 10 minutes, covering the 24 hours before its generation, and SHALL record who requested it.

#### Scenario: Regenerate from the site page
- **WHEN** an Editor selects *Regenerate* on a site page
- **THEN** the card says a new digest is being generated, and within 10 minutes it shows the new digest without the page being reloaded by hand

### Requirement: Site page card
The site page SHALL show a *Digest* card with the latest digest: the brief, the period, the generation time, the figures, and links to the pages of the devices mentioned; Editors SHALL see a *Regenerate* action; when digests are disabled in the environment, the card SHALL not be shown.

#### Scenario: Reader views a digest
- **WHEN** a Reader opens the page of a site with a digest
- **THEN** the card shows the brief and the figures, the device ids in the brief link to their pages, and there is no *Regenerate* action

### Requirement: Availability per environment
Digests SHALL be switchable per environment; when they are off, no digest is generated, the portal hides the card, the assistant has no digest tool, and the rest of the platform is unaffected.

#### Scenario: Digests disabled
- **WHEN** digests are disabled in the environment
- **THEN** the site page has no Digest card and the digest API is not called by the portal

### Requirement: Digest observability
Each generation run SHALL be traced in Application Insights with the sites processed, digests generated (scheduled and requested), model calls with token usage, and failures.

#### Scenario: Trace of a run
- **WHEN** a run generates the digest of one site
- **THEN** Application Insights shows one request for the run with a child span for the site's figures and one for the model call with its token usage
