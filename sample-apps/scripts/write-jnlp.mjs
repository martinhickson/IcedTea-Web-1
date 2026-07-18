import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { codebaseUrl, isSampleId, jnlpOutputDir, sampleRoot } from './samples.mjs';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const sampleId = process.env.ITW_SAMPLE ?? process.argv[2];

if (!sampleId || !isSampleId(sampleId)) {
  console.error(`Sample id required (${['swing-gui', 'console'].join(', ')}).`);
  process.exit(1);
}

const templatePath = join(sampleRoot(repoRoot, sampleId), 'jnlp', 'app.jnlp.template');
const outDir = jnlpOutputDir(repoRoot, sampleId);
mkdirSync(outDir, { recursive: true });

const template = readFileSync(templatePath, 'utf8');
const jnlp = template.replace('@CODEBASE@', codebaseUrl(sampleId));
writeFileSync(join(outDir, 'app.jnlp'), jnlp, { encoding: 'utf8' });
