export function formatINR(amount: number | string): string {
  const num = typeof amount === "number" ? amount : Number(amount)
  if (isNaN(num)) {
    return String(amount)
  }
  return `₹${num.toLocaleString("en-IN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`
}

export function getCurrencySymbol(currency: string = "INR"): string {
  if (currency === "INR") return "₹"
  if (currency === "USD") return "$"
  if (currency === "EUR") return "€"
  if (currency === "GBP") return "£"
  return currency
}
