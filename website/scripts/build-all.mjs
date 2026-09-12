import { execSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const websiteDir = dirname(fileURLToPath(import.meta.url)).replace(/\/scripts$/, '');
const distDir = join(websiteDir, 'dist');
const tempDistDir = join(websiteDir, '.dist-temp');
const versionsFile = join(websiteDir, 'src', 'config', 'versions.json');
const versions = JSON.parse(readFileSync(versionsFile, 'utf-8'));

console.log('Building all documentation versions...');

// Clean and prepare temporary directory
if (existsSync(tempDistDir)) {
  rmSync(tempDistDir, { recursive: true, force: true });
}
mkdirSync(tempDistDir, { recursive: true });

for (const v of versions) {
  console.log(`\n========================================`);
  console.log(`Building version: ${v.label} (base: ${v.path})`);
  console.log(`========================================`);

  try {
    execSync('npx astro build', {
      cwd: websiteDir,
      env: {
        ...process.env,
        PUBLIC_BASE_PATH: v.path,
      },
      stdio: 'inherit',
    });

    const targetSubDir = join(tempDistDir, v.version);
    mkdirSync(targetSubDir, { recursive: true });
    cpSync(distDir, targetSubDir, { recursive: true });
  } catch (error) {
    console.error(`Failed to build version ${v.version}:`, error);
    process.exit(1);
  }
}

// Create root redirect in temp directory
const rootRedirectHtml = `<!doctype html>
<meta charset="utf-8">
<meta http-equiv="refresh" content="0; url=/nf-diff/latest/">
<link rel="canonical" href="/nf-diff/latest/">
<p><a href="/nf-diff/latest/">Continue to the nf-diff docs</a></p>
`;
writeFileSync(join(tempDistDir, 'index.html'), rootRedirectHtml);

// Replace dist with tempDist
rmSync(distDir, { recursive: true, force: true });
mkdirSync(distDir, { recursive: true });
cpSync(tempDistDir, distDir, { recursive: true });
rmSync(tempDistDir, { recursive: true, force: true });

console.log('\nSuccessfully built all versions to dist/:');
for (const v of versions) {
  console.log(`  - ${v.label}: dist/${v.version}/`);
}
