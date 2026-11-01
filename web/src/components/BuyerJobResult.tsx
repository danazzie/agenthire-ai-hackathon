import { useEffect, useState } from 'react'
import { api, type Contract, type Hold } from '../api.ts'
import { money, shortHash } from '../model.ts'
import type { DealActions } from './DealView.tsx'
import circleCheckLg from '../assets/figma-circle-check-lg.svg'
import circleX from '../assets/figma-circle-x.svg'
import checkMark from '../assets/figma-check.svg'
import fileCode from '../assets/figma-file-code.svg'
import infoMark from '../assets/figma-info.svg'

const CARD = 'rounded-xl border border-[#e5e7eb] bg-white p-6 shadow-[0px_6px_18px_0px_rgba(15,23,42,0.04)]'
const BLUE = 'flex min-h-11 w-full items-center justify-center rounded-lg border border-[#2563eb] bg-[#2563eb] px-4 text-[13px] font-semibold text-white disabled:opacity-40'
const FIELD = 'min-h-[46px] w-full rounded-lg border border-[#e5e7eb] bg-white px-[13px] py-[13px] text-[14px] leading-normal text-[#0f172a]'

function workspaceDate(now = new Date()): string {
  const months = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC']
  return `${String(now.getUTCDate()).padStart(2, '0')} ${months[now.getUTCMonth()]} ${now.getUTCFullYear()} · UTC`
}

function stamp(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  const day = d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric', timeZone: 'UTC' })
  const hm = d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', timeZone: 'UTC', hour12: false })
  return `${day}, ${hm} UTC`
}

function hm(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  return d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', timeZone: 'UTC', hour12: false })
}

function feeOf(deal: Contract): Hold | undefined {
  return deal.holds.find((h) => h.side === 'BUYER_FEE')
}

function bondOf(deal: Contract): Hold | undefined {
  return deal.holds.find((h) => h.side === 'SELLER_BOND')
}

export function isJobResult(deal: Contract): boolean {
  return deal.state === 'PASS' || deal.state === 'FAIL' || deal.state === 'EXPIRED' || deal.state === 'SETTLED' || !!deal.delivery?.verdict
}

