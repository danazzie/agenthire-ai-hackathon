import { useState } from 'react'
import type { Contract, Hold } from '../api.ts'
import { JAR_LABEL, money, shortHash, time } from '../model.ts'
import { holdFor, nextAction } from '../role.ts'
import { CancelDeal, DryRunBox, canAskPayPal, type DealActions } from './DealView.tsx'
import circleCheck from '../assets/figma-circle-check.svg'
import circleX from '../assets/figma-circle-x.svg'
import clockMark from '../assets/figma-clock.svg'
import checkMark from '../assets/figma-check.svg'
import fileCode from '../assets/figma-file-code.svg'
import fileMark from '../assets/figma-file.svg'
import infoMark from '../assets/figma-info.svg'

const CARD = 'rounded-xl border border-[#e5e7eb] bg-white p-6 shadow-[0px_2px_8px_0px_rgba(15,23,42,0.04)]'
const BLUE = 'inline-flex min-h-11 items-center justify-center rounded-lg border border-[#2563eb] bg-[#2563eb] px-4 text-[13px] font-semibold text-white disabled:opacity-40'
const GHOST = 'inline-flex min-h-11 items-center justify-center rounded-lg border border-[#e5e7eb] bg-white px-4 text-[13px] font-semibold text-[#0f172a] disabled:opacity-40'

function workspaceDate(now = new Date()): string {
  const months = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC']
  return `${String(now.getUTCDate()).padStart(2, '0')} ${months[now.getUTCMonth()]} ${now.getUTCFullYear()} · UTC`
}

function utcStamp(iso: string): string {
  const d = new Date(iso)
  const hm = d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', timeZone: 'UTC', hour12: false })
  const day = d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', timeZone: 'UTC' })
  return `${day}, ${hm} UTC`
}

function remaining(iso: string | null): string | null {
  if (!iso) return null
  const ms = new Date(iso).getTime() - Date.now()
  if (ms <= 0) return `${utcStamp(iso)} · the deadline has passed`
  const h = Math.floor(ms / 3_600_000)
  const m = Math.floor((ms % 3_600_000) / 60_000)
  return `${utcStamp(iso)} · ${h}h ${m}m until the deadline`
}

function feeHold(deal: Contract): Hold | undefined {
  return deal.holds.find((h) => h.side === 'BUYER_FEE')
}

function earned(deal: Contract): number {
  const fee = feeHold(deal)
  if (!fee || (fee.jar !== 'CAPTURED' && fee.jar !== 'PAID')) return 0
  return fee.capturedMinor ?? fee.amountMinor
}

function compensated(deal: Contract): number {
  return deal.holds.filter((h) => h.jar === 'COMPENSATION').reduce((n, h) => n + h.amountMinor, 0)
}

