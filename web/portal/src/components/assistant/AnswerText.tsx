import { makeStyles, tokens } from '@fluentui/react-components'
import { Fragment, type ReactNode } from 'react'

const useStyles = makeStyles({
  paragraph: { margin: `0 0 ${tokens.spacingVerticalS}` },
  list: { margin: `0 0 ${tokens.spacingVerticalS}`, paddingLeft: tokens.spacingHorizontalXL },
  code: { fontFamily: tokens.fontFamilyMonospace, fontSize: tokens.fontSizeBase200 },
})

/**
 * The assistant's answer, which uses a small subset of Markdown: paragraphs, bullet and numbered lists, **bold** and
 * `code`. Rendered as React elements (never as HTML), so text from the model cannot inject markup.
 */
export function AnswerText({ text }: { text: string }) {
  const styles = useStyles()
  const blocks: ReactNode[] = []
  let list: { ordered: boolean; items: string[] } | null = null
  let paragraph: string[] = []

  const flush = () => {
    if (paragraph.length) {
      blocks.push(<p key={blocks.length} className={styles.paragraph}>{inline(paragraph.join(' '), styles.code)}</p>)
      paragraph = []
    }
    if (list) {
      const items = list.items.map((item, i) => <li key={i}>{inline(item, styles.code)}</li>)
      blocks.push(list.ordered
        ? <ol key={blocks.length} className={styles.list}>{items}</ol>
        : <ul key={blocks.length} className={styles.list}>{items}</ul>)
      list = null
    }
  }

  for (const raw of text.split('\n')) {
    const line = raw.trim()
    const bullet = /^[-*•]\s+(.*)$/.exec(line)
    const numbered = /^\d+[.)]\s+(.*)$/.exec(line)
    if (bullet || numbered) {
      if (paragraph.length || (list && list.ordered !== Boolean(numbered))) {
        flush()
      }
      list ??= { ordered: Boolean(numbered), items: [] }
      list.items.push((bullet ?? numbered)![1])
    } else if (line === '') {
      flush()
    } else {
      if (list) {
        flush()
      }
      paragraph.push(line.replace(/^#+\s+/, ''))
    }
  }
  flush()
  return <>{blocks}</>
}

function inline(text: string, codeClass: string): ReactNode {
  return text.split(/(\*\*[^*]+\*\*|`[^`]+`)/g).map((part, i) => {
    if (part.startsWith('**') && part.endsWith('**') && part.length > 4) {
      return <strong key={i}>{part.slice(2, -2)}</strong>
    }
    if (part.startsWith('`') && part.endsWith('`') && part.length > 2) {
      return <code key={i} className={codeClass}>{part.slice(1, -1)}</code>
    }
    return <Fragment key={i}>{part}</Fragment>
  })
}
