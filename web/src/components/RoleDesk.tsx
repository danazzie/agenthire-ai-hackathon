import type { Contract } from '../api.ts'
import { holdFor, nextAction, type Role } from '../role.ts'
import { money, time } from '../model.ts'
import type { DealActions } from './DealView.tsx'
import { Jars } from './DealView.tsx'

/** One person's desk: both jars, only their next action. The other side is a line, not a second cockpit. */
export function RoleDesk({ role, deal, actions = {} }: { role: Role; deal: Contract; actions?: DealActions }) {
  const next = nextAction(role, deal)
  const mine = holdFor(role, deal)
  const other = role === 'buyer' ? deal.seller : deal.buyer
  const me = role === 'buyer' ? deal.buyer : deal.seller

  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-ink-soft">You are {me.displayName} · the {role}</p>
      {(deal.needsAttention || actions.error) && (
        <p role="alert" className="rounded-lg bg-red-50 px-4 py-2 text-sm text-red-800">{actions.error ?? deal.attentionReason}</p>
      )}
      <section className="flex flex-col gap-3 rounded-lg border border-rule bg-paper p-5">
        <h2 className="font-display text-3xl font-medium tracking-tight">{next.title}</h2>
        {next.body && <p className="max-w-2xl text-ink-soft">{next.body}</p>}
        {next.kind === 'approve' && mine?.approveUrl && (
          <a className="inline-flex min-h-11 w-full items-center justify-center rounded-sm bg-ink px-4 py-2 text-sm font-medium text-paper sm:w-auto" href={mine.approveUrl} target="_blank" rel="noreferrer">
            Approve the {role === 'buyer' ? 'fee' : 'bond'} on PayPal · {money(mine.amountMinor)}
          </a>
        )}
        {next.kind === 'approve' && mine?.needsAttention && <p className="text-sm text-red-700">{mine.attentionReason}</p>}
        {next.kind === 'deliver' && (
          <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap">
            <button type="button" disabled={actions.busy} onClick={() => actions.deliver?.('AGENT')} className="min-h-11 w-full rounded-sm bg-ink px-3 py-2 text-sm font-medium text-paper disabled:opacity-40 sm:w-auto">
              Seller's agent writes it (and tests it first)
            </button>
            <button type="button" disabled={actions.busy} onClick={() => actions.deliver?.('SCRIPTED_JUNK')} className="min-h-11 w-full rounded-sm border border-rule bg-paper px-3 py-2 text-sm font-medium disabled:opacity-40 sm:w-auto">
              Scripted junk delivery
            </button>
            <button type="button" disabled={actions.busy} onClick={() => actions.deliver?.('SCRIPTED_PASS')} className="min-h-11 w-full rounded-sm bg-paid px-3 py-2 text-sm font-medium text-paper disabled:opacity-40 sm:w-auto">
              Scripted 50-word delivery
            </button>
          </div>
        )}
        {next.kind === 'confirm' && (
          <button type="button" disabled={actions.busy} onClick={() => actions.confirm?.()} className="min-h-11 w-full self-start rounded-sm bg-held px-4 py-2 text-sm font-semibold text-paper disabled:opacity-40 sm:w-auto">
            Confirm the first capture
          </button>
        )}
      </section>
      <Jars deal={deal} />
      <section className="rounded-lg border border-rule bg-paper p-4 text-sm">
        <h3 className="font-display text-xl font-medium">{other.displayName}</h3>
        <p className="text-ink-soft">the {role === 'buyer' ? "seller's" : "buyer's"} agent</p>
        {deal.jobText && <p className="mt-2">{deal.jobText}</p>}
        {deal.delivery && (
          <p className="mt-2 text-ink-soft">Delivered {time(deal.delivery.submittedAt)}: “{deal.delivery.text.slice(0, 140)}{deal.delivery.text.length > 140 ? '…' : ''}”</p>
        )}
        {deal.delivery?.verdict && (
          <p className={`mt-2 font-display text-2xl ${deal.delivery.verdict === 'PASS' ? 'text-paid' : 'text-red-800'}`}>{deal.delivery.verdict}{deal.delivery.summary ? ` · ${deal.delivery.summary}` : ''}</p>
        )}
      </section>
    </div>
  )
}
