import { useState, useCallback, Dispatch, SetStateAction } from "react"

function useSessionStorage(
    key: string,
    initialValue: string | null
): [string | null, Dispatch<SetStateAction<string | null>>] {
    const [storedValue, setStoredValue] = useState<string | null>(() => {
        try {
            const item = window.sessionStorage.getItem(key)
            return item ? (JSON.parse(item) as string) : initialValue
        } catch (error) {
            console.warn(error)
            return initialValue
        }
    })

    const setValue = useCallback<Dispatch<SetStateAction<string | null>>>(
        (value) => {
            try {
                const valueToStore =
                    value instanceof Function ? value(storedValue) : value
                setStoredValue(valueToStore)
                if (valueToStore === null) {
                    window.sessionStorage.removeItem(key)
                } else {
                    window.sessionStorage.setItem(
                        key,
                        JSON.stringify(valueToStore)
                    )
                }
            } catch (error) {
                console.warn(error)
            }
        },
        [key, storedValue]
    )

    return [storedValue, setValue]
}

export function sessionStore(value: string | null) {
    return useSessionStorage("user-id", value)
}

export function localStore(value: string | null) {
    return useState<string | null>(value)
}
