// What the screen says, derived only from the control plane's views (which follow PayPal). Pure functions, tested.
import type { Contract, Jar } from './api.ts'

export const STEPS = ['Post job', 'Negotiate', 'Fund', 'Deliver', 'Verdict', 'Settle', 'Appeal'] as const
export type Step = (typeof STEPS)[number]

/** The step a deal is on, from its state. Appeal is not built in this version. */
export function stepOf(c: Contract | null): Step {
  if (!c) return 'Post job'
  switch (c.state) {
    case 'DRAFT':
      return 'Negotiate'
    case 'TERMS_LOCKED':
    case 'CANCELLED':
      return 'Fund'
    case 'BOTH_HELD':
      return 'Deliver'
    case 'DELIVERED':
      return c.delivery?.verdict ? 'Verdict' : 'Deliver'
    case 'PASS':
    case 'FAIL':
    case 'EXPIRED':
      return c.settlementPlannedAt ? 'Settle' : 'Verdict'
    case 'SETTLED':
    case 'APPEALED':
      return 'Settle'
    default:
      return 'Post job'
  }
}

/** PROTOTYPES' five jar states, plus Captured: PayPal has taken the money but the payout has not landed yet. */
export const JAR_LABEL: Record<Jar, string> = {
  EMPTY: 'Empty',
  HELD: 'Held',
  CAPTURED: 'Captured',
  PAID: 'Paid',
  RELEASED: 'Released',
  COMPENSATION: 'Compensation',
}

export function money(cents: number | null | undefined): string {
  if (cents == null) return '—'
  const sign = cents < 0 ? '-' : ''
  const abs = Math.abs(Math.trunc(cents))
  return `${sign}$${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`
}

/** sha256:7f3a…c9e1, as PROTOTYPES shows it. */
export function shortHash(hex: string | null | undefined): string {
  return hex ? `sha256:${hex.slice(0, 4)}…${hex.slice(-4)}` : '—'
}

export function time(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

/** The deal is done moving: nothing in flight and nothing more to come. */
export function settled(c: Contract): boolean {
  return (c.state === 'SETTLED' || c.state === 'CANCELLED') && c.inFlight.length === 0
}
