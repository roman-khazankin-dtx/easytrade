import type { ComponentType } from "react"

type ComponentModule = { default: ComponentType }

/**
 * Adapts a dynamic `import()` of a page module (default export) into the shape
 * React Router's `lazy` route property expects. Keeps route definitions DRY by
 * removing the repeated `async () => ({ Component: (await import(...)).default })`
 * boilerplate and enables per-route code splitting.
 */
export const lazyRoute =
    (factory: () => Promise<ComponentModule>) =>
    async (): Promise<{ Component: ComponentType }> => ({
        Component: (await factory()).default,
    })
