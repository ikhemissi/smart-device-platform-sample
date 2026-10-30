// Fake environment served to the end-to-end tests (no real tenant or API is contacted).
export const PORT = 4280
export const FAKE_ENV = {
  ENTRA_TENANT_ID: '11111111-2222-3333-4444-555555555555',
  WEB_CLIENT_ID: 'cccccccc-0000-0000-0000-000000000001',
  API_SCOPE: 'api://aaaaaaaa-0000-0000-0000-000000000001/user_impersonation',
  API_BASE_URL: 'https://apim-e2e.azure-api.net/api/v1',
  // Anomaly detection (M7) on, for paris-01 only.
  ANOMALY_DETECTOR_JOB_NAME: 'caj-anomaly-detector-e2e',
  ANOMALY_SITES: 'paris-01',
}
