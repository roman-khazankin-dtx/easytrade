/// <reference types="vitest" />
/// <reference types="vite/client" />

import react from "@vitejs/plugin-react-swc"
import { defineConfig } from "vite"

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
    console.log(`VITE getting config for [${mode}]`)
    return {
        plugins: [react()],
        server: {
            port: 3000,
            allowedHosts: true,
        },
        build: {
            rollupOptions: {
                output: {
                    // We isolate core React dependencies here to keep them cached.
                    manualChunks: {
                        "react-vendor": ["react", "react-dom", "react-router"],
                    },
                },
            },
        },
    }
})
