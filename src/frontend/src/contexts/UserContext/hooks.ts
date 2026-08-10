import { useRouteLoaderData } from "react-router"
import { Balance, User } from "../../api/user/types"
import { useBalanceQuery, useUserQuery } from "../QueryContext/user/hooks"
import { useAuthUser } from "./context"
import { LoaderIds } from "../../router"

export function useAuthUserData(): { user?: User; balance?: Balance } {
    const { userId } = useAuthUser()
    const loaderData = useRouteLoaderData<[User, Balance]>(LoaderIds.user)
    const [userData, balanceData] = loaderData ?? []
    return {
        user: useUserQuery(userId, userData).data,
        balance: useBalanceQuery(userId, balanceData).data,
    }
}
