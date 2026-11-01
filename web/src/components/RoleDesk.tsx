import { useEffect, useState } from 'react'
import type { Contract } from '../api.ts'
import { holdFor, nextAction, type Role } from '../role.ts'
import { money, time } from '../model.ts'
import type { DealActions } from './DealView.tsx'
import { CancelDeal, Jars, canAskPayPal } from './DealView.tsx'
import { SellerCounteroffer } from './SellerCounteroffer.tsx'
import { SellerDelivery } from './SellerDelivery.tsx'
import { SellerOpportunity } from './SellerOpportunity.tsx'
import { BuyerJobResult, isJobResult } from './BuyerJobResult.tsx'

const primary = 'inline-flex min-h-11 w-full items-center justify-center rounded-xl bg-[#2563eb] px-4 py-2 text-[13px] font-semibold text-white disabled:opacity-40 sm:w-auto'
const secondary = 'inline-flex min-h-11 w-full items-center justify-center rounded-xl border border-[var(--bm-highest)] bg-white px-4 py-2 text-[13px] font-semibold disabled:opacity-40 sm:w-auto'
const confirmBtn = 'inline-flex min-h-11 w-full items-center justify-center rounded-xl bg-held px-4 py-2 text-[13px] font-semibold text-white disabled:opacity-40 sm:w-auto'

/** One person's desk: both jars, only their next action. The other side is a line, not a second cockpit. */
export function RoleDesk({ role, deal, actions = {} }: { role: Role; deal: Contract; actions?: DealActions }) {
  const next = nextAction(role, deal)
  const mine = holdFor(role, deal)
  const other = role === 'buyer' ? deal.seller : deal.buyer
  const me = role === 'buyer' ? deal.buyer : deal.seller
  const [negotiating, setNegotiating] = useState(false)

  useEffect(() => { setNegotiating(false) }, [deal.id])

  if (role === 'seller' && deal.state === 'DRAFT' && negotiating) {
    return <SellerCounteroffer deal={deal} onBack={() => { setNegotiating(false); window.scrollTo({ top: 0 }) }} />
  }
  if (role === 'seller' && deal.state === 'DRAFT') {
    return <SellerOpportunity deal={deal} onNegotiate={() => { setNegotiating(true); window.scrollTo({ top: 0 }) }} />
  }
  if (role === 'seller') return <SellerDelivery deal={deal} actions={actions} />
  if (role === 'buyer' && isJobResult(deal)) return <BuyerJobResult deal={deal} actions={actions} />

  return (
    <div className="flex min-w-0 flex-col gap-6">
      <p className="text-sm text-[var(--bm-on-var)]">You are {me.displayName} · the {role}</p>
      {(deal.needsAttention || actions.error) && (
        <p role="alert" className="rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-800">{actions.error ?? deal.attentionReason}</p>
      )}
      <section className="flex min-w-0 flex-col gap-4 rounded-2xl bg-white p-6 shadow-sm">
        <p className="text-[11px] font-semibold uppercase tracking-[0.6px] text-[#2563eb]">Next for you</p>
        <h2 className="break-words text-[30px] leading-tight tracking-[-0.5px] text-[#0f172a]">{next.title}</h2>
        {next.body && <p className="max-w-2xl break-words text-[var(--bm-on-var)]">{next.body}</p>}
        {next.kind === 'approve' && mine?.approveUrl && (
          <a className={primary} href={mine.approveUrl} target="_blank" rel="noreferrer">
            Approve the {role === 'buyer' ? 'fee' : 'bond'} on PayPal · {money(mine.amountMinor)}
          </a>
        )}
        {next.kind === 'approve' && mine?.needsAttention && <p className="text-sm text-red-700">{mine.attentionReason}</p>}
        {mine && canAskPayPal(mine) && (
          <button type="button" disabled={actions.busy} onClick={() => actions.checkHold?.(mine.id)} className={secondary}>
            Ask PayPal again
          </button>
        )}
        {deal.state === 'TERMS_LOCKED' && actions.cancel && <CancelDeal busy={actions.busy} onCancel={actions.cancel} />}
        {next.kind === 'confirm' && (
          <button type="button" disabled={actions.busy} onClick={() => actions.confirm?.()} className={confirmBtn}>
            Confirm the first capture
          </button>
        )}
      </section>
      <Jars deal={deal} />
      <section className="min-w-0 rounded-2xl bg-white p-6 text-sm shadow-sm">
        <h3 className="text-xl font-semibold">{other.displayName}</h3>
        <p className="text-[var(--bm-on-var)]">the {role === 'buyer' ? "seller's" : "buyer's"} agent</p>
        {deal.jobText && <p className="mt-2 break-words">{deal.jobText}</p>}
        {deal.delivery && (
          <p className="mt-2 break-words text-[var(--bm-on-var)]">Delivered {time(deal.delivery.submittedAt)}: “{deal.delivery.text.slice(0, 140)}{deal.delivery.text.length > 140 ? '…' : ''}”</p>
        )}
        {deal.delivery?.verdict && (
          <p className={`mt-2 text-2xl font-semibold ${deal.delivery.verdict === 'PASS' ? 'text-paid' : 'text-red-800'}`}>{deal.delivery.verdict}{deal.delivery.summary ? ` · ${deal.delivery.summary}` : ''}</p>
        )}
      </section>
    </div>
  )
}
