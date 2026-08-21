import {
    IProvider,
    IVisit,
    WeightedProvider,
    createLoggerOptions,
} from "@demoability/loadgen-core"
import { Config } from "../config"
import { getRegularProviderFunction } from "./regularVisits"
import { RateLimitedProviderWrapper } from "@demoability/loadgen-core/lib/providers"
import { TIME_UNITS } from "@demoability/loadgen-core/lib/time"

/**
 * Return true if the NYSE is opened at the given time.
 * It assumes open time to be 14:30-21:00 UTC
 * @param now
 */
function isNYSEOpenHours(now: Date): boolean {
    return (
        ((now.getUTCHours() === 14 && now.getUTCMinutes() > 30) ||
            now.getUTCHours() >= 15) &&
        now.getUTCHours() < 21
    )
}

export function getProvider(config: Config): IProvider<IVisit> {
    const regularVisitProvider = new WeightedProvider(
        config.regularVisitsWeights,
        getRegularProviderFunction(config)
    )
    if (config.providerConfig.type === "constant") {
        return regularVisitProvider
    }
    const { learnTimeFactor, timeframeMinutes, offHoursLoadFactor } =
        config.providerConfig
    return new RateLimitedProviderWrapper(
        regularVisitProvider,
        { unit: TIME_UNITS.MINUTE, quantity: timeframeMinutes },
        (now: Date) => {
            if (isNYSEOpenHours(now)) {
                return 1
            }
            return offHoursLoadFactor
        },
        learnTimeFactor,
        createLoggerOptions("provider", { logLevel: config.logLevel })
    )
}
