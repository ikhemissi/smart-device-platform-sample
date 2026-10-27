// Serves the production build like Azure Static Web Apps: navigation fallback, global and route headers (CSP…) from
// the same generator as the deployed staticwebapp.config.json, for a fake environment (fixtures.ts).
import { createServer } from 'node:http'
import { readFile, stat } from 'node:fs/promises'
import { extname, join, normalize } from 'node:path'
import { portalFiles } from '../scripts/write-config.mjs'
import { FAKE_ENV, PORT } from './env.mjs'

const dist = new URL('../dist/', import.meta.url).pathname
const { config, staticWebApp } = portalFiles(FAKE_ENV)
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml' }
const glob = (pattern) => new RegExp(`^${pattern.replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*')}$`)
const excluded = staticWebApp.navigationFallback.exclude.map(glob)

createServer(async (req, res) => {
  const path = decodeURIComponent(new URL(req.url, 'http://localhost').pathname)
  for (const [name, value] of Object.entries(staticWebApp.globalHeaders)) res.setHeader(name, value)
  for (const route of staticWebApp.routes) if (glob(route.route).test(path)) for (const [n, v] of Object.entries(route.headers)) res.setHeader(n, v)

  if (path === '/config.json') {
    res.setHeader('Content-Type', 'application/json')
    return res.end(JSON.stringify(config))
  }
  let file = join(dist, normalize(path).replace(/^(\.\.[/\\])+/, ''))
  const isFile = await stat(file).then((s) => s.isFile(), () => false)
  if (!isFile) {
    if (excluded.some((pattern) => pattern.test(path))) {
      res.statusCode = 404
      return res.end('Not found')
    }
    file = join(dist, 'index.html')
  }
  res.setHeader('Content-Type', TYPES[extname(file)] ?? 'application/octet-stream')
  res.end(await readFile(file))
}).listen(PORT, () => console.log(`Static Web App emulator on http://localhost:${PORT}`))
