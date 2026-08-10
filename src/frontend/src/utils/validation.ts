export const isCreditCard = (str: string) => /^\d{13,19}$/.test(str.replace(/[- ]/g, ''))
