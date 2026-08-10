import React from "react"
import {
    createBrowserRouter,
    createRoutesFromElements,
    Route,
} from "react-router"
import ProviderLayout from "./layouts/ProviderLayout"
import ProtectedLayout from "./layouts/ProtectedLayout"
import PublicLayout from "./layouts/PublicLayout"
import { queryClient } from "./contexts/QueryContext/QueryContext"
import { getUser, getPresetUsers, getBalance } from "./api/user/user"
import ErrorPage from "./pages/ErrorPage"
import {
    loadWithUser,
    presetUsersLoader,
    sessionUserProvider,
    balanceLoader,
    userLoader,
} from "./contexts/QueryContext/user/loaders"
import { instrumentsLoader } from "./contexts/QueryContext/instrument/loaders"
import { getInstruments } from "./api/instrument/instruments"
import { instrumentPricesLoader } from "./contexts/QueryContext/price/loaders"
import { getPricesForInstrument } from "./api/price/price"
import { transactionsLoader } from "./contexts/QueryContext/transaction/loaders"
import { getTransactions } from "./api/transaction/transactions"
import {
    creditCardStatusHistoryLoader,
    creditCardStatusLoader,
} from "./contexts/QueryContext/creditCard/loaders"
import { getOrderStatus, getOrderStatusHistory } from "./api/creditCard/order"
import CreditCardLayout from "./layouts/CreditCardLayout"
import { lazyRoute } from "./utils/lazyRoute"

export enum LoaderIds {
    user = "user-loader",
    instruments = "instruments-loader",
    transactions = "transactions-loader",
    creditCard = "creditCard-loader",
    creditCardStatusHistory = "creditCardStatusHistory-loader",
    prices = "prices-loader",
}

const elementRoutes = createRoutesFromElements(
    <Route path="/" element={<ProviderLayout />} errorElement={<ErrorPage />}>
        <Route index lazy={lazyRoute(() => import("./pages/BaseNavigation"))} />
        <Route path="*" lazy={lazyRoute(() => import("./pages/BaseNavigation"))} />
        <Route path="feature-flags" lazy={lazyRoute(() => import("./pages/FeatureFlags"))} />
        <Route path="version" lazy={lazyRoute(() => import("./pages/Version"))} />
        <Route element={<PublicLayout />}>
            <Route
                path="login"
                lazy={lazyRoute(() => import("./pages/public/Login"))}
                loader={presetUsersLoader(queryClient, getPresetUsers)}
            />
            <Route path="signup" lazy={lazyRoute(() => import("./pages/public/Signup"))} />
        </Route>
        <Route
            element={<ProtectedLayout />}
            loader={async () => {
                return await Promise.all([
                    loadWithUser(
                        sessionUserProvider,
                        userLoader(queryClient, getUser)
                    )(),
                    loadWithUser(
                        sessionUserProvider,
                        balanceLoader(queryClient, getBalance)
                    )(),
                ])
            }}
            id={LoaderIds.user}
        >
            <Route path="withdraw" lazy={lazyRoute(() => import("./pages/protected/Withdraw"))} />
            <Route path="deposit" lazy={lazyRoute(() => import("./pages/protected/Deposit"))} />
            <Route
                path="credit-card"
                element={<CreditCardLayout />}
                loader={loadWithUser(
                    sessionUserProvider,
                    creditCardStatusLoader(queryClient, getOrderStatus)
                )}
                id={LoaderIds.creditCard}
            >
                <Route path="order" lazy={lazyRoute(() => import("./pages/protected/creditCard/CreditCardOrder"))} />
                <Route
                    path="status"
                    lazy={lazyRoute(() => import("./pages/protected/creditCard/CreditCardStatus"))}
                    loader={loadWithUser(
                        sessionUserProvider,
                        creditCardStatusHistoryLoader(
                            queryClient,
                            getOrderStatusHistory
                        )
                    )}
                    id={LoaderIds.creditCardStatusHistory}
                />
                <Route path="active" lazy={lazyRoute(() => import("./pages/protected/creditCard/CreditCardActive"))} />
            </Route>
            <Route
                loader={loadWithUser(
                    sessionUserProvider,
                    instrumentsLoader(queryClient, getInstruments)
                )}
                id={LoaderIds.instruments}
            >
                <Route
                    path="home"
                    lazy={lazyRoute(() => import("./pages/protected/Home"))}
                    loader={loadWithUser(
                        sessionUserProvider,
                        transactionsLoader(queryClient, getTransactions)
                    )}
                    id={LoaderIds.transactions}
                />
                <Route path="instruments">
                    <Route index lazy={lazyRoute(() => import("./pages/protected/Instruments"))} />
                    <Route
                        path=":id"
                        lazy={lazyRoute(() => import("./pages/protected/Instrument"))}
                        loader={async ({ params }) => {
                            return await instrumentPricesLoader(
                                queryClient,
                                getPricesForInstrument
                            )(params.id as string)
                        }}
                        id={LoaderIds.prices}
                    />
                </Route>
            </Route>
        </Route>
    </Route>
)

export const router = createBrowserRouter(elementRoutes, {
    basename: import.meta.env.VITE_BASE_URL,
})
