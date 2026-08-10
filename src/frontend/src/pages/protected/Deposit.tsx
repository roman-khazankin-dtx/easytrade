import React, { lazy } from "react"
import { Card, CardContent, Stack } from "@mui/material"
import DemoAppWarning from "../../components/DemoAppWarning"
import { deposit } from "../../api/creditCard/deposit/deposit"

const DepositForm = lazy(() => import("../../components/forms/DepositForm"))

export default function Deposit() {
    return (
        <Card
            sx={{
                margin: "auto",
                maxWidth: "450px",
            }}
        >
            <CardContent>
                <Stack
                    spacing={2}
                    alignItems="center"
                    justifyContent="center"
                    direction={"column"}
                >
                    <DemoAppWarning />
                    <DepositForm submitHandler={deposit} />
                </Stack>
            </CardContent>
        </Card>
    )
}
