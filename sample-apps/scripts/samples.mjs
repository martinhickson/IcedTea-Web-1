import { join } from 'node:path';

export const SAMPLE_IDS = ['swing-gui', 'console'];
export const DEFAULT_PORT = '4200';

export function isSampleId(value) {
  return SAMPLE_IDS.includes(value);
}

export function sampleRoot(repoRoot, sampleId) {
  return join(repoRoot, sampleId);
}

export function jnlpOutputDir(repoRoot, sampleId) {
  return join(repoRoot, 'jnlp-dist', sampleId);
}

export function codebaseUrl(sampleId, port = process.env.ITW_SAMPLE_PORT ?? DEFAULT_PORT) {
  return `http://127.0.0.1:${port}/jnlp/${sampleId}/`;
}
