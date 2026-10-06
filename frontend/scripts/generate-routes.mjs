// Generates src/routeTree.gen.ts from the file routes in src/routes (the Vite plugin does the same
// during dev and build; type checking runs before the bundler, so it needs the file first).
import { resolve } from 'node:path';
import { Generator, getConfig } from '@tanstack/router-generator';

const root = resolve(import.meta.dirname, '..');
const config = getConfig(
  { target: 'react', routesDirectory: './src/routes', generatedRouteTree: './src/routeTree.gen.ts', autoCodeSplitting: true },
  root,
);
await new Generator({ config, root }).run();
console.log('Wrote src/routeTree.gen.ts');