/** Maya's delivery desk. The test is pass or fail: this screen does not split the fee. */
export function SellerDelivery({ deal, actions = {} }: { deal: Contract; actions?: DealActions }) {
  const [sourceOpen, setSourceOpen] = useState(false)
  const [textOpen, setTextOpen] = useState(false)
  const next = nextAction('seller', deal)
  const delivery = deal.delivery
  const bond = holdFor('seller', deal)
  const fee = feeHold(deal)
  const verdict = delivery?.verdict ?? null
  const failing = delivery?.checks.find((c) => !c.passed)
  const title = verdict === 'FAIL'
    ? (delivery?.summary || failing?.detail || 'The test failed')
    : verdict === 'PASS'
      ? 'The test passed'
      : next.title
  const chip = verdict === 'FAIL'
    ? 'Final result · FAIL'
    : verdict === 'PASS'
      ? 'Final result · PASS'
      : delivery
        ? 'Verifier running'
        : next.kind === 'deliver'
          ? 'Ready to deliver'
          : next.kind === 'approve'
            ? 'Bond approval needed'
            : deal.state.replaceAll('_', ' ')
  const expiredNoDelivery = !verdict && deal.state === 'EXPIRED'

  return (
    <div className="mx-auto flex min-w-0 max-w-7xl flex-col gap-6 px-4 py-6 sm:px-6 sm:py-8">
      <header className="flex flex-col gap-2.5">
        <div className="flex items-center justify-between gap-3 text-[11px] leading-normal">
          <p className="text-[#2563eb]">SELLER AGENT / DELIVERY & VERIFICATION</p>
          <p className="shrink-0 text-[#64748b]">{workspaceDate()}</p>
        </div>
        <h1 className="text-[30px] leading-normal text-[#0f172a]">{title}</h1>
        <p className="text-[14px] leading-normal text-[#64748b]">
          {deal.jobText} · {deal.id.slice(0, 8)} · Agreed fee {money(deal.feeMinor)} · Bond {money(deal.bondMinor)}
        </p>
      </header>

      <div className="flex flex-wrap items-center gap-3">
        <span className="rounded-md border border-[#bfdbfe] bg-[#dbeafe] px-[9px] py-1 text-[11px] leading-normal text-[#1e40af]">{chip}</span>
        {remaining(deal.deadlineAt) && <p className="text-[12px] leading-normal text-[#64748b]">{remaining(deal.deadlineAt)}</p>}
      </div>

      {(deal.needsAttention || actions.error) && (
        <p role="alert" className="rounded-lg bg-[#fceeee] px-4 py-3 text-sm text-[#a63f3f]">{actions.error ?? deal.attentionReason}</p>
      )}

      <div className="grid min-w-0 items-start gap-6 lg:grid-cols-[minmax(0,1fr)_352px]">
        <div className="flex min-w-0 flex-col gap-6">
          {delivery && (
            <section className={`flex flex-col gap-[18px] ${CARD}`}>
              <h2 className="text-[17px] leading-normal text-[#0f172a]">Attempt {delivery.attempts || 1} · Verification report</h2>
              <div className="flex items-center justify-between gap-3">
                <span className={`rounded-md px-[9px] py-1 text-[11px] leading-normal ${verdict === 'PASS' ? 'bg-[#d1fae5] text-[#047857]' : verdict === 'FAIL' ? 'bg-[#fceeee] text-[#a63f3f]' : 'bg-[#f3f4f6] text-[#64748b]'}`}>
                  {verdict ?? 'RUNNING'}{failing ? ` · ${failing.name}` : ''}
                </span>
                <span className="text-[11px] text-[#64748b]">{delivery.verifiedAt ? utcStamp(delivery.verifiedAt) : time(delivery.submittedAt)}</span>
              </div>
              {verdict === 'FAIL' && (
                <div className="flex flex-col gap-[5px] rounded-lg bg-[#fceeee] p-4 text-[#a63f3f]">
                  <p className="text-[11px] leading-normal">WHY THIS FAILED</p>
                  <p className="text-[16px] leading-normal">{delivery.summary || failing?.detail || 'A check did not pass.'}</p>
                </div>
              )}
              {delivery.checks.map((c) => (
                <div key={c.name} className="flex items-center gap-2.5">
                  <img src={c.passed ? circleCheck : circleX} alt="" />
                  <p className="min-w-0 flex-1 text-[12px] leading-normal text-[#0f172a]">{c.name}</p>
                  <p className={`shrink-0 text-[12px] leading-normal ${c.passed ? 'text-[#087f73]' : 'text-[#a63f3f]'}`}>{c.detail}{c.expected ? ` · needs ${c.expected}` : ''}</p>
                </div>
              ))}
              <button type="button" onClick={() => setTextOpen((v) => !v)} className="text-left text-[12px] font-semibold text-[#2563eb]">
                {textOpen ? 'Hide the delivery' : 'View the delivery the test read ↗'}
              </button>
              {textOpen && <p className="whitespace-pre-wrap text-[13px] leading-normal text-[#0f172a]">{delivery.text}</p>}
              {delivery.injectionFlags.length > 0 && (
                <p className="text-[12px] text-[#a63f3f]">Flagged as an injection attempt: {delivery.injectionFlags.join(', ')}. The verifier still scored the text. It did not obey the flag.</p>
              )}
            </section>
          )}

          <aside className="flex items-start gap-3 rounded-lg border border-[#bfdbfe] bg-[#eff6ff] p-4">
            <img src={verdict ? infoMark : clockMark} alt="" />
            <div className="min-w-0 flex-1 text-[#2563eb]">
              <p className="text-[13px] leading-normal">
                {verdict ? 'This result is final' : expiredNoDelivery ? 'No delivery before the deadline' : 'Holds stay in place until the test runs'}
              </p>
              <p className="mt-1 text-[12px] leading-normal">
                {verdict === 'PASS' && `PASS captures ${money(deal.feeMinor)} and releases your ${money(deal.bondMinor)} bond. The session’s first capture still waits for the buyer.`}
                {verdict === 'FAIL' && `A final FAIL voids the uncaptured ${money(deal.feeMinor)} hold. That is not a refund: the money was never taken. Your ${money(deal.bondMinor)} bond is paid to the buyer. A no-show pays the bond to the buyer the same way.`}
                {expiredNoDelivery && `The ${money(deal.feeMinor)} fee hold is voided and your ${money(deal.bondMinor)} bond is paid to the buyer — the same as a final FAIL.`}
                {!verdict && !expiredNoDelivery && `The buyer’s ${money(deal.feeMinor)} and your ${money(deal.bondMinor)} stay as PayPal authorizations. Nothing is captured until the compiled test decides. There is no second delivery after that.`}
              </p>
            </div>
          </aside>

          <section className={`flex flex-col gap-[18px] ${CARD}`}>
            <p className="text-[11px] font-semibold leading-normal text-[#2563eb]">
              {next.kind === 'deliver' ? 'DELIVERY · NOT YET SUBMITTED' : 'NEXT FOR YOU'}
            </p>
            <h2 className="text-[17px] leading-normal text-[#0f172a]">{next.kind === 'deliver' ? 'Send your delivery' : next.title}</h2>
            {next.kind === 'deliver' ? (
              <>
                <div className="flex items-center gap-3 rounded-lg border border-dashed border-[#cbd5e1] bg-[#f8fafb] p-5">
                  <img src={fileMark} alt="" />
                  <div className="min-w-0">
                    <p className="text-[14px] font-semibold text-[#0f172a]">The deliverable is text</p>
                    <p className="text-[11px] text-[#64748b]">The locked test reads it. “Mark as passed” does nothing.</p>
                  </div>
                </div>
                <p className="text-[13px] leading-normal text-[#64748b]">Submitting runs the compiled test once. A PASS captures the fee. A FAIL voids the uncaptured hold and your bond is paid to the buyer.</p>
                <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap">
                  <button type="button" disabled={actions.busy} onClick={() => actions.deliver?.('AGENT')} className={BLUE}>Seller’s agent writes it</button>
                  <button type="button" disabled={actions.busy} onClick={() => actions.deliver?.('SCRIPTED_PASS')} className={GHOST}>Scripted 50-word delivery</button>
                  <button type="button" disabled={actions.busy} onClick={() => actions.deliver?.('SCRIPTED_JUNK')} className={GHOST}>Scripted junk delivery</button>
                </div>
              </>
            ) : (
              <p className="text-[13px] leading-normal text-[#64748b]">{next.body}</p>
            )}
            {next.kind === 'approve' && bond?.approveUrl && (
              <a className={BLUE} href={bond.approveUrl} target="_blank" rel="noreferrer">Approve the bond on PayPal · {money(bond.amountMinor)}</a>
            )}
            {deal.verifier && <DryRunBox verifierId={deal.verifier.id} />}
            {deal.state === 'TERMS_LOCKED' && actions.cancel && <CancelDeal busy={actions.busy} onCancel={actions.cancel} />}
          </section>

          <section className={`flex flex-col gap-[18px] ${CARD}`}>
            <h2 className="text-[17px] leading-normal text-[#0f172a]">Acceptance test · unchanged</h2>
            <div className="flex items-center justify-between gap-3">
              <p className="text-[13px] text-[#0f172a]">Compiled test</p>
              <span className="rounded-md border border-[#bfdbfe] bg-[#edf3fa] px-[9px] py-1 text-[11px] text-[#275e9a]">{deal.verifier ? `${shortHash(deal.verifier.sha256)} · locked` : 'Not compiled yet'}</span>
            </div>
            <p className="text-[13px] leading-normal text-[#64748b]">{deal.jobText}</p>
            <div className="flex items-start gap-2.5">
              <img src={checkMark} alt="" />
              <p className="text-[13px] leading-normal text-[#0f172a]">PASS: every check passes → the full {money(deal.feeMinor)} fee is captured and your {money(deal.bondMinor)} bond is released.</p>
            </div>
            <div className="flex items-start gap-2.5">
              <img src={checkMark} alt="" />
              <p className="text-[13px] leading-normal text-[#0f172a]">FAIL: any check fails → the fee hold is voided and your bond is paid to the buyer. The fee is not split.</p>
            </div>
            <div className="flex items-start gap-2.5">
              <img src={checkMark} alt="" />
              <p className="text-[13px] leading-normal text-[#0f172a]">No delivery at all → the fee hold is voided and your bond is paid to the buyer, the same as a failed test.</p>
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
            <p className="text-[11px] font-semibold leading-normal text-[#64748b]">CURRENT STATE · {deal.state.replaceAll('_', ' ')}</p>
            <h2 className="text-[17px] leading-normal text-[#0f172a]">Money stays separate</h2>
            <dl className="flex flex-col gap-3.5">
              <div>
                <div className="flex gap-3">
                  <dt className="flex-1 text-[13px] text-[#64748b]">Buyer fee authorization</dt>
                  <dd className="text-[14px] text-[#192b3a]">{money(fee?.amountMinor ?? deal.feeMinor)}</dd>
                </div>
                <p className="text-[11px] text-[#64748b]">{fee ? `${JAR_LABEL[fee.jar]}. ${fee.jar === 'HELD' ? 'Held on PayPal. Not captured.' : fee.caption}` : 'Not opened yet.'}</p>
              </div>
              <div className="flex gap-3">
                <dt className="flex-1 text-[13px] text-[#0f172a]">Your work earnings so far</dt>
                <dd className="text-[14px] font-bold text-[#192b3a]">{money(earned(deal))}</dd>
              </div>
              <div className="flex gap-3">
                <dt className="flex-1 text-[13px] text-[#64748b]">Your seller bond</dt>
                <dd className="text-[14px] text-[#192b3a]">{money(bond?.amountMinor ?? deal.bondMinor)}{bond ? ` · ${JAR_LABEL[bond.jar]}` : ''}</dd>
              </div>
              <div className="flex gap-3">
                <dt className="flex-1 text-[13px] text-[#64748b]">Bond compensation paid</dt>
                <dd className="text-[14px] text-[#192b3a]">{money(compensated(deal))}</dd>
              </div>
            </dl>
            <div className="h-px bg-[#e5e7eb]" />
            <p className="text-[12px] leading-normal text-[#64748b]">Settlement follows the test. Capture replaces a hold. It never adds a second charge.</p>
            {bond && canAskPayPal(bond) && (
              <button type="button" disabled={actions.busy} onClick={() => actions.checkHold?.(bond.id)} className={GHOST}>Ask PayPal again</button>
            )}
          </section>

          <section className={`flex flex-col gap-[18px] ${CARD}`}>
            <h2 className="text-[17px] leading-normal text-[#0f172a]">Submission history</h2>
            {deal.transitions.length === 0 ? (
              <p className="text-[13px] text-[#64748b]">No events yet.</p>
            ) : (
              <ul className="flex flex-col gap-3.5">
                {deal.transitions.slice(-4).map((t) => (
                  <li key={`${t.occurredAt}-${t.to}`} className="flex gap-3">
                    <span className="min-w-0 flex-1 text-[13px] text-[#64748b]">{utcStamp(t.occurredAt).split(',')[1]?.trim() ?? utcStamp(t.occurredAt)} · {t.cause || `${t.from} → ${t.to}`}</span>
                    <span className="shrink-0 text-[14px] text-[#192b3a]">{t.to.replaceAll('_', ' ')}</span>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <aside className="flex items-start gap-3 rounded-lg border border-[#bfdbfe] bg-[#eff6ff] p-4 text-[#2563eb]">
            <img src={infoMark} alt="" />
            <div className="min-w-0 flex-1">
              <p className="text-[13px] leading-normal">If the deadline passes</p>
              <p className="mt-1 text-[12px] leading-normal">With no delivery, the {money(deal.feeMinor)} fee hold is voided and your {money(deal.bondMinor)} bond is paid to the buyer. A delivered FAIL follows the same rule: your bond is paid to the buyer.</p>
            </div>
          </aside>
        </div>
      </div>

      <section className="flex flex-col gap-4">
        <h2 className="text-[19px] font-semibold leading-normal text-[#0f172a]">After the final check</h2>
        <p className="text-[13px] leading-normal text-[#64748b]">The two ways this job can settle. The test picks one. A fraction of the fee is not a result here.</p>
        <div className="grid gap-6 md:grid-cols-2">
          <article className={`flex flex-col gap-[18px] ${CARD} ${verdict === 'PASS' ? 'ring-2 ring-[#a7f3d0]' : ''}`}>
            <h3 className="text-[17px] leading-normal text-[#0f172a]">PASS · Paid and bond released</h3>
            <span className="w-fit rounded-md border border-[#a7f3d0] bg-[#d1fae5] px-[9px] py-1 text-[11px] text-[#047857]">{verdict === 'PASS' ? 'This job' : 'The other result'}</span>
            <dl className="flex flex-col gap-3.5">
              <div className="flex gap-3"><dt className="flex-1 text-[13px] text-[#0f172a]">Buyer fee captured to you</dt><dd className="text-[14px] font-bold text-[#192b3a]">{money(deal.feeMinor)}</dd></div>
              <div className="flex gap-3"><dt className="flex-1 text-[13px] text-[#64748b]">Your bond returned</dt><dd className="text-[14px] text-[#192b3a]">{money(deal.bondMinor)}</dd></div>
              <div className="flex gap-3"><dt className="flex-1 text-[13px] text-[#0f172a]">Net work earnings</dt><dd className="text-[14px] font-bold text-[#192b3a]">{money(deal.feeMinor)}</dd></div>
            </dl>
            <p className="text-[12px] leading-normal text-[#64748b]">PASS captures the fee and releases the bond. You do not wait for a manual approval, except the session’s first capture.</p>
          </article>
          <article className={`flex flex-col gap-[18px] ${CARD} ${verdict === 'FAIL' ? 'ring-2 ring-[#f5c2c2]' : ''}`}>
            <h3 className="text-[17px] leading-normal text-[#0f172a]">FAIL · Fee voided, bond paid to the buyer</h3>
            <span className="w-fit rounded-md border border-[#bfdbfe] bg-[#dbeafe] px-[9px] py-1 text-[11px] text-[#1e40af]">{verdict === 'FAIL' ? 'This job' : 'The other result'}</span>
            <dl className="flex flex-col gap-3.5">
              <div className="flex gap-3"><dt className="flex-1 text-[13px] text-[#0f172a]">Buyer fee voided</dt><dd className="text-[14px] font-bold text-[#192b3a]">{money(deal.feeMinor)}</dd></div>
              <div className="flex gap-3"><dt className="flex-1 text-[13px] text-[#64748b]">Your bond paid to the buyer</dt><dd className="text-[14px] text-[#192b3a]">{money(deal.bondMinor)}</dd></div>
              <div className="flex gap-3"><dt className="flex-1 text-[13px] text-[#64748b]">Net work earnings</dt><dd className="text-[14px] text-[#192b3a]">{money(0)}</dd></div>
            </dl>
            <p className="text-[12px] leading-normal text-[#64748b]">A final FAIL voids the whole hold. It is not a refund, and it is not a partial payment.</p>
          </article>
        </div>
      </section>

      <footer className="flex flex-wrap items-center justify-between gap-2 text-[11px] text-[#64748b]">
        <p>HireMind · Clear terms. Verified work.</p>
        <p>A hold is not a charge.</p>
      </footer>
    </div>
  )
}
