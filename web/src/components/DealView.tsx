import { useState, type ReactNode } from 'react'
import { api, ApiError, type Contract, type DryRun, type Hold, type Offer, type PayPalCall, type Verifier } from '../api.ts'
import { money, shortHash, stepOf, time, type Step } from '../model.ts'
import { JarCard } from './JarCard.tsx'
import { StepPills } from './StepPills.tsx'

export interface DealActions {
  deliver?: (mode: 'AGENT' | 'SCRIPTED_JUNK' | 'SCRIPTED_PASS') => void
  confirm?: () => void
  cancel?: () => void
  checkHold?: (holdId: string) => void
  loadReads?: () => void
  busy?: boolean
  error?: string | null
}

export function canAskPayPal(hold: Hold) {
  return hold.state === 'ORDER_CREATED' || hold.state === 'APPROVED'
}

/** TERMS_LOCKED only. Voiding a placed hold is not a refund: the money was never taken. */
export function CancelDeal({ busy, onCancel }: { busy?: boolean; onCancel: () => void }) {
  const [armed, setArmed] = useState(false)
  if (!armed) {
    return (
      <button type="button" disabled={busy} onClick={() => setArmed(true)} className={secondary}>
        Cancel this deal
      </button>
    )
  }
  return (
    <div className="flex min-w-0 flex-col gap-2">
      <p className="text-sm text-[var(--bm-on-var)]">Cancel while the holds are still open? A placed hold is voided. That is not a refund: the money was never taken.</p>
      <div className="flex flex-col gap-2 sm:flex-row">
        <button type="button" disabled={busy} onClick={onCancel} className={secondary}>Yes, cancel</button>
        <button type="button" disabled={busy} onClick={() => setArmed(false)} className="min-h-11 px-4 text-[13px] font-semibold text-[var(--bm-on-var)]">Keep it</button>
      </div>
    </div>
  )
}

