import { defineConfig } from 'rolldown'

export default defineConfig({
    input: 'index.js',
    platform: 'browser',
    resolve: {
        alias: {
            // MJML always passes the `xml` option to `cheerio.load`, which uses htmlparser2.
            // The slim build omits the unused parse5 parser and shrinks the bundle.
            cheerio: 'cheerio/slim',
        },
    },
    output: {
        file: 'dist/mjml/mjml.js',
        format: 'iife',
        name: 'MJML', // global variable
        minify: true,
    },
})
