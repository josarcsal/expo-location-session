import { createServer as createHttp } from 'node:http'
import { createServer as createHttps } from 'node:https'
import { readFileSync } from 'node:fs'

const port = Number(process.env.PORT ?? 8443)
const { TLS_CERT, TLS_KEY } = process.env

let failNext = 0

const handler = (req, res) => {
  const url = new URL(req.url, 'http://localhost')
  if (url.pathname === '/fail') {
    failNext = Number(url.searchParams.get('n') ?? 1)
    console.log(`${new Date().toISOString()} next ${failNext} upload(s) -> 503`)
    res.writeHead(202)
    return res.end()
  }
  const status = failNext > 0 ? (failNext--, 503) : Number(url.searchParams.get('status') ?? 204)
  let body = ''
  req.on('data', (chunk) => (body += chunk))
  req.on('end', () => {
    console.log(`${new Date().toISOString()} ${req.method} ${url.pathname}${url.search} -> ${status} ${body}`)
    res.writeHead(status)
    res.end()
  })
}

const server = TLS_CERT && TLS_KEY
  ? createHttps({ cert: readFileSync(TLS_CERT), key: readFileSync(TLS_KEY) }, handler)
  : createHttp(handler)

server.listen(port, () => console.log(`echo server on ${TLS_CERT ? 'https' : 'http'}://localhost:${port}`))
