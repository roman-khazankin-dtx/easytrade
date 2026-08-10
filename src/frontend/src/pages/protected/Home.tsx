import React, { lazy } from "react"
import { Container, Stack } from "@mui/material"
import AccountInfo from "../../components/AccountInfo"
import { useTransactionQuery } from "../../contexts/QueryContext/transaction/hooks"
import { useInstrumentsQuery } from "../../contexts/QueryContext/instrument/hooks"
import { useLoaderData, useRouteLoaderData } from "react-router"
import { Instrument } from "../../api/instrument/types"
import { useAuthUser } from "../../contexts/UserContext/context"
import { Transaction } from "../../api/transaction/types"
import { LoaderIds } from "../../router"

// Charts pull in recharts (~360 KiB) and the tables pull in @mui/x-data-grid (~800 KiB);
// defer them so they stay out of the critical path for the initial paint.
const InstrumentsChart = lazy(
    () => import("../../components/charts/InstrumentsChart")
)
const TransactionsCharts = lazy(
    () => import("../../components/charts/TransactionsCharts")
)
const InstrumentsTable = lazy(() => import("../../components/InstrumentsTable"))
const TransactionsTable = lazy(() => import("../../components/TransactionsTable"))


export default function Home() {
    const { userId } = useAuthUser()
    const transactionData: Transaction[] = useLoaderData()
    const transactionsData = useTransactionQuery(userId, transactionData)
    const instrumentData = useRouteLoaderData(
        LoaderIds.instruments
    ) as Instrument[]
    const instruments = useInstrumentsQuery(userId, instrumentData)
        .data as Instrument[]

    return (
        <Container>
            <Stack spacing={2}>
                <AccountInfo />
                <InstrumentsChart instruments={instruments} />
                <InstrumentsTable instruments={instruments} />
                <TransactionsCharts
                    transactions={transactionsData.data ?? []}
                />
                <TransactionsTable
                    transactions={transactionsData.data ?? []}
                    instruments={instruments}
                />
            </Stack>
        </Container>
    )
}
