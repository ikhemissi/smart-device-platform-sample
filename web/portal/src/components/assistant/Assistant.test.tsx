import { screen, waitFor, within } from '@testing-library/react'
import { HttpResponse, http } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { renderPortal } from '../../test/render'
import { API, READER } from '../../test/server'

// ECharts needs a canvas (device pages); the chart is tested elsewhere.
vi.mock('../TelemetryChart', () => ({ TelemetryChart: () => <div role="img" aria-label="chart" /> }))

/** Requests received by the fake assistant API. */
let agentRequests: Request[] = []

const ASSISTANT = { features: { assistant: true } }

/** A Server-Sent Events response, sent in several chunks (one event split across two of them). */
function sse(events: [string, unknown][]): Response {
  const text = events.map(([name, data]) => `event: ${name}\r\ndata: ${JSON.stringify(data)}\r\n\r\n`).join(': ping\r\n\r\n')
  const chunks = [text.slice(0, 25), text.slice(25, 90), text.slice(90)]
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(new TextEncoder().encode(chunk))
      }
      controller.close()
    },
  })
  return new Response(body, { headers: { 'Content-Type': 'text/event-stream' } })
}

function agent(answer: (body: { content: string; context?: { deviceId?: string } }, conversation: string) => Response) {
  let conversations = 0
  agentRequests = []
  return [
    http.post(`${API}/agent/conversations`, ({ request }) => {
      agentRequests.push(request)
      return HttpResponse.json({ id: `conv-${++conversations}`, createdAt: '', messages: [] }, { status: 201 })
    }),
    http.delete(`${API}/agent/conversations/:id`, ({ request }) => {
      agentRequests.push(request)
      return new HttpResponse(null, { status: 204 })
    }),
    http.post(`${API}/agent/conversations/:id/messages`, async ({ request, params }) => {
      agentRequests.push(request.clone())
      return answer((await request.json()) as { content: string }, String(params.id))
    }),
  ]
}

const PARIS_ANSWER: [string, unknown][] = [
  ['tool', { name: 'fleet_status', arguments: { site_id: 'paris-01' } }],
  ['delta', { text: '**9 of 10** devices report at paris-01:\n' }],
  ['delta', { text: '- paris-01-ruuvi5-001\n- paris-01-lpp-002' }],
  ['done', {
    conversationId: 'conv-1',
    citations: [
      { kind: 'site', siteId: 'paris-01' },
      { kind: 'device', deviceId: 'paris-01-ruuvi5-001', metrics: ['temperature'], from: '2026-10-21T12:00:00Z', to: '2026-10-28T12:00:00Z' },
    ],
  }],
]

async function openAndAsk(user: ReturnType<typeof renderPortal>['user'], question: string) {
  await user.click(await screen.findByRole('button', { name: 'Assistant' }))
  const panel = await screen.findByRole('dialog', { name: 'Assistant' })
  await user.type(within(panel).getByRole('textbox', { name: 'Question' }), question)
  await user.click(within(panel).getByRole('button', { name: 'Send' }))
  return panel
}

