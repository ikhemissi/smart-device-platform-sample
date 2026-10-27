import { Body1, Button, FluentProvider, Title2, webLightTheme } from '@fluentui/react-components'

export function StartupError({ error }: { error: unknown }) {
  return (
    <FluentProvider theme={webLightTheme}>
      <main style={{ maxWidth: '40rem', margin: '4rem auto' }}>
        <Title2 as="h1">The portal could not start</Title2>
        <Body1 as="p">Sign-in or the portal configuration failed. Reload the page; if the problem persists, contact your administrator.</Body1>
        <Body1 as="p"><code>{error instanceof Error ? error.message : String(error)}</code></Body1>
        <Button appearance="primary" onClick={() => window.location.reload()}>Reload</Button>
      </main>
    </FluentProvider>
  )
}
