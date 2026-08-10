import React, { lazy, Suspense } from "react"
import { Card, CardHeader, Skeleton } from "@mui/material"
import InstrumentHeader from "./InstrumentHeader"
import { useInstrument } from "../../contexts/InstrumentContext/context"
import { useRouteLoaderData } from "react-router"
import { LoaderIds } from "../../router"
import { Price } from "../../api/price/types"
import { useInstrumentPricesQuery } from "../../contexts/QueryContext/price/hooks"

// Charts pull in recharts (~360 KiB); defer it so it stays out of the critical path.
const InstrumentPriceChart = lazy(() => import("../charts/InstrumentPriceChart"))

export default function FullInstrumentCard() {
    const { instrument } = useInstrument()
    const pricesData = useRouteLoaderData(LoaderIds.prices) as Price[]
    const { data } = useInstrumentPricesQuery(instrument.id, pricesData)

    return (
        <Card>
            <CardHeader
                title={<InstrumentHeader instrument={instrument} />}
                subheader={instrument.code}
                slotProps={{
                    subheader: {
                        sx: {
                            fontStyle: "italic",
                        },
                    },
                }}
            />
            <Suspense fallback={<Skeleton variant="rectangular" height={200} />}>
                <InstrumentPriceChart prices={data ?? []} />
            </Suspense>
        </Card>
    )
}
