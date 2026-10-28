import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '../../api/client'
import { useApi } from '../../api/context'
import type { Citation, ToolActivity } from '../../api/types'

export interface Turn {
  id: number
  question: string
  deviceId?: string
  answer: string
  tools: ToolActivity[]
  citations: Citation[]
  status: 'streaming' | 'done' | 'error'
  error?: string
}

/** One conversation with the assistant: created on the first question, recreated when it has expired. */
export function useAssistant() {
  const api = useApi()
  const [turns, setTurns] = useState<Turn[]>([])
  const conversation = useRef<string | null>(null)
  const abort = useRef<AbortController | null>(null)
  const nextId = useRef(1)

  useEffect(() => () => abort.current?.abort(), [])

  const update = (id: number, change: (turn: Turn) => Partial<Turn>) =>
    setTurns((all) => all.map((t) => (t.id === id ? { ...t, ...change(t) } : t)))

  const ask = useCallback(
    async (question: string, deviceId?: string) => {
      const id = nextId.current++
      const controller = new AbortController()
      abort.current = controller
      setTurns((all) => [...all, { id, question, deviceId, answer: '', tools: [], citations: [], status: 'streaming' }])
      const handlers = {
        onTool: (tool: ToolActivity) => update(id, (t) => ({ tools: [...t.tools, tool] })),
        onDelta: (text: string) => update(id, (t) => ({ answer: t.answer + text })),
      }
      try {
        let citations: Citation[]
        try {
          conversation.current ??= (await api.createConversation()).id
          citations = await api.ask(conversation.current, question, deviceId, handlers, controller.signal)
        } catch (error) {
          if (!(error instanceof ApiError && error.status === 404)) {
            throw error
          }
          // The conversation expired (one hour without messages): continue in a new one.
          conversation.current = (await api.createConversation()).id
          update(id, () => ({ answer: '', tools: [] }))
          citations = await api.ask(conversation.current, question, deviceId, handlers, controller.signal)
        }
        update(id, () => ({ citations, status: 'done' }))
      } catch (error) {
        if (controller.signal.aborted) {
          return
        }
        update(id, () => ({ status: 'error', error: describe(error) }))
      }
    },
    [api],
  )

  const reset = useCallback(() => {
    abort.current?.abort()
    const previous = conversation.current
    conversation.current = null
    setTurns([])
    if (previous) {
      void api.deleteConversation(previous).catch(() => undefined)
    }
  }, [api])

  return { turns, ask, reset, busy: turns.some((t) => t.status === 'streaming') }
}

function describe(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 429) {
      return error.problem.detail ?? 'You are sending messages too quickly. Wait a minute and try again.'
    }
    if (error.status === 403) {
      return 'You do not have permission to use the assistant.'
    }
    return error.problem.detail || error.message || 'The assistant could not answer. Try again.'
  }
  return 'The assistant could not answer. Try again.'
}
