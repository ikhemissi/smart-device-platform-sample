import { describe, expect, it, vi } from 'vitest'
import { loadConfig } from './config'

const BASE = { tenantId: 't', clientId: 'c', apiScope: 's', apiBaseUrl: 'https://api.test/api/v1/' }

function serve(config: object) {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify(config)))
}

describe('runtime configuration', () => {
  it('switches optional features off when absent', async () => {
    serve(BASE)

    expect(await loadConfig()).toEqual({ ...BASE, apiBaseUrl: 'https://api.test/api/v1', assistantEnabled: false, anomaliesEnabled: false, anomalySites: [], digestsEnabled: false, maintenanceEnabled: false })
  })

  it('reads anomaly detection and its sites', async () => {
    serve({ ...BASE, anomaliesEnabled: true, anomalySites: ['paris-01', 'lyon-01'] })

    expect(await loadConfig()).toMatchObject({ anomaliesEnabled: true, anomalySites: ['paris-01', 'lyon-01'] })
  })

  it('reads site digests', async () => {
    serve({ ...BASE, digestsEnabled: true })

    expect(await loadConfig()).toMatchObject({ digestsEnabled: true })
  })

  it('reads predictive maintenance', async () => {
    serve({ ...BASE, maintenanceEnabled: true })

    expect(await loadConfig()).toMatchObject({ maintenanceEnabled: true })
  })
})
