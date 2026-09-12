import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const websiteDir = path.dirname(fileURLToPath(import.meta.url)).replace(/\/scripts$/, '');
const distDir = path.join(websiteDir, 'dist');
const port = process.env.PORT ? parseInt(process.env.PORT, 10) : 4321;

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.xml': 'application/xml',
  '.txt': 'text/plain; charset=utf-8',
  '.map': 'application/json',
};

function resolveFile(urlPath) {
  const cleanPath = decodeURIComponent(urlPath.split('?')[0].split('#')[0]);

  if (cleanPath === '/' || cleanPath === '/nf-diff' || cleanPath === '/nf-diff/') {
    return { redirect: '/nf-diff/latest/' };
  }

  let relative = cleanPath.startsWith('/nf-diff/')
    ? cleanPath.slice('/nf-diff/'.length)
    : cleanPath.replace(/^\//, '');

  const candidatePaths = [
    path.join(distDir, relative),
    path.join(distDir, relative, 'index.html'),
    path.join(distDir, relative + '.html'),
  ];

  if (relative.startsWith('latest/')) {
    const unversioned = relative.slice('latest/'.length);
    candidatePaths.push(
      path.join(distDir, unversioned),
      path.join(distDir, unversioned, 'index.html'),
      path.join(distDir, unversioned + '.html')
    );
  }

  for (const p of candidatePaths) {
    if (fs.existsSync(p) && fs.statSync(p).isFile()) {
      return { filePath: p };
    }
  }

  return { notFound: true };
}

const server = http.createServer((req, res) => {
  const result = resolveFile(req.url || '/');

  if (result.redirect) {
    res.writeHead(302, { Location: result.redirect });
    res.end();
    return;
  }

  if (result.notFound || !result.filePath) {
    res.writeHead(404, { 'Content-Type': 'text/html; charset=utf-8' });
    if (req.method !== 'HEAD') {
      res.end('<h1>404 Not Found</h1>');
    } else {
      res.end();
    }
    return;
  }

  const ext = path.extname(result.filePath).toLowerCase();
  const contentType = MIME_TYPES[ext] || 'application/octet-stream';
  const stat = fs.statSync(result.filePath);

  res.writeHead(200, {
    'Content-Type': contentType,
    'Content-Length': stat.size,
  });

  if (req.method === 'HEAD') {
    res.end();
    return;
  }

  const stream = fs.createReadStream(result.filePath);
  stream.pipe(res);
});

server.listen(port, () => {
  console.log(`\n  nf-diff docs preview server running at:`);
  console.log(`  > http://localhost:${port}/nf-diff/latest/\n`);
});
