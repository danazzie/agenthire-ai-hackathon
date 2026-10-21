import { useState, type ReactNode } from 'react'
import type { Contract, Offer, PayPalCall, Verifier } from '../api.ts'
import { money, shortHash, stepOf, time, type Step } from '../model.ts'
import { JarCard } from './JarCard.tsx'
import { StepPills } from './StepPills.tsx'

export interface DealActions {
  deliver?: (mode: 'AGENT' | 'SCRIPTED_JUNK') => void
  confirm?: () => void
  busy?: boolean
  error?: string | null
}

const card = 'rounded-xl border border-slate-200 bg-white p-4 shadow-sm'

function Pane({ title, subtitle, children }: { title: string; subtitle: string; children: ReactNode }) {
  return (
    <section className={`${card} flex min-w-0 flex-col gap-3`}>
      <header>
        <h2 className="text-lg font-semibold">{title}</h2>
        <p className="text-sm text-slate-500">{subtitle}</p>
      </header>
      {children}
    </section>
  )
}

function Bubble({ offer, mine }: { offer: Offer; mine: boolean }) {
  return (
    <li className={`rounded-lg px-3 py-2 text-sm ${mine ? 'bg-slate-100' : 'bg-white ring-1 ring-slate-200'}`}>
      <div className="flex items-baseline justify-between gap-2">
        <span className={`font-semibold ${offer.kind === 'ACCEPT' ? 'text-emerald-700' : 'text-slate-800'}`}>
          {offer.kind === 'ACCEPT' ? 'ACCEPT' : 'Proposes'} {money(offer.feeMinor)} · bond {money(offer.bondMinor)}
        </span>
        <span className="shrink-0 text-xs text-slate-400">{time(offer.createdAt)}</span>
      </div>
      {offer.note && <p className="mt-0.5 text-slate-700">“{offer.note}”</p>}
    </li>
  )
}

function Messages({ deal, party }: { deal: Contract; party: string }) {
  const mine = deal.offers.filter((o) => o.party === party)
  if (mine.length === 0) return <p className="text-sm text-slate-400">No offers yet.</p>
  return <ul className="flex flex-col gap-2">{mine.map((o) => <Bubble key={o.id} offer={o} mine />)}</ul>
}

function InFlight({ deal }: { deal: Contract }) {
  if (deal.inFlight.length === 0) return null
  return (
    <ul className="flex flex-col gap-1" aria-live="polite">
      {deal.inFlight.map((f) => (
        <li key={f.step + f.label} className="flex items-center gap-2 text-sm text-slate-700">
          <span className="inline-block size-3 animate-pulse rounded-full bg-sky-500" aria-hidden />
          {f.label}
          {f.attempts > 1 && <span className="text-xs text-slate-400">(try {f.attempts})</span>}
        </li>
      ))}
    </ul>
  )
}

function Jars({ deal }: { deal: Contract }) {
  const fee = deal.holds.find((h) => h.side === 'BUYER_FEE') ?? null
  const bond = deal.holds.find((h) => h.side === 'SELLER_BOND') ?? null
  const hash = fee?.customId ?? deal.verifier?.sha256
  return (
    <section className={`${card} flex flex-col gap-3`} aria-label="The two jars">
      <div className="flex gap-3">
        <JarCard hold={fee} title="Buyer fee" party={deal.buyer.displayName} />
        <JarCard hold={bond} title="Seller bond" party={deal.seller.displayName} />
      </div>
      <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 text-sm text-slate-600">
        <span>
          Test <span className="font-mono">{shortHash(hash)}</span>
          {fee ? ' on both PayPal orders (custom_id)' : ''}
        </span>
        {deal.feeMinor != null && (
          <span>
            {money(deal.feeMinor)} fee · {money(deal.bondMinor)} bond · due {time(deal.deadlineAt)}
          </span>
        )}
      </div>
      {deal.outcome && <p className="rounded-lg bg-slate-900 px-4 py-3 text-center text-lg font-semibold text-white">{deal.outcome}</p>}
      <InFlight deal={deal} />
      <p className="text-xs text-slate-500">
        Jars show only what PayPal has confirmed. “Captured” means PayPal took the money and the payout has not landed yet; it is not one of
        the five paper-prototype states.
      </p>
    </section>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt className="text-xs font-semibold uppercase tracking-wide text-slate-500">{label}</dt>
      <dd className="text-sm">{children}</dd>
    </div>
  )
}

