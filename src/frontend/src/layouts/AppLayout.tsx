import React, { Suspense } from "react"
import { Stack, CircularProgress, Box } from "@mui/material"
import AppHeader from "../components/AppHeader/AppHeader"
import { Outlet } from "react-router"
import { useTheme } from "../contexts/ThemeContext/ThemeContext"

export default function AppLayout() {
    const { themeMode } = useTheme()
    return (
        <Stack
            component="main"
            sx={{ display: "flex", minHeight: "100vh" }}
            spacing={5}
            data-dt-properties={`theme:${themeMode}`}
        >
            <AppHeader />
            <Suspense fallback={
                <Box sx={{ display: 'flex', justifyContent: 'center', p: 4 }}>
                    <CircularProgress />
                </Box>
            }>
                <Outlet />
            </Suspense>
        </Stack>
    )
}
