import type { Session } from '../auth/session'
import type {
  AnswerHandlers,
  AssistantConversation,
  Citation,
  Alert,
  Anomaly,
  AnomalyQuery,
  AnomalyStatus,
  AuditEvent,
  CertificateChange,
  CurrentUser,
  DeviceDetails,
  DigestRequest,
  DeviceEdit,
  DevicePage,
  DeviceQuery,
  Measurement,
  Problem,
  ReportedMetric,
  ReviewVerdict,
  Series,
  Site,
  SiteCertificate,
  SiteDigest,
  Threshold,
  ThresholdInput,
} from './types'

export class ApiError extends Error {
  readonly status: number
  readonly problem: Problem

  constructor(status: number, problem: Problem) {
    super(problem.detail ?? problem.title ?? `HTTP ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }
}

/** A value with the entity tag needed to change it. */
export interface Versioned<T> {
  value: T
  etag: string
}

export class ApiClient {
  private readonly baseUrl: string
  private readonly session: Session

  constructor(baseUrl: string, session: Session) {
    this.baseUrl = baseUrl
    this.session = session
  }

  me(): Promise<CurrentUser> {
    return this.json('/me')
  }

  sites(): Promise<Site[]> {
    return this.json('/sites')
  }

  devices(query: DeviceQuery): Promise<DevicePage> {
    const params = new URLSearchParams()
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== '') {
        params.set(key, String(value))
      }
    }
    return this.json(`/devices?${params}`)
  }

  async device(deviceId: string): Promise<Versioned<DeviceDetails>> {
    const response = await this.request(`/devices/${encodeURIComponent(deviceId)}`)
    return { value: (await response.json()) as DeviceDetails, etag: response.headers.get('ETag') ?? '' }
  }

  async updateDevice(deviceId: string, etag: string, edit: Partial<DeviceEdit>): Promise<Versioned<DeviceDetails>> {
    const response = await this.request(`/devices/${encodeURIComponent(deviceId)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/merge-patch+json', 'If-Match': etag },
      body: JSON.stringify(edit),
    })
    return { value: (await response.json()) as DeviceDetails, etag: response.headers.get('ETag') ?? '' }
  }

  latestMeasurements(deviceId: string, count = 20): Promise<Measurement[]> {
    return this.json(`/devices/${encodeURIComponent(deviceId)}/measurements/latest?count=${count}`)
  }

  deviceMetrics(deviceId: string): Promise<ReportedMetric[]> {
    return this.json(`/devices/${encodeURIComponent(deviceId)}/metrics`)
  }

  /** `to` omitted: the server uses its own "now" (no client clock skew). */
  series(deviceId: string, metrics: string[], from: Date, to: Date | undefined, maxPoints: number): Promise<Series> {
    const params = new URLSearchParams({ metrics: metrics.join(','), from: from.toISOString(), maxPoints: String(maxPoints) })
    if (to) {
      params.set('to', to.toISOString())
    }
    return this.json(`/devices/${encodeURIComponent(deviceId)}/series?${params}`)
  }

