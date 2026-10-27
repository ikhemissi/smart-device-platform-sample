# web-portal Specification

## Purpose

Provides the browser experience of the platform: signing in, navigating, browsing devices and, for Editors, maintaining device information, with role-aware behaviour and clear error handling.

## Requirements

### Requirement: Portal shell
The portal SHALL show the signed-in user's name, a sign-out action and navigation to the platform's sections, and SHALL open on the device list after sign-in.

#### Scenario: Landing after sign-in
- **WHEN** a Reader signs in
- **THEN** the device list is displayed with their name in the header

### Requirement: Access-denied page
The portal SHALL display an access-denied page, without any data, when the signed-in user has no platform permission.

#### Scenario: User without role
- **WHEN** the current-user endpoint reports `canRead: false`
- **THEN** the access-denied page is shown and no device request is made

### Requirement: Device list view
The device list SHALL display display name (or id when empty), site, format, first seen, last seen (relative and absolute time) and a reporting/silent status badge, with a site filter, a search box, sortable columns, paging and an empty state. Filters, sort and page SHALL be reflected in the URL.

#### Scenario: Search narrows the list
- **WHEN** a user types "cold" in the search box
- **THEN** after a short debounce only matching devices are displayed and the URL contains the search term

#### Scenario: Shared link restores the view
- **WHEN** a user opens a URL containing a site filter and page number
- **THEN** the list opens with that filter and page applied

### Requirement: Role-aware editing
Edit actions SHALL be visible only to users with `canWrite`; the edit dialog SHALL validate inputs as the API does, send the entity tag, and on conflict SHALL inform the user and reload the latest version.

#### Scenario: Reader sees no edit action
- **WHEN** a Reader views the device list
- **THEN** no edit action is displayed

#### Scenario: Conflicting edit
- **WHEN** an Editor saves changes and the API answers 412
- **THEN** the portal explains that the device was changed by someone else and shows the latest values

### Requirement: Session and error handling
The portal SHALL renew tokens silently, re-prompt for sign-in only when renewal fails, and show non-technical error messages with a retry option when an API call fails.

#### Scenario: API unavailable
- **WHEN** the device list request fails with a 5xx error
- **THEN** a message explains the data could not be loaded and offers a retry

### Requirement: Supported browsers and accessibility
The portal SHALL work in the two latest versions of Edge, Chrome, Firefox and Safari on desktop and SHALL be operable with keyboard only, with accessible names on all controls.

#### Scenario: Keyboard navigation
- **WHEN** a user navigates the device list with the keyboard only
- **THEN** filters, search, sorting, paging and the edit action are reachable and operable
