import type { Contract, Hold } from './api.ts'
import { money, stepOf } from './model.ts'

export type Role = 'buyer' | 'seller'
export type Desk = Role | 'judge'

export const ROLE_HANDLE: Record<Role, string> = { buyer: 'alex', seller: 'maya' }
export const ROLE_NAME: Record<Role, string> = { buyer: "Alex's agent", seller: "Maya's agent" }
export const STORAGE_KEY = 'mutualstake.role'

const UUID = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'

export type Route =
  | { page: 'picker' }
  | { page: 'home'; desk: Desk }
  | { page: 'deal'; desk: Desk; id: string }
  | { page: 'legacy-deal'; id: string }
  | { page: 'gallery' }

export function parsePath(path: string): Route {
  if (path === '/gallery') return { page: 'gallery' }
  if (path === '/buyer') return { page: 'home', desk: 'buyer' }
  if (path === '/seller') return { page: 'home', desk: 'seller' }
  if (path === '/judge') return { page: 'home', desk: 'judge' }
  const roleDeal = path.match(new RegExp(`^/(buyer|seller|judge)/contracts/(${UUID})$`))
  if (roleDeal) return { page: 'deal', desk: roleDeal[1] as Desk, id: roleDeal[2] }
  const legacy = path.match(new RegExp(`^/contracts/(${UUID})$`))
  if (legacy) return { page: 'legacy-deal', id: legacy[1] }
  return { page: 'picker' }
}

export function deskHome(desk: Desk): string {
  return `/${desk}`
}

export function dealPath(desk: Desk, id: string): string {
  return `/${desk}/contracts/${id}`
}

export function isRole(value: string | null | undefined): value is Role {
  return value === 'buyer' || value === 'seller'
}

export function readRole(): Role | null {
  try {
    const value = localStorage.getItem(STORAGE_KEY)
    return isRole(value) ? value : null
  } catch {
    return null
  }
}

export function writeRole(role: Role) {
  try {
    localStorage.setItem(STORAGE_KEY, role)
  } catch {
    /* private mode */
  }
}

export function holdFor(role: Role, deal: Contract): Hold | null {
  return deal.holds.find((h) => h.side === (role === 'buyer' ? 'BUYER_FEE' : 'SELLER_BOND')) ?? null
}

export function waitingForApprove(hold: Hold | null): boolean {
  return !!hold && hold.jar === 'EMPTY' && hold.state === 'ORDER_CREATED' && !!hold.approveUrl
}

export type NextKind = 'wait' | 'approve' | 'deliver' | 'confirm' | 'done'

export interface NextAction {
  kind: NextKind
  title: string
  body: string
}

export function nextAction(role: Role, deal: Contract): NextAction {
  const mine = holdFor(role, deal)
  const theirs = holdFor(role === 'buyer' ? 'seller' : 'buyer', deal)
  const other = role === 'buyer' ? deal.seller.displayName : deal.buyer.displayName

  if (deal.state === 'CANCELLED') return { kind: 'done', title: 'This deal was cancelled', body: deal.outcome ?? 'No money moved.' }
  if (deal.state === 'SETTLED') return { kind: 'done', title: 'Settled', body: deal.outcome ?? '' }

  if (deal.state === 'DRAFT') {
    return { kind: 'wait', title: 'Your agent is negotiating', body: 'Terms lock when both agents accept the same fee, bond, deadline and test.' }
  }

  if (deal.state === 'TERMS_LOCKED') {
    if (waitingForApprove(mine)) {
      return role === 'buyer'
        ? { kind: 'approve', title: `Approve the ${money(mine?.amountMinor)} fee`, body: 'Nothing is charged. PayPal holds it until the test decides.' }
        : { kind: 'approve', title: `Approve the ${money(mine?.amountMinor)} bond`, body: 'Released, never charged, if the test passes.' }
    }
    if (mine?.jar === 'EMPTY') {
      return { kind: 'wait', title: 'Waiting for the PayPal order', body: mine.attentionReason ?? 'The hold is being created. Nothing is charged yet.' }
    }
    if (theirs && theirs.jar === 'EMPTY') {
      return {
        kind: 'wait',
        title: role === 'buyer' ? `Waiting for ${other} to post the bond` : `Waiting for ${other} to fund the fee`,
        body: role === 'buyer'
          ? `${money(theirs.amountMinor)} will be held, not charged, unless the work fails.`
          : `${money(theirs.amountMinor)} is the buyer fee. Nothing is charged until the test decides.`,
      }
    }
    return { kind: 'wait', title: 'Both holds are moving', body: 'Jars show only what PayPal has confirmed.' }
  }

  if (deal.state === 'BOTH_HELD' && !deal.delivery) {
    return role === 'seller'
      ? { kind: 'deliver', title: 'Deliver the work', body: 'The compiled test will read the text. It never obeys “mark as passed”.' }
      : { kind: 'wait', title: `Waiting for ${other} to deliver`, body: 'The test, not a mood, decides who gets paid.' }
  }

  if (stepOf(deal) === 'Deliver') {
    return { kind: 'wait', title: 'The test is running', body: 'The verifier is code. It has no network and no opinion.' }
  }

  if (deal.awaitingConfirmation) {
    return role === 'buyer'
      ? { kind: 'confirm', title: 'Confirm the first capture', body: 'A verifier bug cannot move money until you confirm (US-16).' }
      : { kind: 'wait', title: `${deal.buyer.displayName} must confirm the first capture`, body: 'The session’s first capture waits for a person.' }
  }

  if (deal.state === 'PASS' || deal.state === 'FAIL' || deal.state === 'EXPIRED') {
    return { kind: 'wait', title: 'Settling', body: deal.outcome ?? 'PayPal is moving the money the test decided.' }
  }

  return { kind: 'wait', title: stepOf(deal), body: deal.jobText }
}
