import { spawn } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');

function spawnLogged(command, args, label) {
  const child = spawn(command, args, {
    cwd: repoRoot,
    stdio: 'inherit',
    shell: process.platform === 'win32',
    env: process.env,
  });

  child.on('exit', (code, signal) => {
    if (signal) {
      console.error(`${label} stopped (${signal})`);
      process.exit(1);
    }
    if (code && code !== 0) {
      process.exit(code);
    }
  });

  return child;
}

const jnlpServer = spawnLogged('node', ['scripts/jnlp-static-server.mjs'], 'JNLP server');
const ngServe = spawnLogged(
  'npx',
  ['ng', 'serve', '--host', '127.0.0.1', '--port', '4200'],
  'Angular dev server',
);

function shutdown() {
  jnlpServer.kill();
  ngServe.kill();
  process.exit(0);
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
