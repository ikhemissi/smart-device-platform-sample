import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { createMsal } from './auth/msal'
import { loadConfig } from './config'
import './global.css'
import { StartupError } from './StartupError'

const root = createRoot(document.getElementById('root')!)
try {
  const config = await loadConfig()
  const msal = await createMsal(config)
  root.render(
    <StrictMode>
      <App msal={msal} config={config} />
    </StrictMode>,
  )
} catch (error) {
  root.render(<StartupError error={error} />)
}
