import React, { lazy } from "react"
import { Card, CardContent, Stack } from "@mui/material"
import DemoAppWarning from "../../components/DemoAppWarning"
import { withdraw } from "../../api/creditCard/withdraw/withdraw"

const WithdrawForm = lazy(() => import("../../components/forms/WithdrawForm"))

export default function Withdraw() {
    return (
        <Card
            sx={{
                margin: "auto",
                maxWidth: "450px",
            }}
        >
            <CardContent>
                <Stack
                    direction="column"
                    spacing={2}
                    alignItems="center"
                    justifyContent="center"
                >
                    <DemoAppWarning />
                    <WithdrawForm submitHandler={withdraw} />
                </Stack>
            </CardContent>
        </Card>
    )
}