  async createSite(siteId: string, displayName: string): Promise<void> {
    await this.request('/sites', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ siteId, displayName }),
    })
  }

  allSiteCertificates(): Promise<SiteCertificate[]> {
    return this.json('/site-certificates')
  }

  siteCertificates(siteId: string): Promise<SiteCertificate[]> {
    return this.json(`/sites/${encodeURIComponent(siteId)}/certificates`)
  }

  siteAudit(siteId: string): Promise<AuditEvent[]> {
    return this.json(`/sites/${encodeURIComponent(siteId)}/audit?limit=20`)
  }

  async addSiteCertificate(siteId: string, file: File): Promise<CertificateChange> {
    const form = new FormData()
    form.append('file', file)
    const response = await this.request(`/sites/${encodeURIComponent(siteId)}/certificates`, { method: 'POST', body: form })
    return (await response.json()) as CertificateChange
  }

  async removeSiteCertificate(siteId: string, fingerprint: string): Promise<CertificateChange> {
    const response = await this.request(`/sites/${encodeURIComponent(siteId)}/certificates/${fingerprint}`, { method: 'DELETE' })
    return (await response.json()) as CertificateChange
  }

  siteDigest(siteId: string): Promise<SiteDigest> {
    return this.json(`/sites/${encodeURIComponent(siteId)}/digest`)
  }

  /** Asks the digest job for a new digest; an existing pending request is returned. */
  async requestSiteDigest(siteId: string): Promise<DigestRequest> {
    const response = await this.request(`/sites/${encodeURIComponent(siteId)}/digest/requests`, { method: 'POST' })
    return (await response.json()) as DigestRequest
  }

  thresholds(deviceId: string): Promise<Threshold[]> {
    return this.json(`/devices/${encodeURIComponent(deviceId)}/thresholds`)
  }

  async createThreshold(deviceId: string, input: ThresholdInput): Promise<Threshold> {
    const response = await this.request(`/devices/${encodeURIComponent(deviceId)}/thresholds`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(input),
    })
    return (await response.json()) as Threshold
  }

  async updateThreshold(deviceId: string, threshold: Threshold, input: ThresholdInput): Promise<Threshold> {
    const response = await this.request(`/devices/${encodeURIComponent(deviceId)}/thresholds/${threshold.id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', 'If-Match': threshold.etag },
      body: JSON.stringify(input),
    })
    return (await response.json()) as Threshold
  }

  async deleteThreshold(deviceId: string, threshold: Threshold): Promise<void> {
    await this.request(`/devices/${encodeURIComponent(deviceId)}/thresholds/${threshold.id}`, {
      method: 'DELETE',
      headers: { 'If-Match': threshold.etag },
    })
  }

  deviceAlerts(deviceId: string): Promise<Alert[]> {
    return this.json(`/devices/${encodeURIComponent(deviceId)}/alerts?limit=50`)
  }

  anomalies(query: AnomalyQuery): Promise<Anomaly[]> {
    const params = new URLSearchParams()
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== '') {
        params.set(key, Array.isArray(value) ? value.join(',') : String(value))
      }
    }
    return this.json(`/anomalies?${params}`)
  }

  /** `to` omitted: the server uses its own "now". */
  deviceAnomalies(deviceId: string, status: AnomalyStatus[], from: Date, to?: Date): Promise<Anomaly[]> {
    const params = new URLSearchParams({ status: status.join(','), from: from.toISOString() })
    if (to) {
      params.set('to', to.toISOString())
    }
    return this.json(`/devices/${encodeURIComponent(deviceId)}/anomalies?${params}`)
  }

  async reviewAnomaly(id: number, verdict: ReviewVerdict, comment: string | null): Promise<Anomaly> {
    const response = await this.request(`/anomalies/${id}/review`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ verdict, comment }),
    })
    return (await response.json()) as Anomaly
  }

  createConversation(): Promise<AssistantConversation> {
    return this.request('/agent/conversations', { method: 'POST' }).then((r) => r.json() as Promise<AssistantConversation>)
  }

  async deleteConversation(id: string): Promise<void> {
    await this.request(`/agent/conversations/${encodeURIComponent(id)}`, { method: 'DELETE' })
  }

  /**
   * Asks a question; the answer arrives as Server-Sent Events (tool activity, text deltas) and resolves with its
   * citations. An `error` event or an interrupted stream rejects with an ApiError.
   */
  async ask(
    conversationId: string,
    content: string,
    deviceId: string | undefined,
    handlers: AnswerHandlers,
    signal?: AbortSignal,
  ): Promise<Citation[]> {
    const response = await this.request(`/agent/conversations/${encodeURIComponent(conversationId)}/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ content, ...(deviceId ? { context: { deviceId } } : {}) }),
      signal,
    })
    for await (const event of readEvents(response)) {
      const data = JSON.parse(event.data || '{}') as Record<string, unknown>
      switch (event.name) {
        case 'tool':
          handlers.onTool({ name: String(data.name), arguments: (data.arguments as Record<string, unknown>) ?? {} })
          break
        case 'delta':
          handlers.onDelta(String(data.text ?? ''))
          break
        case 'done':
          return (data.citations as Citation[]) ?? []
        case 'error':
          throw new ApiError(502, { title: String(data.title ?? 'Error'), detail: String(data.detail ?? '') })
      }
    }
    throw new ApiError(0, { title: 'Interrupted', detail: 'The answer was interrupted. Try again.' })
  }

  private async json<T>(path: string): Promise<T> {
    return (await (await this.request(path)).json()) as T
  }

  private async request(path: string, init: RequestInit = {}): Promise<Response> {
    const token = await this.session.getAccessToken()
    const headers = new Headers(init.headers)
    headers.set('Authorization', `Bearer ${token}`)
    if (!headers.has('Accept')) {
      headers.set('Accept', 'application/json')
    }
    let response: Response
    try {
      response = await fetch(`${this.baseUrl}${path}`, { ...init, headers })
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        throw error
      }
      throw new ApiError(0, { title: 'Network error' })
    }
    if (!response.ok) {
      let problem: Problem = { status: response.status }
      try {
        problem = { ...problem, ...((await response.json()) as Problem) }
      } catch {
        // Body is not JSON (e.g. an infrastructure error page).
      }
      throw new ApiError(response.status, problem)
    }
    return response
  }
}

/** Server-Sent Events of a fetch response (`event:` and `data:` fields; comments and pings are skipped). */
export async function* readEvents(response: Response): AsyncGenerator<{ name: string; data: string }> {
  const reader = response.body!.pipeThrough(new TextDecoderStream()).getReader()
  let buffer = ''
  for (;;) {
    const { value, done } = await reader.read()
    if (done) {
      return
    }
    buffer += value.replace(/\r\n?/g, '\n')
    let end: number
    while ((end = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, end)
      buffer = buffer.slice(end + 2)
      let name = 'message'
      const data: string[] = []
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) {
          name = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          data.push(line.slice(5).replace(/^ /, ''))
        }
      }
      if (data.length > 0) {
        yield { name, data: data.join('\n') }
      }
    }
  }
}