/** POST /verifiers/{id}/dry-run. The text is not a delivery. */
export function DryRunBox({ verifierId }: { verifierId: string }) {
  const [text, setText] = useState('')
  const [result, setResult] = useState<DryRun | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function run() {
    setBusy(true)
    setError(null)
    try {
      setResult(await api.dryRun(verifierId, text))
    } catch (e) {
      setResult(null)
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex min-w-0 flex-col gap-3 rounded-xl bg-[var(--bm-low)] p-4">
      <div>
        <h3 className="text-[15px] font-semibold">Try the test</h3>
        <p className="mt-1 text-[13px] text-[var(--bm-on-var)]">Run the compiled verifier on any text. Nothing is delivered and nothing is at stake.</p>
      </div>
      <textarea value={text} onChange={(e) => setText(e.target.value)} rows={4} aria-label="Text to test" className="min-h-24 w-full resize-y rounded-xl bg-white px-3 py-2 text-sm" />
      <button type="button" disabled={busy || text.trim().length === 0} onClick={run} className={secondary}>Run the test</button>
      {error && <p role="alert" className="text-sm text-red-800">{error}</p>}
      {result && (
        <div className="text-sm">
          <p className={`text-2xl font-extrabold ${result.outcome === 'PASS' ? 'text-paid' : 'text-red-800'}`}>{result.outcome}</p>
          {result.summary && <p className="mt-1">{result.summary}</p>}
          {result.error && <p className="mt-1 text-red-800">{result.error}</p>}
          {result.injectionFlags.length > 0 && <p className="mt-1 text-amber-800">Flagged: {result.injectionFlags.join(', ')}</p>}
          <ul className="mt-2 flex flex-col gap-1">
            {result.checks.map((c) => (
              <li key={c.name} className="break-words">{c.passed ? '✓' : '✗'} <span className="font-mono text-xs">{c.name}</span> {c.detail}</li>
            ))}
          </ul>
          <p className="mt-2 text-xs text-[var(--bm-on-var)]">{result.runtime} · {result.durationMs} ms · source {shortHash(result.sourceSha256)}</p>
        </div>
      )}
    </div>
  )
}

const card = 'min-w-0 rounded-2xl bg-white p-6 shadow-sm'
const primary = 'inline-flex min-h-11 w-full items-center justify-center rounded-xl bg-[#2563eb] px-4 py-2 text-[13px] font-semibold text-white disabled:opacity-40 sm:w-auto'
const secondary = 'inline-flex min-h-11 w-full items-center justify-center rounded-xl border border-[var(--bm-highest)] bg-white px-4 py-2 text-[13px] font-semibold disabled:opacity-40 sm:w-auto'
const passBtn = 'inline-flex min-h-11 w-full items-center justify-center rounded-xl bg-paid px-4 py-2 text-[13px] font-semibold text-white disabled:opacity-40 sm:w-auto'
const confirmBtn = 'inline-flex min-h-11 w-full items-center justify-center rounded-xl bg-held px-4 py-2 text-[13px] font-semibold text-white disabled:opacity-40 sm:w-auto'

function Pane({ title, subtitle, className = '', children }: { title: string; subtitle: string; className?: string; children: ReactNode }) {
  return (
    <section className={`${card} flex min-w-0 flex-col gap-3 ${className}`}>
      <header>
        <h2 className="break-words text-2xl font-semibold">{title}</h2>
        <p className="text-sm text-[var(--bm-on-var)]">{subtitle}</p>
      </header>
      {children}
    </section>
  )
}

function Bubble({ offer, mine }: { offer: Offer; mine: boolean }) {
  return (
    <li className={`min-w-0 rounded-xl px-3 py-2 text-sm ${mine ? 'bg-[var(--bm-low)]' : 'bg-white shadow-sm'}`}>
      <div className="flex flex-wrap items-baseline justify-between gap-x-2 gap-y-0.5">
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

export function Jars({ deal, className = '' }: { deal: Contract; className?: string }) {
  const fee = deal.holds.find((h) => h.side === 'BUYER_FEE') ?? null
  const bond = deal.holds.find((h) => h.side === 'SELLER_BOND') ?? null
  const hash = fee?.customId ?? deal.verifier?.sha256
  return (
    <section className={`flex min-w-0 flex-col gap-4 ${className}`} aria-label="The two jars">
      <div className="grid min-w-0 grid-cols-1 gap-4 sm:grid-cols-2">
        <JarCard hold={fee} title="Buyer fee" party={deal.buyer.displayName} />
        <JarCard hold={bond} title="Seller bond" party={deal.seller.displayName} />
      </div>
      <div className={`${card} flex flex-col gap-3`}>
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-x-4 gap-y-1 text-sm text-[var(--bm-on-var)]">
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
      {deal.outcome && <p className="break-words rounded-xl bg-[#2563eb] px-4 py-3 text-center text-xl font-semibold text-white">{deal.outcome}</p>}
      <InFlight deal={deal} />
      <p className="text-xs text-[var(--bm-on-var)]">
        Jars show only what PayPal has confirmed. “Captured” means PayPal took the money and the payout has not landed yet; it is not one of
        the five paper-prototype states.
      </p>
      </div>
    </section>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt className="text-xs font-semibold uppercase tracking-wide text-slate-500">{label}</dt>
      <dd className="break-words text-sm">{children}</dd>
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
    <dl className="grid min-w-0 gap-3 sm:grid-cols-2">
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
        <div className="rounded-lg bg-emerald-50 px-3 py-2 text-sm text-emerald-900">
          <p>Terms locked: both agents accepted the same fee, bond, deadline and test. Terms <span className="font-mono">{shortHash(deal.termsHash)}</span>.</p>
          {deal.termsDocument && (
            <details className="mt-2">
              <summary className="cursor-pointer">The terms document anyone can re-hash</summary>
              <pre className="mt-2 overflow-auto whitespace-pre-wrap break-all font-mono text-xs text-emerald-950">{deal.termsDocument}</pre>
            </details>
          )}
        </div>
      ) : (
        <p className="text-sm text-slate-600">Terms lock only when both agents ACCEPT the same fee, bond, deadline and test.</p>
      )}
    </div>
  )
}

function FundPanel({ deal, actions }: { deal: Contract; actions: DealActions }) {
  return (
    <div className="flex flex-col gap-3">
      <p className="text-sm text-slate-700">Nothing is charged. Each hold is a real PayPal sandbox approval, made by the person who pays it.</p>
      <ul className="flex flex-col gap-3 sm:hidden">
        {deal.holds.map((h) => (
          <li key={h.id} className="flex min-w-0 flex-col gap-2 rounded-xl bg-[var(--bm-low)] px-3 py-3">
            <div className="flex items-baseline justify-between gap-3">
              <span className="font-medium">{h.side === 'BUYER_FEE' ? deal.buyer.displayName : deal.seller.displayName}</span>
              <span className="text-2xl font-semibold tabular-nums">{money(h.amountMinor)}</span>
            </div>
            <p className="text-sm text-ink-soft">{h.jar === 'EMPTY' ? (h.state === 'ABANDONED' ? 'never held' : 'waiting for approval') : 'Held'}</p>
            <p className="font-mono text-xs text-muted">order {h.paypalOrderId ?? '…'} · test {shortHash(h.customId)}</p>
            {h.approveUrl && h.state === 'ORDER_CREATED' && (
              <a className={primary} href={h.approveUrl} target="_blank" rel="noreferrer">
                Approve as {h.side === 'BUYER_FEE' ? 'buyer' : 'seller'}
              </a>
            )}
            {canAskPayPal(h) && (
              <button type="button" disabled={actions.busy} onClick={() => actions.checkHold?.(h.id)} className={secondary}>Ask PayPal again</button>
            )}
            {h.needsAttention && <p className="text-sm text-red-700">{h.attentionReason}</p>}
          </li>
        ))}
      </ul>
      <div className="hidden min-w-0 overflow-x-auto sm:block">
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
                  <a className={primary} href={h.approveUrl} target="_blank" rel="noreferrer">
                    Approve as {h.side === 'BUYER_FEE' ? 'buyer' : 'seller'}
                  </a>
                )}
                {canAskPayPal(h) && (
                  <button type="button" disabled={actions.busy} onClick={() => actions.checkHold?.(h.id)} className={secondary}>Ask PayPal again</button>
                )}
                {h.needsAttention && <div className="text-xs text-red-700">{h.attentionReason}</div>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      </div>
      {deal.state === 'TERMS_LOCKED' && actions.cancel && <CancelDeal busy={actions.busy} onCancel={actions.cancel} />}
    </div>
  )
}

