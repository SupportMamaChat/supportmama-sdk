// dist/index.mjs      ES module (npm, bundlers)
// dist/core.mjs       the protocol client alone, for custom UIs
// dist/supportmama.js script tag; sets window.SupportMama
import { build } from 'esbuild';

const common = { bundle: true, minify: true, sourcemap: true, target: 'es2020', legalComments: 'none' };
await Promise.all([
  build({ ...common, entryPoints: ['src/index.ts'], format: 'esm', outfile: 'dist/index.mjs' }),
  build({ ...common, entryPoints: ['src/core.ts'], format: 'esm', outfile: 'dist/core.mjs' }),
  build({ ...common, entryPoints: ['src/global.ts'], format: 'iife', outfile: 'dist/supportmama.js' }),
]);
