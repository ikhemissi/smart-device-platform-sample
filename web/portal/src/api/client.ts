import type { Session } from '../auth/session'
import type {
  CurrentUser,
  DeviceDetails,
  DeviceEdit,
  DevicePage,
  DeviceQuery,
  Measurement,
  Problem,
  ReportedMetric,
  Series,
  Site,
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

  private async json<T>(path: string): Promise<T> {
    return (await (await this.request(path)).json()) as T
  }

  private async request(path: string, init: RequestInit = {}): Promise<Response> {
    const token = await this.session.getAccessToken()
    const headers = new Headers(init.headers)
    headers.set('Authorization', `Bearer ${token}`)
    headers.set('Accept', 'application/json')
    let response: Response
    try {
      response = await fetch(`${this.baseUrl}${path}`, { ...init, headers })
    } catch {
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
