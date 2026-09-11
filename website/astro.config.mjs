import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

// Base path is parameterized so CI can build the same source once per
// published path (e.g. /nf-diff/latest/, /nf-diff/0.6.0/).
const base = process.env.PUBLIC_BASE_PATH ?? '/nf-diff/latest/';

export default defineConfig({
  site: 'https://mribeirodantas.github.io',
  base,
  integrations: [
    starlight({
      title: 'nf-diff',
      description:
        'A Nextflow plugin that diffs two pipeline runs and renders a self-contained HTML report of everything that changed.',
      social: [
        { icon: 'github', label: 'GitHub', href: 'https://github.com/mribeirodantas/nf-diff' },
      ],
      editLink: {
        baseUrl: 'https://github.com/mribeirodantas/nf-diff/edit/main/docs/',
      },
      sidebar: [
        { label: 'Home', link: '/' },
        { label: 'Usage', link: '/usage/' },
        { label: 'Comparison layers', link: '/layers/' },
        { label: 'How it works', link: '/how-it-works/' },
        { label: 'Gallery', link: '/gallery/' },
        { label: 'Development', link: '/development/' },
      ],
      customCss: ['./src/styles/custom.css'],
    }),
  ],
});
