import React from "react"
import { Navigate, Outlet, useLoaderData, useLocation } from "react-router"
import { useAuthUser } from "../contexts/UserContext/context"
import { OrderStatusResponse } from "../api/creditCard/order"
import { useCreditCardOrderStatus } from "../contexts/QueryContext/creditCard/hooks"
import { Alert, AlertTitle, Box, Button, Stack } from "@mui/material"

export default function CreditCardLayout() {
    const { userId } = useAuthUser()
    const orderStatus: OrderStatusResponse = useLoaderData()
    const { data, isError, refetch } = useCreditCardOrderStatus(
        userId,
        orderStatus
    )

    const { pathname } = useLocation()

    if (isError || data === undefined || data.type === "error") {
        return (
            <Box sx={{ display: "flex", m: "auto", maxWidth: 480 }}>
                <Alert severity="error" variant="outlined" sx={{ width: "100%" }}>
                    <AlertTitle>Credit card status unavailable</AlertTitle>
                    <Stack spacing={2} alignItems="flex-start">
                        <span>
                            {(data?.type === "error" && data.error) ||
                                "We couldn't load your credit card order status right now. Please try again."}
                        </span>
                        <Button
                            variant="outlined"
                            color="inherit"
                            size="small"
                            onClick={() => void refetch()}
                        >
                            Retry
                        </Button>
                    </Stack>
                </Alert>
            </Box>
        )
    }
    if (data.type === "not_found" && !pathname.includes("order")) {
        return <Navigate to="/credit-card/order" />
    }
    if (data.type === "success") {
        if (data.status === "card_delivered" && !pathname.includes("active")) {
            return <Navigate to="/credit-card/active" />
        }
        if (data.status !== "card_delivered" && !pathname.includes("status")) {
            return <Navigate to="/credit-card/status" />
        }
    }

    return (
        <Box sx={{ display: "flex", m: "auto" }}>
            <Outlet />
        </Box>
    )
}
