import { defineConfig } from 'vite'
import { fileURLToPath, URL } from 'node:url'

function resolve(path) {
    return fileURLToPath(new URL(path, import.meta.url))
}

export default defineConfig({
    build: {
        outDir: resolve('./dist/mjml/'),
        emptyOutDir: true,
        minify: true,
        lib: {
            name: 'MJML', // global variable
            formats: ['iife'], // define on load
            entry: resolve('./index.js'),
            fileName: () => 'mjml.js',
        }
    }
})