describe('assistant panel', () => {
  it('is hidden when the assistant is not deployed', async () => {
    renderPortal('/devices', READER)
    expect(await screen.findByRole('heading', { name: 'Devices', level: 1 })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Assistant' })).not.toBeInTheDocument()
  })

  it('streams the answer with the data consulted and citations as links', async () => {
    const { user, router } = renderPortal('/devices', READER, { ...ASSISTANT, handlers: agent(() => sse(PARIS_ANSWER)) })

    const panel = await openAndAsk(user, 'How many devices are reporting at paris-01?')

    expect(await within(panel).findByText('paris-01-lpp-002')).toBeInTheDocument()
    expect(within(panel).getByText('How many devices are reporting at paris-01?')).toBeInTheDocument()
    expect(within(panel).getByRole('list', { name: 'Data consulted' })).toHaveTextContent('Checking which devices report of paris-01')
    expect(within(panel).getByText('9 of 10').tagName).toBe('STRONG')
    expect(within(panel).getAllByRole('listitem').map((li) => li.textContent)).toContain('paris-01-ruuvi5-001')

    const device = await within(panel).findByRole('link', { name: /^paris-01-ruuvi5-001 ·/ })
    expect(device).toHaveAttribute('href',
      '/devices/paris-01-ruuvi5-001?period=custom&from=2026-10-21T12%3A00%3A00Z&to=2026-10-28T12%3A00%3A00Z&metrics=temperature')
    await user.click(within(panel).getByRole('link', { name: 'Site paris-01' }))
    expect(router.state.location.pathname).toBe('/sites/paris-01')
    expect(screen.getByRole('dialog', { name: 'Assistant' })).toBeInTheDocument()

    const ask = agentRequests.find((r) => r.url.endsWith('/messages'))!
    expect(ask.headers.get('Authorization')).toMatch(/^Bearer test-token-/)
    expect(ask.headers.get('Accept')).toBe('text/event-stream')
  })

  it('sends the device of the page as context', async () => {
    let received: unknown
    const { user } = renderPortal('/devices/lyon-01-ruuvi5-001', READER, {
      ...ASSISTANT,
      handlers: agent((body) => {
        received = body
        return sse([['delta', { text: 'The battery is fine.' }], ['done', { citations: [] }]])
      }),
    })

    const panel = await openAndAsk(user, 'Is the battery OK?')

    expect(within(panel).getByText('lyon-01-ruuvi5-001')).toBeInTheDocument()
    expect(await within(panel).findByText('The battery is fine.')).toBeInTheDocument()
    expect(received).toEqual({ content: 'Is the battery OK?', context: { deviceId: 'lyon-01-ruuvi5-001' } })
  })

  it('keeps the conversation for follow-up questions and starts a new one on request', async () => {
    const seen: string[] = []
    const { user } = renderPortal('/devices', READER, {
      ...ASSISTANT,
      handlers: agent((body, conversation) => {
        seen.push(conversation)
        return sse([['delta', { text: `Answer to ${body.content}` }], ['done', { citations: [] }]])
      }),
    })

    const panel = await openAndAsk(user, 'first')
    await within(panel).findByText('Answer to first')
    await user.type(within(panel).getByRole('textbox', { name: 'Question' }), 'second{Enter}')
    await within(panel).findByText('Answer to second')
    expect(seen).toEqual(['conv-1', 'conv-1'])

    await user.click(within(panel).getByRole('button', { name: 'New conversation' }))
    expect(within(panel).queryByText('Answer to first')).not.toBeInTheDocument()
    await waitFor(() => expect(agentRequests.some((r) => r.method === 'DELETE' && r.url.endsWith('/conv-1'))).toBe(true))
    await user.type(within(panel).getByRole('textbox', { name: 'Question' }), 'third{Enter}')
    await within(panel).findByText('Answer to third')
    expect(seen.at(-1)).toBe('conv-2')
  })

  it('continues in a new conversation when the previous one expired', async () => {
    const { user } = renderPortal('/devices', READER, {
      ...ASSISTANT,
      handlers: agent((_, conversation) =>
        conversation === 'conv-1'
          ? HttpResponse.json({ title: 'Not Found', status: 404 }, { status: 404 })
          : sse([['delta', { text: 'Fresh answer' }], ['done', { citations: [] }]]),
      ),
    })

    const panel = await openAndAsk(user, 'hello')

    expect(await within(panel).findByText('Fresh answer')).toBeInTheDocument()
  })

  it('explains rate limiting and stream errors', async () => {
    let calls = 0
    const { user } = renderPortal('/devices', READER, {
      ...ASSISTANT,
      handlers: agent(() =>
        ++calls === 1
          ? HttpResponse.json({ title: 'Too Many Requests', status: 429, detail: 'At most 20 messages per minute; retry in 12 s.' }, { status: 429 })
          : sse([['tool', { name: 'get_device', arguments: { device_id: 'x' } }], ['error', { title: 'Timeout', detail: 'The answer took too long.' }]]),
      ),
    })

    const panel = await openAndAsk(user, 'one')
    expect(await within(panel).findByText('At most 20 messages per minute; retry in 12 s.')).toBeInTheDocument()
    await user.type(within(panel).getByRole('textbox', { name: 'Question' }), 'two{Enter}')
    expect(await within(panel).findByText('The answer took too long.')).toBeInTheDocument()
    expect(within(panel).getByText('Reading x')).toBeInTheDocument()
  })
})