function DeliverPanel({ deal, actions }: { deal: Contract; actions: DealActions }) {
  const d = deal.delivery
  return (
    <div className="flex flex-col gap-3">
      {deal.state === 'BOTH_HELD' && !d && (
        <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap">
          <button type="button" disabled={actions.busy} onClick={() => actions.deliver?.('AGENT')} className={primary}>
            Seller's agent writes it (and tests it first)
          </button>
          <button type="button" disabled={actions.busy} onClick={() => actions.deliver?.('SCRIPTED_JUNK')} className={secondary}>
            Scripted junk delivery
          </button>
          <button type="button" disabled={actions.busy} onClick={() => actions.deliver?.('SCRIPTED_PASS')} className={passBtn}>
            Scripted 50-word delivery
          </button>
        </div>
      )}
      {deal.verifier && <DryRunBox verifierId={deal.verifier.id} />}
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
      <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-baseline sm:gap-4">
        <span className={`text-5xl font-extrabold tracking-tight sm:text-6xl ${pass ? 'text-paid' : 'text-red-800'}`}>{d.verdict}</span>
        <span className="min-w-0 break-words text-lg sm:text-xl">{d.summary}</span>
      </div>
      <ul className="flex flex-col gap-2 sm:hidden">
        {d.checks.map((c) => (
          <li key={c.name} className="rounded-sm border border-rule px-3 py-2 text-sm">
            <div className="flex items-baseline justify-between gap-3">
              <span className="font-mono text-xs">{c.name}</span>
              <span className={c.passed ? 'text-paid' : 'text-red-700'}>{c.passed ? '✓ pass' : '✗ fail'}</span>
            </div>
            <p className="mt-1">{c.detail}{c.evidence ? ` (${c.evidence})` : ''}</p>
            <p className="text-ink-soft">{c.expected ?? '—'}</p>
          </li>
        ))}
      </ul>
      <div className="hidden min-w-0 overflow-x-auto sm:block">
      <table className="w-full text-left text-sm">
        <thead className="text-xs uppercase text-slate-500"><tr><th className="py-1">Check</th><th>Found</th><th>The test needs</th><th /></tr></thead>
        <tbody>
          {d.checks.map((c) => (
            <tr key={c.name} className="border-t border-slate-100">
              <td className="py-1.5 font-mono text-xs">{c.name}</td>
              <td className="break-words">{c.detail}{c.evidence ? ` (${c.evidence})` : ''}</td>
              <td className="break-words">{c.expected ?? '—'}</td>
              <td className={c.passed ? 'text-paid' : 'text-red-700'}>{c.passed ? '✓ pass' : '✗ fail'}</td>
            </tr>
          ))}
        </tbody>
      </table>
      </div>
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
        <div className="flex flex-col gap-3 rounded-sm border border-[#f3d9b1] bg-[#fdf6ee] px-4 py-3 sm:flex-row sm:flex-wrap sm:items-center">
          <p className="text-sm text-held">
            The verdict is {deal.state}. The first capture of a session waits for a person, so a verifier bug cannot move money silently (US-16).
          </p>
          <button type="button" disabled={actions.busy} onClick={() => actions.confirm?.()} className={confirmBtn}>
            Confirm the first capture
          </button>
        </div>
      )}
      <ul className="flex flex-col gap-3 sm:hidden">
        {deal.holds.map((h) => (
          <li key={h.id} className="rounded-sm border border-rule px-3 py-3 text-sm">
            <div className="font-medium">{h.side === 'BUYER_FEE' ? 'Buyer fee' : 'Seller bond'}</div>
            <p className="mt-1">{h.caption}</p>
            <dl className="mt-2 grid min-w-0 grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1 font-mono text-xs text-ink-soft">
              <dt>Authorization</dt><dd className="break-all">{h.authorizationId ?? '—'}</dd>
              <dt>Capture</dt><dd className="break-all">{h.captureId ?? '—'}</dd>
              <dt>Payout</dt><dd className="break-all">{h.payoutBatchId ? `${h.payoutBatchId} / ${h.payoutItemId ?? '…'}` : '—'}</dd>
            </dl>
          </li>
        ))}
      </ul>
      <div className="hidden sm:block overflow-x-auto">
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
      </div>
      <div>
        <h3 className="text-sm font-semibold">Ledger: what PayPal confirmed</h3>
        <ul className="mt-2 flex flex-col gap-2 sm:hidden">
          {deal.ledger.map((e) => (
            <li key={e.event + e.paypalRef} className="rounded-sm border border-rule px-3 py-2 text-sm">
              <div className="flex items-baseline justify-between gap-3">
                <span>{e.event.replaceAll('_', ' ').toLowerCase()}</span>
                <span className="tabular-nums">{money(e.amountMinor)}</span>
              </div>
              <p className="text-ink-soft">{e.direction === 'NONE' ? 'held, nothing moved' : e.direction === 'IN' ? 'in' : 'out'} · {e.counterparty}</p>
              <p className="break-all font-mono text-xs text-muted">{e.paypalRef}</p>
            </li>
          ))}
        </ul>
        <div className="mt-1 hidden min-w-0 overflow-x-auto sm:block">
        <table className="w-full text-left text-sm">
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
      </div>
      {(deal.holds.some((h) => h.paypalOrderId) || (calls && calls.length > 0)) && (
        <details className="text-sm" open={!calls || calls.length === 0}>
          <summary className="cursor-pointer">Every PayPal call, with its request id and debug id ({calls?.length ?? 0})</summary>
          {actions.loadReads && (
            <button type="button" disabled={actions.busy} onClick={() => actions.loadReads?.()} className={`${secondary} mt-2`}>Include PayPal reads</button>
          )}
          <div className="mt-2 overflow-x-auto">
          <table className="w-full text-left font-mono text-xs">
            <tbody>
              {(calls ?? []).map((c, i) => (
                <tr key={i} className="border-t border-slate-100">
                  <td className="py-1">{c.side}</td><td>{c.operation}</td><td>{c.method}</td><td className="break-all">{c.path}</td><td>{c.httpStatus ?? '—'}</td><td>{c.requestId ?? ''}</td><td>{c.debugId ?? ''}</td><td>{c.errorName ?? ''}{c.errorIssue ? ` ${c.errorIssue}` : ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
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
    <div className="flex min-w-0 flex-col gap-6">
      <StepPills current={current} shown={shown} onShow={(s) => setChosen(s === current ? null : s)} />
      {(deal.needsAttention || actions.error) && (
        <p role="alert" className="rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-800">{actions.error ?? deal.attentionReason}</p>
      )}
      <div className="grid min-w-0 gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.6fr)_minmax(0,1fr)]">
        <Pane className="order-3 lg:order-none" title={deal.buyer.displayName} subtitle="the buyer's agent">
          {deal.sentence && <p className="break-words text-sm text-[var(--bm-on-var)]">Asked for: “{deal.sentence}”</p>}
          <Messages deal={deal} party={deal.buyer.handle} />
        </Pane>
        <Jars deal={deal} className="order-1 lg:order-none" />
        <Pane className="order-4 lg:order-none" title={deal.seller.displayName} subtitle="the seller's agent">
          <Messages deal={deal} party={deal.seller.handle} />
          {deal.delivery && <p className="break-words text-sm text-[var(--bm-on-var)]">Delivered {time(deal.delivery.submittedAt)}: “{deal.delivery.text.slice(0, 90)}{deal.delivery.text.length > 90 ? '…' : ''}”</p>}
        </Pane>
        <section className={`${card} order-2 lg:order-none lg:col-span-3`} aria-label={shown}>
          <h2 className="mb-3 break-words text-2xl font-semibold">{shown}</h2>
          {shown === 'Post job' && <PostJobPanel deal={deal} verifier={verifier} />}
          {shown === 'Negotiate' && <NegotiatePanel deal={deal} />}
          {shown === 'Fund' && <FundPanel deal={deal} actions={actions} />}
          {shown === 'Deliver' && <DeliverPanel deal={deal} actions={actions} />}
          {shown === 'Verdict' && <VerdictPanel deal={deal} />}
          {shown === 'Settle' && <SettlePanel deal={deal} actions={actions} calls={calls} />}
          {shown === 'Appeal' && <p className="text-sm text-slate-600">Escalating to PayPal Disputes is not built in this version.</p>}
        </section>
      </div>
    </div>
  )
}
