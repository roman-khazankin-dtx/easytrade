import React, { lazy } from "react"
import { Card, CardContent, Stack } from "@mui/material"
import DemoAppWarning from "../../../components/DemoAppWarning"

const CreditCardForm = lazy(() => import("../../../components/creditCard/CreditCardForm"))

export default function CreditCardOrder() {
    return (
        <Card sx={{ padding: 1, maxWidth: "450px" }}>
            <CardContent>
                <Stack justifyContent="center" alignItems="center" spacing={2}>
                    <DemoAppWarning />
                    <CreditCardForm />
                </Stack>
            </CardContent>
        </Card>
    )
}
