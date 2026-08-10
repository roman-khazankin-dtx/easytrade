import React from "react"
import DarkMode from "@mui/icons-material/DarkMode"
import LightMode from "@mui/icons-material/LightMode"
import { IconButton } from "@mui/material"
import { useTheme } from "../contexts/ThemeContext/ThemeContext"

export function ThemeSwitcher() {
    const { isDarkTheme, toggleTheme } = useTheme()

    return (
        <IconButton aria-label="button" onClick={toggleTheme}>
            {isDarkTheme ? <DarkMode /> : <LightMode />}
        </IconButton>
    )
}
