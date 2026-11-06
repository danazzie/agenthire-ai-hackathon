/** Two decimals, so 40/150 is 26.67 and recomputes to 40 cents, not 41. */
export function ratioOf(bondMinor: number, feeMinor: number): string {
  const pct = Math.round((bondMinor / feeMinor) * 10000) / 100
  return String(pct)
}

export function bondCents(feeMinor: number, ratioPercent: number): number {
  return Math.round((feeMinor * ratioPercent) / 100)
}