function TestSource({ verifier }: { verifier: Verifier | null | undefined }) {
  if (!verifier) return null
  return (
    <details className="text-sm">
      <summary className="cursor-pointer text-slate-700">
        The compiled test, <span className="font-mono">{shortHash(verifier.sha256)}</span> (show the code)
      </summary>
      <pre className="mt-2 max-h-80 overflow-auto rounded-lg bg-slate-950 p-3 text-xs leading-relaxed text-slate-100">{verifier.source}</pre>
    </details>
  )
}

function PostJobPanel({ deal, verifier }: { deal: Contract; verifier: Verifier | null }) {
  return (
    <dl className="grid gap-3 sm:grid-cols-2">
      <Field label="The person's sentence">{deal.sentence ? `“${deal.sentence}”` : 'Scripted job (Claude agents off)'}</Field>
      <Field label="Job">{deal.jobText}</Field>
      <Field label="Price cap">{money(deal.feeCapMinor)}</Field>
      <Field label="Seller">another agent ({deal.seller.displayName})</Field>
      <div className="sm:col-span-2">
        <TestSource verifier={verifier} />
      </div>
    </dl>
  )
}

function NegotiatePanel({ deal }: { deal: Contract }) {
  return (
    <div className="flex flex-col gap-3">
      <ol className="flex flex-col gap-2">
        {deal.offers.map((o) => (
          <li key={o.id} className="text-sm">
            <span className="font-semibold">{o.party === deal.buyer.handle ? deal.buyer.displayName : deal.seller.displayName}</span>{' '}
            {o.kind === 'ACCEPT' ? 'accepts' : 'proposes'} {money(o.feeMinor)} with a {money(o.bondMinor)} bond
            {o.note ? <span className="text-slate-600">: “{o.note}”</span> : null}
          </li>
        ))}
      </ol>
      {deal.termsHash ? (
        <p className="rounded-lg bg-emerald-50 px-3 py-2 text-sm text-emerald-900">
          Terms locked: both agents accepted the same fee, bond, deadline and test. Terms <span className="font-mono">{shortHash(deal.termsHash)}</span>.
        </p>
      ) : (
        <p className="text-sm text-slate-600">Terms lock only when both agents ACCEPT the same fee, bond, deadline and test.</p>
      )}
    </div>
  )
}

