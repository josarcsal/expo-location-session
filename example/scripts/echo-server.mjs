import { createServer as createHttp } from 'node:http';
import { createServer as createHttps } from 'node:https';
import { readFileSync } from 'node:fs';

const port = Number(process.env.PORT ?? 8443);
const { TLS_CERT, TLS_KEY } = process.env;

let failNext = 0;

const handler = (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname === '/fail') {
    failNext = Number(url.searchParams.get('n') ?? 1);
    console.log(`${new Date().toISOString()} next ${failNext} upload(s) -> 503`);
    res.writeHead(202);
    return res.end();
  }
  if (url.pathname === '/logs') return logs(req, res, url);
  if (url.pathname === '/refresh') return refresh(req, res, url);
  const auth = url.searchParams.get('auth');
  const authorized = !auth || (auth !== 'never' && req.headers.authorization === 'Bearer fresh');
  const status = !authorized ? 401 : failNext > 0 ? (failNext--, 503) : Number(url.searchParams.get('status') ?? 204);
  let body = '';
  req.on('data', (chunk) => (body += chunk));
  req.on('end', () => {
    console.log(`${new Date().toISOString()} ${req.method} ${url.pathname}${url.search} -> ${status} ${body}`);
    res.writeHead(status, status >= 300 && status < 400 ? { Location: '/redirected' } : undefined);
    res.end();
  });
};

const refresh = (req, res, url) => {
  const failed = url.searchParams.get('fail') === '1';
  let body = '';
  req.on('data', (chunk) => (body += chunk));
  req.on('end', () => {
    console.log(`${new Date().toISOString()} POST /refresh${url.search} -> ${failed ? 500 : 200} ${body}`);
    if (failed) {
      res.writeHead(500);
      return res.end();
    }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ headers: { Authorization: 'Bearer fresh' } }));
  });
};

const logs = (req, res, url) => {
  const status = Number(url.searchParams.get('status') ?? 204);
  const delayMs = Number(url.searchParams.get('delayMs') ?? 0);
  let body = '';
  req.on('data', (chunk) => (body += chunk));
  req.on('end', () => {
    let summary;
    try {
      const { install, lines } = JSON.parse(body);
      const seqs = lines.map((line) => line.seq);
      summary = `install=${install?.id} lines=${lines.length} seq=${Math.min(...seqs)}..${Math.max(...seqs)} auth=${req.headers.authorization ?? '-'}`;
      if (process.env.LOG_LINES) lines.forEach((line) => console.log(`  ${JSON.stringify(line)}`));
    } catch {
      summary = `unparseable body (${body.length} bytes)`;
    }
    console.log(`${new Date().toISOString()} POST /logs${url.search} -> ${status} after ${delayMs}ms ${summary}`);
    setTimeout(() => {
      res.writeHead(status);
      res.end();
    }, delayMs);
  });
};

const server =
  TLS_CERT && TLS_KEY
    ? createHttps({ cert: readFileSync(TLS_CERT), key: readFileSync(TLS_KEY) }, handler)
    : createHttp(handler);

server.listen(port, () => console.log(`echo server on ${TLS_CERT ? 'https' : 'http'}://localhost:${port}`));
