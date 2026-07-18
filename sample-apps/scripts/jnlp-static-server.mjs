import { createReadStream, existsSync } from 'node:fs';
import { join, normalize, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createServer } from 'node:http';

const repoRoot = join(fileURLToPath(new URL('.', import.meta.url)), '..');
const publicJnlpRoot = join(repoRoot, 'jnlp-dist');
const port = Number(process.env.ITW_SAMPLE_JNLP_BACKEND_PORT ?? 4201);

const MIME_BY_EXT = {
  '.jnlp': 'application/x-java-jnlp-file',
  '.jar': 'application/java-archive',
  '.ico': 'image/x-icon',
};

function resolveJnlpFile(urlPath) {
  const decoded = decodeURIComponent(urlPath.split('?')[0] ?? '');
  if (!decoded.startsWith('/jnlp/')) {
    return null;
  }

  const relative = decoded.slice('/jnlp/'.length);
  if (!relative || relative.includes('..')) {
    return null;
  }

  const filePath = normalize(join(publicJnlpRoot, ...relative.split('/')));
  if (!filePath.startsWith(publicJnlpRoot + sep) && filePath !== publicJnlpRoot) {
    return null;
  }

  return existsSync(filePath) ? filePath : null;
}

const server = createServer((req, res) => {
  const filePath = resolveJnlpFile(req.url ?? '');
  if (!filePath) {
    res.statusCode = 404;
    res.end('Not found');
    return;
  }

  const ext = filePath.slice(filePath.lastIndexOf('.')).toLowerCase();
  const contentType = MIME_BY_EXT[ext] ?? 'application/octet-stream';
  res.setHeader('Content-Type', contentType);
  res.setHeader('Cache-Control', 'no-cache');
  createReadStream(filePath).pipe(res);
});

server.listen(port, '127.0.0.1', () => {
  console.log(`JNLP static server listening on http://127.0.0.1:${port}/jnlp/`);
});