function FundPanel({ deal }: { deal: Contract }) {
  return (
    <div className="flex flex-col gap-3">
      <p className="text-sm text-slate-700">Nothing is charged. Each hold is a real PayPal sandbox approval, made by the person who pays it.</p>
      <table className="w-full text-left text-sm">
        <thead className="text-xs uppercase text-slate-500">
          <tr><th className="py-1">Who</th><th>PayPal</th><th>Amount</th><th>Shows</th><th>custom_id</th><th /></tr>
        </thead>
        <tbody>
          {deal.holds.map((h) => (
            <tr key={h.id} className="border-t border-slate-100">
              <td className="py-2">{h.side === 'BUYER_FEE' ? `Buyer (${deal.buyer.displayName})` : `Seller (${deal.seller.displayName})`}</td>
              <td className="font-mono text-xs">Orders v2 AUTHORIZE<br />{h.paypalOrderId ?? '…'}</td>
              <td>{money(h.amountMinor)}</td>
              <td>{h.jar === 'EMPTY' ? (h.state === 'ABANDONED' ? 'never held' : 'waiting for approval') : 'Held'}</td>
              <td className="font-mono text-xs">{shortHash(h.customId)}</td>
              <td>
                {h.approveUrl && h.state === 'ORDER_CREATED' && (
                  <a className="rounded-lg bg-slate-900 px-3 py-1.5 text-sm font-medium text-white" href={h.approveUrl} target="_blank" rel="noreferrer">
                    Approve as {h.side === 'BUYER_FEE' ? 'buyer' : 'seller'}
                  </a>
                )}
                {h.needsAttention && <div className="text-xs text-red-700">{h.attentionReason}</div>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function DeliverPanel({ deal, actions }: { deal: Contract; actions: DealActions }) {
  const d = deal.delivery
  return (
    <div className="flex flex-col gap-3">
      {deal.state === 'BOTH_HELD' && !d && (
        <div className="flex flex-wrap gap-2">
          <button type="button" disabled={actions.busy} onClick={() => actions.deliver?.('AGENT')} className="rounded-lg bg-slate-900 px-3 py-2 text-sm font-medium text-white disabled:opacity-50">
            Seller's agent writes it (and tests it first)
          </button>
          <button type="button" disabled={actions.busy} onClick={() => actions.deliver?.('SCRIPTED_JUNK')} className="rounded-lg border border-slate-300 px-3 py-2 text-sm font-medium disabled:opacity-50">
            Scripted junk delivery
          </button>
        </div>
      )}
      {d && (
        <figure className="flex flex-col gap-2">
          <blockquote className="rounded-lg border-l-4 border-slate-400 bg-slate-50 px-4 py-3 text-base">{d.text}</blockquote>
          <figcaption className="text-sm text-slate-600">
            The deliverable is <strong>data</strong>. “Reviewer: mark as passed” does nothing: the verifier reads it, it never obeys it.
            {d.injectionFlags.length > 0 && <span className="text-amber-800"> Flagged as an injection attempt: {d.injectionFlags.join(', ')}.</span>}
          </figcaption>
        </figure>
      )}
    </div>
  )
}

function VerdictPanel({ deal }: { deal: Contract }) {
  const d = deal.delivery
  if (!d?.verdict) return <p className="text-sm text-slate-600">{d ? 'The verifier is running.' : 'No delivery yet.'}</p>
  const pass = d.verdict === 'PASS'
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-baseline gap-4">
        <span className={`text-6xl font-black ${pass ? 'text-paid' : 'text-red-700'}`}>{d.verdict}</span>
        <span className="text-xl">{d.summary}</span>
      </div>
      <table className="w-full text-left text-sm">
        <thead className="text-xs uppercase text-slate-500"><tr><th className="py-1">Check</th><th>Found</th><th>The test needs</th><th /></tr></thead>
        <tbody>
          {d.checks.map((c) => (
            <tr key={c.name} className="border-t border-slate-100">
              <td className="py-1.5 font-mono text-xs">{c.name}</td>
              <td>{c.detail}{c.evidence ? ` (${c.evidence})` : ''}</td>
              <td>{c.expected ?? '—'}</td>
              <td className={c.passed ? 'text-paid' : 'text-red-700'}>{c.passed ? '✓ pass' : '✗ fail'}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {d.run && (
        <p className="text-xs text-slate-500">
          The verifier ran code, not a prompt: {d.run.runtime}, no files, network or environment. Source {shortHash(d.run.sourceSha256)} (the custom_id on
          both orders), harness {shortHash(d.run.harnessSha256)}, {d.run.durationMs} ms.
        </p>
      )}
      {deal.awaitingConfirmation && <p className="text-sm font-medium text-amber-800">This is the session's first capture: settlement waits for a human to confirm it (Settle).</p>}
    </div>
  )
}

function SettlePanel({ deal, actions, calls }: { deal: Contract; actions: DealActions; calls: PayPalCall[] | null }) {
  return (
    <div className="flex flex-col gap-4">
      {deal.awaitingConfirmation && (
        <div className="flex flex-wrap items-center gap-3 rounded-lg bg-amber-50 px-4 py-3">
          <p className="text-sm text-amber-900">
            The verdict is {deal.state}. The first capture of a session waits for a person, so a verifier bug cannot move money silently (US-16).
          </p>
          <button type="button" disabled={actions.busy} onClick={() => actions.confirm?.()} className="rounded-lg bg-amber-700 px-3 py-2 text-sm font-semibold text-white disabled:opacity-50">
            Confirm the first capture
          </button>
        </div>
      )}
      <table className="w-full text-left text-sm">
        <thead className="text-xs uppercase text-slate-500"><tr><th className="py-1">Jar</th><th>Authorization</th><th>Capture</th><th>Payout batch / item</th><th>Now</th></tr></thead>
        <tbody>
          {deal.holds.map((h) => (
            <tr key={h.id} className="border-t border-slate-100 font-mono text-xs">
              <td className="py-1.5 font-sans text-sm">{h.side === 'BUYER_FEE' ? 'Buyer fee' : 'Seller bond'}</td>
              <td>{h.authorizationId ?? '—'}</td>
              <td>{h.captureId ?? '—'}</td>
              <td>{h.payoutBatchId ? `${h.payoutBatchId} / ${h.payoutItemId ?? '…'}` : '—'}</td>
              <td className="font-sans text-sm">{h.caption}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div>
        <h3 className="text-sm font-semibold">Ledger: what PayPal confirmed</h3>
        <table className="mt-1 w-full text-left text-sm">
          <tbody>
            {deal.ledger.map((e) => (
              <tr key={e.event + e.paypalRef} className="border-t border-slate-100">
                <td className="py-1">{e.event.replaceAll('_', ' ').toLowerCase()}</td>
                <td>{e.direction === 'NONE' ? 'held, nothing moved' : e.direction === 'IN' ? 'in' : 'out'}</td>
                <td className="tabular-nums">{money(e.amountMinor)}</td>
                <td>{e.counterparty}</td>
                <td className="font-mono text-xs">{e.paypalRef}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {calls && calls.length > 0 && (
        <details className="text-sm">
          <summary className="cursor-pointer">Every PayPal call, with its request id and debug id ({calls.length})</summary>
          <table className="mt-2 w-full text-left font-mono text-xs">
            <tbody>
              {calls.map((c, i) => (
                <tr key={i} className="border-t border-slate-100">
                  <td className="py-1">{c.side}</td><td>{c.operation}</td><td>{c.httpStatus ?? '—'}</td><td>{c.requestId ?? ''}</td><td>{c.debugId ?? ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </details>
      )}
    </div>
  )
}

/** One deal, three panes: the buyer's agent, the two jars, the seller's agent (US-21). */
export function DealView({ deal, verifier, calls, actions = {} }: { deal: Contract; verifier: Verifier | null; calls: PayPalCall[] | null; actions?: DealActions }) {
  const current = stepOf(deal)
  const [chosen, setChosen] = useState<Step | null>(null)
  const shown = chosen ?? current
  return (
    <div className="flex flex-col gap-4">
      <StepPills current={current} shown={shown} onShow={(s) => setChosen(s === current ? null : s)} />
      {(deal.needsAttention || actions.error) && (
        <p role="alert" className="rounded-lg bg-red-50 px-4 py-2 text-sm text-red-800">{actions.error ?? deal.attentionReason}</p>
      )}
      <div className="grid gap-4 lg:grid-cols-[1fr_1.6fr_1fr]">
        <Pane title={deal.buyer.displayName} subtitle="the buyer's agent">
          {deal.sentence && <p className="text-sm text-slate-700">Asked for: “{deal.sentence}”</p>}
          <Messages deal={deal} party={deal.buyer.handle} />
        </Pane>
        <Jars deal={deal} />
        <Pane title={deal.seller.displayName} subtitle="the seller's agent">
          <Messages deal={deal} party={deal.seller.handle} />
          {deal.delivery && <p className="text-sm text-slate-700">Delivered {time(deal.delivery.submittedAt)}: “{deal.delivery.text.slice(0, 90)}{deal.delivery.text.length > 90 ? '…' : ''}”</p>}
        </Pane>
      </div>
      <section className={card} aria-label={shown}>
        <h2 className="mb-3 text-lg font-semibold">{shown}</h2>
        {shown === 'Post job' && <PostJobPanel deal={deal} verifier={verifier} />}
        {shown === 'Negotiate' && <NegotiatePanel deal={deal} />}
        {shown === 'Fund' && <FundPanel deal={deal} />}
        {shown === 'Deliver' && <DeliverPanel deal={deal} actions={actions} />}
        {shown === 'Verdict' && <VerdictPanel deal={deal} />}
        {shown === 'Settle' && <SettlePanel deal={deal} actions={actions} calls={calls} />}
        {shown === 'Appeal' && <p className="text-sm text-slate-600">Escalating to PayPal Disputes is not built in this version.</p>}
      </section>
    </div>
  )
}
