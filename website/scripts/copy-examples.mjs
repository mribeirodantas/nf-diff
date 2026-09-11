// Copies the repo's sample HTML reports into public/ so the Gallery page can
// embed them; keeps a single source of truth instead of duplicating the files.
import { copyFileSync, mkdirSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const websiteDir = dirname(fileURLToPath(import.meta.url)).replace(/\/scripts$/, '');
const repoRoot = join(websiteDir, '..');
const destDir = join(websiteDir, 'public', 'examples');

mkdirSync(destDir, { recursive: true });

const files = [
  [join(repoRoot, 'nf-diff-report.html'), join(destDir, 'nf-diff-report.html')],
  [join(repoRoot, 'examples', 'rich-report', 'report.html'), join(destDir, 'rich-report.html')],
];

for (const [src, dest] of files) {
  if (existsSync(src)) {
    copyFileSync(src, dest);
    console.log(`copied ${src} -> ${dest}`);
  } else {
    console.warn(`skipped missing sample report: ${src}`);
  }
}
