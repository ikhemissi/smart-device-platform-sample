/** Environment-specific settings, generated into /config.json at deployment (the same build serves every environment). */
export interface RuntimeConfig {
  tenantId: string
  clientId: string
  apiScope: string
  apiBaseUrl: string
  /** The AI assistant (M6) is deployed behind the API. */
  assistantEnabled: boolean
}

export async function loadConfig(): Promise<RuntimeConfig> {
  const response = await fetch('/config.json', { cache: 'no-store' })
  if (!response.ok) {
    throw new Error(`config.json could not be loaded (HTTP ${response.status})`)
  }
  const config = (await response.json()) as Partial<RuntimeConfig>
  for (const key of ['tenantId', 'clientId', 'apiScope', 'apiBaseUrl'] as const) {
    if (!config[key]) {
      throw new Error(`config.json is missing "${key}"`)
    }
  }
  return {
    ...(config as RuntimeConfig),
    apiBaseUrl: config.apiBaseUrl!.replace(/\/+$/, ''),
    assistantEnabled: config.assistantEnabled === true,
  }
}
