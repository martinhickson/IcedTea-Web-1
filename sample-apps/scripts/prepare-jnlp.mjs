import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { SAMPLE_IDS, isSampleId } from './samples.mjs';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const requested = process.argv.slice(2).filter(Boolean);
const samples = requested.length ? requested : SAMPLE_IDS;

for (const sampleId of samples) {
  if (!isSampleId(sampleId)) {
    console.error(`Unknown sample: ${sampleId}`);
    process.exit(1);
  }
}

const isWindows = process.platform === 'win32';
const buildScript = isWindows
  ? join(repoRoot, 'scripts', 'build-jnlp.ps1')
  : join(repoRoot, 'scripts', 'build-jnlp.sh');

for (const sampleId of samples) {
  console.log(`Building JNLP sample: ${sampleId}`);
  const env = { ...process.env, ITW_SAMPLE: sampleId };
  const result = isWindows
    ? spawnSync(
        'powershell',
        ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', buildScript, '-Sample', sampleId],
        { stdio: 'inherit', cwd: repoRoot, env },
      )
    : spawnSync('bash', [buildScript, sampleId], { stdio: 'inherit', cwd: repoRoot, env });

  if ((result.status ?? 1) !== 0) {
    process.exit(result.status ?? 1);
  }
}
