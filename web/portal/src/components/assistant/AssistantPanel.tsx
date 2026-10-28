import {
  Body1,
  Button,
  Caption1,
  DrawerBody,
  DrawerFooter,
  DrawerHeader,
  DrawerHeaderTitle,
  Link,
  MessageBar,
  MessageBarBody,
  OverlayDrawer,
  Spinner,
  Tag,
  Textarea,
  makeStyles,
  tokens,
} from '@fluentui/react-components'
import { ArrowResetRegular, DatabaseSearchRegular, DismissRegular, SendRegular } from '@fluentui/react-icons'
import { type FormEvent, type KeyboardEvent, useEffect, useRef, useState } from 'react'
import { Link as RouterLink } from 'react-router'
import { AnswerText } from './AnswerText'
import { citationLink, toolLabel } from './assistantText'
import { type Turn, useAssistant } from './useAssistant'

const EXAMPLES = [
  'Which devices are silent right now?',
  'Compare the humidity of the sites over the last 7 days.',
  'Which batteries are declining at lyon-01?',
]

const useStyles = makeStyles({
  body: { display: 'flex', flexDirection: 'column', gap: tokens.spacingVerticalL },
  question: {
    alignSelf: 'flex-end',
    maxWidth: '85%',
    padding: `${tokens.spacingVerticalS} ${tokens.spacingHorizontalM}`,
    borderRadius: tokens.borderRadiusLarge,
    backgroundColor: tokens.colorBrandBackground2,
    whiteSpace: 'pre-wrap',
  },
  answer: { display: 'flex', flexDirection: 'column', gap: tokens.spacingVerticalXS },
  tools: { display: 'flex', flexDirection: 'column', gap: tokens.spacingVerticalXXS, color: tokens.colorNeutralForeground3 },
  tool: { display: 'flex', alignItems: 'center', gap: tokens.spacingHorizontalXS },
  sources: { display: 'flex', flexWrap: 'wrap', gap: tokens.spacingHorizontalS, alignItems: 'baseline' },
  examples: { display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: tokens.spacingVerticalXS },
  footer: { flexDirection: 'column', alignItems: 'stretch', gap: tokens.spacingVerticalS },
  form: { display: 'flex', gap: tokens.spacingHorizontalS, alignItems: 'flex-end' },
  input: { flexGrow: 1 },
})

/**
 * The AI assistant (M6): a side panel kept open while navigating, answering questions about devices, measurements and
 * alerts with the user's own permissions. On a device page, that device is the subject of questions.
 */
export function AssistantPanel({ open, onClose, deviceId }: { open: boolean; onClose: () => void; deviceId?: string }) {
  const styles = useStyles()
  const { turns, ask, reset, busy } = useAssistant()
  const [question, setQuestion] = useState('')
  const end = useRef<HTMLDivElement>(null)
  const last = turns.at(-1)

  useEffect(() => {
    end.current?.scrollIntoView?.({ block: 'end' })
  }, [last?.answer, last?.tools.length, turns.length])

  const submit = (event?: FormEvent) => {
    event?.preventDefault()
    const text = question.trim()
    if (text && !busy) {
      setQuestion('')
      void ask(text, deviceId)
    }
  }

  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      submit(event)
    }
  }

  return (
    <OverlayDrawer
      open={open}
      onOpenChange={(_, data) => !data.open && onClose()}
      modalType="non-modal"
      position="end"
      size="medium"
      aria-label="Assistant"
    >
      <DrawerHeader>
        <DrawerHeaderTitle
          action={
            <>
              <Button appearance="subtle" icon={<ArrowResetRegular />} onClick={reset} disabled={turns.length === 0}>
                New conversation
              </Button>
              <Button appearance="subtle" aria-label="Close the assistant" icon={<DismissRegular />} onClick={onClose} />
            </>
          }
        >
          Assistant
        </DrawerHeaderTitle>
      </DrawerHeader>
      <DrawerBody className={styles.body} aria-live="polite">
        {turns.length === 0 && (
          <div className={styles.examples}>
            <Body1>
              Ask about your devices, their measurements and alerts. Answers use the data you are allowed to see; the
              assistant cannot change anything.
            </Body1>
            {EXAMPLES.map((example) => (
              <Button key={example} appearance="transparent" size="small" onClick={() => setQuestion(example)}>
                {example}
              </Button>
            ))}
          </div>
        )}
        {turns.map((turn) => (
          <TurnView key={turn.id} turn={turn} />
        ))}
        <div ref={end} />
      </DrawerBody>
      <DrawerFooter className={styles.footer}>
        {deviceId && (
          <Caption1>
            About <Tag size="extra-small" appearance="brand">{deviceId}</Tag> unless you name another device
          </Caption1>
        )}
        <form className={styles.form} onSubmit={submit}>
          <Textarea
            className={styles.input}
            aria-label="Question"
            placeholder="Ask a question…"
            value={question}
            maxLength={2000}
            resize="vertical"
            onChange={(_, data) => setQuestion(data.value)}
            onKeyDown={onKeyDown}
          />
          <Button type="submit" appearance="primary" icon={<SendRegular />} disabled={busy || question.trim() === ''}>
            Send
          </Button>
        </form>
      </DrawerFooter>
    </OverlayDrawer>
  )
}

function TurnView({ turn }: { turn: Turn }) {
  const styles = useStyles()
  const links = turn.citations.map(citationLink).filter((l) => l !== null)
  return (
    <>
      <div className={styles.question}>{turn.question}</div>
      <div className={styles.answer}>
        {turn.tools.length > 0 && (
          <ul className={styles.tools} aria-label="Data consulted" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
            {turn.tools.map((tool, i) => (
              <li key={i} className={styles.tool}>
                <DatabaseSearchRegular aria-hidden />
                <Caption1>{toolLabel(tool)}</Caption1>
              </li>
            ))}
          </ul>
        )}
        {turn.answer ? (
          <div>
            <AnswerText text={turn.answer} />
          </div>
        ) : (
          turn.status === 'streaming' && <Spinner size="tiny" label="Thinking…" labelPosition="after" />
        )}
        {turn.status === 'error' && (
          <MessageBar intent="error">
            <MessageBarBody>{turn.error}</MessageBarBody>
          </MessageBar>
        )}
        {links.length > 0 && (
          <div className={styles.sources}>
            <Caption1>Sources:</Caption1>
            {links.map((link) => (
              <Link key={link.to} as="span" inline>
                <RouterLink to={link.to}>{link.label}</RouterLink>
              </Link>
            ))}
          </div>
        )}
      </div>
    </>
  )
}