/** Alex's job result. The fee is all or nothing: a failed test voids the hold, and the bond is paid to the buyer. */
export function BuyerJobResult({ deal, actions = {} }: { deal: Contract; actions?: DealActions }) {
  const verdict = deal.delivery?.verdict ?? (deal.state === 'PASS' ? 'PASS' : deal.state === 'FAIL' ? 'FAIL' : null)
  const expired = !verdict && (deal.state === 'EXPIRED' || deal.state === 'SETTLED')
  const passed = verdict === 'PASS'
  const failed = verdict === 'FAIL'
  const checks = deal.delivery?.checks ?? []
  const passedCount = checks.filter((c) => c.passed).length
  const feeHold = feeOf(deal)
  const bondHold = bondOf(deal)
  const authorized = feeHold?.amountMinor ?? deal.feeMinor ?? 0
  const captured = feeHold && (feeHold.jar === 'CAPTURED' || feeHold.jar === 'PAID') ? (feeHold.capturedMinor ?? feeHold.amountMinor) : 0
  const bondReleased = bondHold?.jar === 'RELEASED'
  const bondPaidToBuyer = !!bondHold && (bondHold.jar === 'COMPENSATION' || bondHold.jar === 'PAID' || (bondHold.jar === 'CAPTURED' && !passed))
  const [deliveryOpen, setDeliveryOpen] = useState(false)
  const [reportOpen, setReportOpen] = useState(false)
  const [sourceOpen, setSourceOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [evidence, setEvidence] = useState('')
  const [noted, setNoted] = useState(false)
  const [sessionLine, setSessionLine] = useState<string | null>(null)

  useEffect(() => {
    Promise.all([api.status(), api.contracts()]).then(([status, deals]) => {
      if (status.session.id !== deal.sessionId) return
      const locked = deals.filter((d) => d.sessionId === status.session.id && d.state !== 'DRAFT' && d.state !== 'CANCELLED')
      const taken = locked.flatMap((d) => d.holds).filter((h) => h.side === 'BUYER_FEE' && (h.jar === 'CAPTURED' || h.jar === 'PAID')).reduce((n, h) => n + (h.capturedMinor ?? h.amountMinor), 0)
      setSessionLine(`Session capture: ${money(taken)} of ${money(status.session.capMinor)} · ${locked.length} of ${status.session.maxJobs} jobs.`)
    }).catch(() => setSessionLine(null))
  }, [deal.sessionId])

  const kind = expired ? 'expired' : failed ? 'fail' : 'pass'
  const title = expired
    ? 'No delivery. Traceable.'
    : failed
      ? 'The test failed. Traceable.'
      : deal.state === 'SETTLED'
        ? 'Verified. Settled. Traceable.'
        : 'Verified. Traceable.'
  const verdictTitle = expired
    ? 'No delivery'
    : failed
      ? `FAIL${checks.length ? ` · ${passedCount} / ${checks.length} checks` : ''}`
      : `PASS${checks.length ? ` · ${passedCount} / ${checks.length} checks` : ''}`
  const verdictWhen = deal.delivery?.verifiedAt ?? deal.settlementConfirmedAt ?? deal.updatedAt
  const tone = passed
    ? { card: 'border-[#bfdbfe] bg-[#eff6ff]', title: 'text-[#1e40af]', body: 'text-[#2563eb]' }
    : failed
      ? { card: 'border-[#f3c7c7] bg-[#fceeee]', title: 'text-[#a63f3f]', body: 'text-[#a63f3f]' }
      : { card: 'border-[#e5e7eb] bg-[#f8fafb]', title: 'text-[#0f172a]', body: 'text-[#64748b]' }
  const paypal = deal.state === 'SETTLED' ? 'SETTLED' : feeHold?.jar === 'PAID' ? 'PAID' : feeHold?.jar === 'CAPTURED' ? 'CAPTURED' : feeHold?.jar === 'RELEASED' ? 'VOIDED' : feeHold?.jar === 'HELD' ? 'HELD' : deal.state.replaceAll('_', ' ')
  const first = [...deal.offers].sort((a, b) => a.createdAt.localeCompare(b.createdAt))[0]
  const termsNote = first && deal.feeMinor != null && first.feeMinor !== deal.feeMinor
    ? `The first proposal was ${money(first.feeMinor)}. You accepted ${money(deal.feeMinor)} before work began.`
    : `Both of you accepted ${money(deal.feeMinor)} and a ${money(deal.bondMinor)} bond. The cap was ${money(deal.feeCapMinor)}.`
  const trail = [
    ...deal.transitions.map((t) => ({ at: t.occurredAt, label: t.cause || `${t.from} → ${t.to}` })),
    ...deal.ledger.map((line) => ({ at: line.occurredAt, label: `${line.event} · ${money(line.amountMinor)}` })),
    ...(deal.delivery ? [{ at: deal.delivery.submittedAt, label: 'Delivery submitted' }] : []),
    ...(deal.delivery?.verifiedAt ? [{ at: deal.delivery.verifiedAt, label: `${deal.delivery.verdict ?? 'Checked'} · ${deal.delivery.summary ?? 'the compiled test ran'}` }] : []),
  ].filter((e) => e.at).sort((a, b) => a.at.localeCompare(b.at))
  const examples = [
    kind !== 'pass' ? { chip: 'PASS', chipClass: 'bg-[#dbeafe] text-[#1e40af]', body: 'Every check passes. The full fee is captured and paid to the seller. The bond is released.', rows: [['Buyer capture', money(deal.feeMinor), true], ['Uncaptured amount', money(0), false], ['Seller bond · released', money(deal.bondMinor), false]] as const } : null,
    kind !== 'fail' ? { chip: 'FAIL', chipClass: 'bg-[#fceeee] text-[#a63f3f]', body: 'Any check fails. The fee hold is voided. That is not a refund. The bond is paid to you.', rows: [['Buyer capture', money(0), true], ['Fee hold · voided', money(deal.feeMinor), false], ['Bond paid to you', money(deal.bondMinor), false]] as const } : null,
    kind !== 'expired' ? { chip: 'NO DELIVERY', chipClass: 'bg-[#f3f4f6] text-[#64748b]', body: 'Nothing is submitted by the deadline. The fee hold is voided, and the bond is paid to you.', rows: [['Buyer capture', money(0), true], ['Fee hold · voided', money(deal.feeMinor), false], ['Bond paid to you', money(deal.bondMinor), false]] as const } : null,
  ].filter((e): e is NonNullable<typeof e> => !!e)

  return (
    <div className="mx-auto flex min-w-0 max-w-7xl flex-col gap-8 px-4 py-6 sm:px-6 sm:py-8">
      <header className="flex flex-col gap-2">
        <div className="flex items-center justify-between gap-3 text-[11px] leading-normal">
          <p className="font-semibold text-[#2563eb]">CLIENT / JOB RESULT</p>
          <p className="shrink-0 text-[#64748b]">{workspaceDate()}</p>
        </div>
        <h1 className="text-[30px] leading-normal text-[#0f172a]">{title}</h1>
        <p className="text-[14px] leading-normal text-[#64748b]">
          {deal.jobText} · {deal.id.slice(0, 8)} · {deal.seller.displayName}
        </p>
      </header>

      {(deal.needsAttention || actions.error) && (
        <p role="alert" className="rounded-lg bg-[#fceeee] px-4 py-3 text-sm text-[#a63f3f]">{actions.error ?? deal.attentionReason}</p>
      )}

      <div className="grid min-w-0 items-start gap-6 lg:grid-cols-[minmax(0,1fr)_352px]">
        <div className="flex min-w-0 flex-col gap-6">
          <section className={`flex flex-col gap-5 rounded-xl border p-6 shadow-[0px_8px_24px_0px_rgba(15,23,42,0.07)] ${tone.card}`}>
            <div className="flex items-center gap-3">
              <img src={passed ? circleCheckLg : circleX} alt="" />
              <div className="min-w-0">
                <p className={`text-[23px] font-bold leading-normal ${tone.title}`}>{verdictTitle}</p>
                <p className={`text-[12px] leading-normal ${tone.body}`}>Final verdict · {stamp(verdictWhen)}</p>
              </div>
            </div>
            <div className="flex gap-1" aria-hidden="true">
              {(checks.length ? checks : [{ passed }]).map((c, i) => (
                <span key={i} className={`h-5 min-w-2 flex-1 rounded-[3px] ${c.passed ? 'bg-[#2563eb]' : 'bg-[#e5e7eb]'}`} />
              ))}
            </div>
            <p className={`text-[13px] leading-normal ${tone.body}`}>
              {deal.delivery?.summary ?? (expired ? 'The deadline passed with no delivery.' : 'The compiled test decided this result.')}
            </p>
            <div className="flex flex-wrap gap-x-5 gap-y-2 text-[12px] text-[#2563eb]">
              {deal.delivery?.text && (
                <button type="button" onClick={() => setDeliveryOpen((v) => !v)} className="text-left">{deliveryOpen ? 'Hide the delivery' : 'View the delivery ↗'}</button>
              )}
              {checks.length > 0 && (
                <button type="button" onClick={() => setReportOpen((v) => !v)} className="text-left">{reportOpen ? 'Hide the report' : 'Verification report ↗'}</button>
              )}
            </div>
            {deliveryOpen && deal.delivery?.text && <p className="whitespace-pre-wrap text-[13px] leading-normal text-[#0f172a]">{deal.delivery.text}</p>}
            {reportOpen && checks.map((c) => (
              <div key={c.name} className="flex items-start gap-2.5">
                <img src={c.passed ? checkMark : circleX} alt="" />
                <p className="min-w-0 flex-1 text-[13px] leading-normal text-[#0f172a]">{c.name}</p>
                <p className={`shrink-0 text-[12px] ${c.passed ? 'text-[#087f73]' : 'text-[#a63f3f]'}`}>{c.detail}{c.expected ? ` · needs ${c.expected}` : ''}</p>
              </div>
            ))}
          </section>

          <section className={`flex flex-col gap-[18px] ${CARD}`}>
            <h2 className="text-[17px] leading-normal text-[#0f172a]">Agreed test & terms</h2>
            <div className="flex gap-3"><p className="flex-1 text-[13px] text-[#64748b]">Agreed fee / seller bond</p><p className="text-[14px] text-[#0f172a]">{money(deal.feeMinor)} / {money(deal.bondMinor)}</p></div>
            <div className="flex gap-3"><p className="flex-1 text-[13px] text-[#64748b]">Approved deadline</p><p className="text-[14px] text-[#0f172a]">{deal.deadlineAt ? stamp(deal.deadlineAt) : '—'}</p></div>
            <p className="text-[11px] leading-normal text-[#64748b]">{termsNote}</p>
            <div className="h-px bg-[#e5e7eb]" />
            <div className="flex items-center justify-between gap-3">
              <p className="text-[13px] text-[#0f172a]">Compiled test</p>
              <span className="rounded-md bg-[#dbeafe] px-[9px] py-1 text-[11px] text-[#1e40af]">{deal.verifier ? `${shortHash(deal.verifier.sha256)} · locked` : 'Locked'}</span>
            </div>
            <div className="flex items-start gap-2.5">
              <img src={checkMark} alt="" />
              <p className="text-[13px] leading-normal text-[#0f172a]">PASS: every check passes → the full {money(deal.feeMinor)} fee is captured and paid to the seller. The bond is released.</p>
            </div>
            <div className="flex items-start gap-2.5">
              <img src={checkMark} alt="" />
              <p className="text-[13px] leading-normal text-[#0f172a]">FAIL: any check fails → the fee hold is voided and the {money(deal.bondMinor)} bond is paid to you. The fee is not split.</p>
            </div>
            {deal.verifier?.source && (
              <button type="button" onClick={() => setSourceOpen((v) => !v)} className="flex items-center gap-2 text-left text-[12px] text-[#2563eb]">
                <img src={fileCode} alt="" />
                {sourceOpen ? 'Hide test source' : 'View test source ↗'}
              </button>
            )}
            {sourceOpen && deal.verifier?.source && (
              <pre className="max-h-64 overflow-auto whitespace-pre-wrap rounded-lg bg-[#f8fafb] p-3 text-[11px] leading-normal text-[#0f172a]">{deal.verifier.source}</pre>
            )}
          </section>
        </div>

        <div className="flex min-w-0 flex-col gap-5">
          <section className={`flex flex-col gap-[18px] ${CARD}`}>
            <p className="text-[11px] font-semibold leading-normal text-[#64748b]">PAYPAL · {paypal}</p>
            <h2 className="text-[17px] leading-normal text-[#0f172a]">Final accounting</h2>
            <div className="flex flex-col gap-3.5">
              <div>
                <div className="flex gap-3"><p className="flex-1 text-[13px] text-[#64748b]">Buyer authorization</p><p className="text-[14px] text-[#0f172a]">{money(authorized)}</p></div>
                <p className="text-[11px] text-[#64748b]">{feeHold?.caption ?? 'The hold opened when both of you accepted.'}</p>
              </div>
              <div className="flex gap-3"><p className="flex-1 text-[13px] text-[#0f172a]">Captured to seller</p><p className="text-[14px] font-bold text-[#0f172a]">{money(captured)}</p></div>
              <div className="flex gap-3"><p className="flex-1 text-[13px] text-[#64748b]">Uncaptured amount</p><p className="text-[14px] text-[#0f172a]">{money(authorized - captured)}</p></div>
              <div>
                <div className="flex gap-3">
                  <p className="flex-1 text-[13px] text-[#64748b]">{bondPaidToBuyer ? 'Bond paid to you' : 'Seller bond released'}</p>
                  <p className="text-[14px] text-[#0f172a]">{money(bondHold?.amountMinor ?? deal.bondMinor)}</p>
                </div>
                <p className="text-[11px] text-[#64748b]">{bondPaidToBuyer ? 'Paid to you because the test failed or nothing was delivered.' : bondReleased ? 'Returned to the seller. Not part of your payment.' : bondHold?.caption ?? 'Not settled yet.'}</p>
              </div>
            </div>
            <div className="h-px bg-[#e5e7eb]" />
            <div className="flex gap-3"><p className="flex-1 text-[13px] text-[#0f172a]">Your actual charge</p><p className="text-[14px] font-bold text-[#0f172a]">{money(captured)}</p></div>
            {sessionLine && <p className="text-[11px] leading-normal text-[#64748b]">{sessionLine}</p>}
            {feeHold?.jar === 'RELEASED' && <p className="text-[11px] leading-normal text-[#64748b]">The {money(authorized)} hold was voided. That is not a refund: the money was never taken.</p>}
            {deal.awaitingConfirmation && (
              <button type="button" disabled={!actions.confirm || actions.busy} onClick={() => actions.confirm?.()} className={BLUE}>Confirm the first capture</button>
            )}
          </section>

          <section className={`flex flex-col gap-[18px] ${CARD}`}>
            <h2 className="text-[17px] leading-normal text-[#0f172a]">Transaction trail</h2>
            {trail.length === 0 ? (
              <p className="text-[13px] text-[#64748b]">No events recorded yet.</p>
            ) : (
              trail.map((event) => (
                <div key={`${event.at}-${event.label}`} className="flex gap-3">
                  <p className="w-12 shrink-0 text-[11px] text-[#64748b]">{hm(event.at)}</p>
                  <p className="min-w-0 flex-1 text-[12px] leading-normal text-[#0f172a]">{event.label}</p>
                </div>
              ))
            )}
          </section>
        </div>
      </div>

      <section className={`flex flex-col gap-[18px] ${CARD}`}>
        <p className="text-[11px] font-semibold leading-normal text-[#64748b]">{noted ? 'NOTE · SAVED ON THIS PAGE' : 'NOTE · NOT SENT'}</p>
        <h2 className="text-[17px] leading-normal text-[#0f172a]">Think this result is wrong?</h2>
        <p className="text-[13px] leading-normal text-[#64748b]">This control plane does not take an appeal. A note stays on this page. It does not reverse a capture, void a hold, or start a second delivery.</p>
        <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_320px]">
          <label className="flex flex-col gap-[7px]">
            <span className="text-[12px] font-semibold text-[#0f172a]">Why should this result be remembered?</span>
            <textarea className={`${FIELD} min-h-[104px]`} rows={3} value={reason} onChange={(e) => { setNoted(false); setReason(e.target.value) }} />
          </label>
          <div className="flex flex-col gap-3">
            <label className="flex flex-col gap-[7px]">
              <span className="text-[12px] font-semibold text-[#0f172a]">Evidence link or timestamp</span>
              <input className={FIELD} value={evidence} onChange={(e) => { setNoted(false); setEvidence(e.target.value) }} />
            </label>
            <button type="button" disabled={!reason.trim()} onClick={() => setNoted(true)} className={BLUE}>Save a note</button>
          </div>
        </div>
        <p className="text-[11px] leading-normal text-[#64748b]">{noted ? 'Saved on this page. Not sent. The result above still stands.' : 'Nothing is sent. No outcome changes.'}</p>
      </section>

      <section className="flex flex-col gap-4">
        <h2 className="text-[19px] leading-normal text-[#0f172a]">Other ways this job could settle</h2>
        <p className="text-[13px] leading-normal text-[#64748b]">These are the other results for the same {money(deal.feeMinor)} fee and {money(deal.bondMinor)} bond. They are not what happened above.</p>
        <div className="grid gap-6 lg:grid-cols-2">
          {examples.map((example) => (
            <article key={example.chip} className={`flex flex-col gap-[18px] ${CARD}`}>
              <span className={`self-start rounded-md px-[9px] py-1 text-[11px] ${example.chipClass}`}>{example.chip}</span>
              <p className="text-[12px] leading-normal text-[#64748b]">{example.body}</p>
              <div className="flex flex-col gap-3.5">
                {example.rows.map(([label, value, strong]) => (
                  <div key={label} className="flex gap-3">
                    <p className={`flex-1 text-[13px] ${strong ? 'text-[#0f172a]' : 'text-[#64748b]'}`}>{label}</p>
                    <p className={`text-[14px] text-[#0f172a] ${strong ? 'font-bold' : ''}`}>{value}</p>
                  </div>
                ))}
              </div>
            </article>
          ))}
        </div>
        <aside className="flex items-start gap-3 rounded-lg bg-[#dbeafe] p-4 text-[#1e40af]">
          <img src={infoMark} alt="" />
          <div className="min-w-0">
            <p className="text-[13px] leading-normal">A failed test is final</p>
            <p className="mt-1 text-[12px] leading-normal">There is no partial capture and no second delivery. A failed test and a missed deadline move the same money: the fee hold is voided, and the bond is paid to you. Void is not a refund.</p>
          </div>
        </aside>
      </section>

      <footer className="flex flex-wrap items-center justify-between gap-2 text-[11px] text-[#64748b]">
        <p>HireMind · Clear terms. Verified work.</p>
        <p>A hold is not a charge.</p>
      </footer>
    </div>
  )
}
