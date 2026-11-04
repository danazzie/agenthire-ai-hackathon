import { useEffect, useState } from 'react'
import { api, ApiError, type Contract, type Offer, type Verifier } from '../api.ts'
import { money, shortHash } from '../model.ts'
import checkMark from '../assets/figma-check.svg'
import downloadMark from '../assets/figma-download.svg'
import fileCode from '../assets/figma-file-code.svg'
import fileMark from '../assets/figma-file.svg'
import infoMark from '../assets/figma-info.svg'
import { ratioOf } from '../bond.ts'

const CARD = 'rounded-xl border border-[#e5e7eb] bg-white p-6 shadow-[0px_2px_10px_0px_rgba(15,23,42,0.04)]'
const BLUE = 'flex min-h-11 w-full items-center justify-center rounded-lg border border-[#2563eb] bg-[#2563eb] px-4 text-[13px] font-semibold text-white shadow-[0px_8px_18px_0px_rgba(37,99,235,0.2)] disabled:opacity-40'
const GHOST = 'flex min-h-11 w-full items-center justify-center rounded-lg border border-[#e5e7eb] bg-white px-4 text-[13px] font-semibold text-[#0f172a] disabled:opacity-40'

function workspaceDate(now = new Date()): string {
  const months = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC']
  return `${String(now.getUTCDate()).padStart(2, '0')} ${months[now.getUTCMonth()]} ${now.getUTCFullYear()} · UTC`
}

function deliverBy(iso: string): string {
  const d = new Date(iso)
  const day = d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', timeZone: 'UTC' })
  const hm = d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', timeZone: 'UTC', hour12: false })
  return `${day} · ${hm} UTC`
}

function untilDeadline(iso: string): string {
  const ms = new Date(iso).getTime() - Date.now()
  if (ms <= 0) return 'The deadline has passed'
  const h = Math.floor(ms / 3_600_000)
  const m = Math.floor((ms % 3_600_000) / 60_000)
  return `${h}h ${m}m until the deadline`
}

function sameTerms(a: Offer, b: Offer): boolean {
  return a.feeMinor === b.feeMinor
    && a.bondMinor === b.bondMinor
    && a.verifierId === b.verifierId
    && new Date(a.deadlineAt).getTime() === new Date(b.deadlineAt).getTime()
}

function partyName(deal: Contract, handle: string): string {
  if (handle === deal.buyer.handle) return deal.buyer.displayName
  if (handle === deal.seller.handle) return deal.seller.displayName
  return handle
}

function describeSpec(spec: string | null): string {
  if (!spec) return 'The compiled test stays locked to these terms.'
  try {
    const checks = (JSON.parse(spec) as { checks?: { type?: string; equals?: unknown; min?: number; max?: number; phrase?: string }[] }).checks ?? []
    const parts = checks.map((c) => {
      if (c.type === 'word_count' && typeof c.equals === 'number') return `exactly ${c.equals} words`
      if (c.type === 'word_count' && c.min != null && c.max != null) return `${c.min}–${c.max} words`
      if (c.type === 'word_count' && c.min != null) return `at least ${c.min} words`
      if (c.type === 'word_count' && c.max != null) return `at most ${c.max} words`
      if (c.type === 'language') return 'English'
      if (c.type === 'not_placeholder') return 'not placeholder text'
      if (c.type === 'contains' && c.phrase) return `contains “${c.phrase}”`
      if (c.type === 'not_contains' && c.phrase) return `does not contain “${c.phrase}”`
      if (c.type === 'char_count' && c.min != null && c.max != null) return `${c.min}–${c.max} characters`
      if (c.type === 'char_count' && c.min != null) return `at least ${c.min} characters`
      if (c.type === 'char_count' && c.max != null) return `at most ${c.max} characters`
      return null
    }).filter((p): p is string => !!p)
    return parts.length ? `The delivery must be ${parts.join(', ')}.` : 'The compiled test stays locked to these terms.'
  } catch {
    return 'The compiled test stays locked to these terms.'
  }
}

/** Maya reviews a draft and accepts it, or opens the counteroffer. Accepting does not hold money until Alex has accepted the same terms. */
export function SellerOpportunity({ deal, onNegotiate }: { deal: Contract; onNegotiate: () => void }) {
  const offers = [...deal.offers].sort((a, b) => a.createdAt.localeCompare(b.createdAt))
  const current = offers.at(-1)
  const buyerOffer = [...offers].reverse().find((o) => o.party === deal.buyer.handle)
  const sellerOffer = [...offers].reverse().find((o) => o.party === deal.seller.handle)
  const willLock = !!current && !!buyerOffer && buyerOffer.kind === 'ACCEPT' && sameTerms(buyerOffer, current)
  const alreadyAccepted = !!current && !!sellerOffer && sellerOffer.kind === 'ACCEPT' && sameTerms(sellerOffer, current)
  const overCap = !!current && current.feeMinor > deal.feeCapMinor
  const deadlineOk = !!current && new Date(current.deadlineAt).getTime() > Date.now()
  const bondOk = !!current && current.bondMinor > 0 && current.bondMinor * 2 <= current.feeMinor
  const [reviewed, setReviewed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [sourceOpen, setSourceOpen] = useState(false)
  const [usage, setUsage] = useState<{ jobs: number; committed: number; cap: number; maxJobs: number } | null>(null)
  const [verifier, setVerifier] = useState<Verifier | null>(deal.verifier)
  const verifierId = deal.verifier?.id ?? current?.verifierId ?? null

  useEffect(() => {
    if (deal.verifier) {
      setVerifier(deal.verifier)
      return
    }
    if (!verifierId) return
    api.verifier(verifierId).then(setVerifier).catch(() => setVerifier(null))
  }, [deal.verifier, verifierId])

  useEffect(() => {
    Promise.all([api.status(), api.contracts()]).then(([status, deals]) => {
      const locked = deals.filter((d) => d.sessionId === status.session.id && d.state !== 'DRAFT' && d.state !== 'CANCELLED')
      setUsage({
        jobs: locked.length,
        committed: locked.reduce((n, d) => n + (d.feeMinor ?? 0), 0),
        cap: status.session.capMinor,
        maxJobs: status.session.maxJobs,
      })
    }).catch(() => setUsage(null))
  }, [])

  const mandateBlocked = !!current && willLock && !!usage && (usage.jobs >= usage.maxJobs || usage.committed + current.feeMinor > usage.cap)
  const canAccept = !!current && !alreadyAccepted && !overCap && deadlineOk && bondOk && !!current.verifierId && !mandateBlocked
  const fee = current?.feeMinor
  const bond = current?.bondMinor
  const ratio = fee && bond ? ratioOf(bond, fee) : null
  const first = offers[0]
  const deadlineNote = current && first && new Date(first.deadlineAt).getTime() !== new Date(current.deadlineAt).getTime()
    ? `Changed from ${deliverBy(first.deadlineAt)}`
    : current ? untilDeadline(current.deadlineAt) : null
  const note = buyerOffer?.note && buyerOffer.note !== deal.jobText ? buyerOffer.note : null

  const chip = !current
    ? 'Waiting for a proposal'
    : alreadyAccepted
      ? 'Waiting for Alex'
      : overCap
        ? 'Above the fee cap'
        : !deadlineOk
          ? 'Deadline passed'
          : mandateBlocked
            ? 'Session is full'
            : 'Ready to accept'
  const statusLine = !current
    ? 'Alex has not proposed terms yet. Nothing is held.'
    : alreadyAccepted
      ? 'You accepted these terms. Alex still has to accept the same ones. Nothing is held.'
      : willLock
        ? 'Alex already accepted these terms. Accepting opens both PayPal holds. Nothing is charged.'
        : 'These terms are still a draft. Work has not started. Nothing is held.'

  function downloadSource() {
    if (!verifier?.source) return
    const blob = new Blob([verifier.source], { type: 'text/plain' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `compiled-test-${verifier.sha256.slice(0, 8)}.txt`
    a.click()
    URL.revokeObjectURL(url)
  }

  async function accept() {
    if (!canAccept || !current || !reviewed) return
    setBusy(true)
    setError(null)
    try {
      await api.offer(deal.id, 'maya', {
        kind: 'ACCEPT',
        feeMinor: current.feeMinor,
        bondMinor: current.bondMinor,
        deadlineAt: current.deadlineAt,
        verifierId: current.verifierId,
      })
    } catch (e) {
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="mx-auto flex min-w-0 max-w-7xl flex-col gap-8 px-4 py-6 sm:px-6 sm:py-8">
      <header className="flex flex-col gap-2">
        <div className="flex items-center justify-between gap-3 text-[11px] leading-normal">
          <p className="font-semibold text-[#2563eb]">SELLER / OPPORTUNITY</p>
          <p className="shrink-0 text-[#64748b]">{workspaceDate()}</p>
        </div>
        <h1 className="text-[30px] leading-normal text-[#0f172a]">Know the test. Then commit.</h1>
        <p className="text-[14px] leading-normal text-[#64748b]">
          {deal.jobText} · {deal.id.slice(0, 8)} · Posted by {deal.buyer.displayName}
        </p>
      </header>

      <div className="flex flex-wrap items-center gap-3">
        <span className="rounded-md bg-[#dbeafe] px-[9px] py-1 text-[11px] leading-normal text-[#1e40af]">{chip}</span>
        <p className="text-[12px] leading-normal text-[#64748b]">{statusLine}</p>
      </div>

      {error && <p role="alert" className="rounded-lg bg-[#fceeee] px-4 py-3 text-sm text-[#a63f3f]">{error}</p>}

      <div className="grid min-w-0 items-start gap-6 lg:grid-cols-[minmax(0,1fr)_352px]">
        <div className="flex min-w-0 flex-col gap-6">
          <section className={`flex flex-col gap-[18px] ${CARD}`}>
            <p className="text-[11px] font-semibold leading-normal text-[#2563eb]">THE JOB</p>
            <h2 className="text-[17px] leading-normal text-[#0f172a]">The work</h2>
            <p className="text-[18px] font-semibold leading-normal text-[#0f172a]">{deal.jobText}</p>
            <p className="text-[13px] leading-normal text-[#64748b]">
              {note ?? 'The acceptance test below decides the fee. It stays the same if you accept these terms.'}
            </p>
            <div className="flex items-center gap-3.5 rounded-lg border border-[#e5e7eb] bg-[#f8fafb] p-4">
              <img src={fileMark} alt="" />
              <div className="min-w-0 flex-1">
                <p className="truncate text-[13px] font-semibold text-[#0f172a]">
                  {verifier ? 'compiled-test.txt' : verifierId ? 'Loading the compiled test…' : 'No compiled test yet'}
                </p>
                <p className="text-[11px] text-[#64748b]">
                  {verifier ? `${verifier.language} · ${shortHash(verifier.sha256)} · compiled ${deliverBy(verifier.compiledAt)}` : verifierId ? 'The test on Alex’s proposal.' : 'Alex has not attached a compiled test.'}
                </p>
              </div>
              {verifier?.source && (
                <button type="button" onClick={downloadSource} aria-label="Download the compiled test" className="shrink-0">
                  <img src={downloadMark} alt="" />
                </button>
              )}
            </div>
            <div className="grid gap-6 sm:grid-cols-2">
              <div>
                <p className="text-[11px] font-semibold text-[#2563eb]">{current ? 'PROPOSED FEE' : 'FEE CAP'}</p>
                <p className="text-[28px] font-bold leading-normal text-[#0f172a]">{money(fee ?? deal.feeCapMinor)}</p>
              </div>
              <div>
                <p className="text-[11px] font-semibold text-[#2563eb]">DELIVER BY</p>
                <p className="text-[17px] font-bold leading-normal text-[#0f172a]">{current ? deliverBy(current.deadlineAt) : 'No deadline yet'}</p>
                {deadlineNote && <p className="text-[11px] text-[#2563eb]">{deadlineNote}</p>}
              </div>
            </div>
          </section>

          <section className={`flex flex-col gap-[18px] ${CARD}`}>
            <p className="text-[11px] font-semibold leading-normal text-[#2563eb]">VISIBLE BEFORE ACCEPTANCE</p>
            <h2 className="text-[17px] leading-normal text-[#0f172a]">Exactly how your work is judged</h2>
            <div className="flex items-center justify-between gap-3">
              <p className="text-[13px] text-[#0f172a]">Compiled test</p>
              <span className="rounded-md bg-[#dbeafe] px-[9px] py-1 text-[11px] text-[#1e40af]">
                {verifier ? `${shortHash(verifier.sha256)} · compiled` : verifierId ? 'Loading' : 'Not compiled yet'}
              </span>
            </div>
            <p className="text-[13px] leading-normal text-[#64748b]">{verifier ? describeSpec(verifier.spec) : verifierId ? 'Loading the compiled test.' : 'The compiled test stays locked to these terms.'}</p>
            <div className="flex items-start gap-2.5">
              <img src={checkMark} alt="" />
              <p className="text-[13px] leading-normal text-[#0f172a]">PASS: every check passes → the full {money(fee)} fee is captured and your {money(bond)} bond is released.</p>
            </div>
            <div className="flex items-start gap-2.5">
              <img src={checkMark} alt="" />
              <p className="text-[13px] leading-normal text-[#0f172a]">FAIL: any check fails → the fee hold is voided and your bond is paid to the buyer. The fee is not split.</p>
            </div>
            <div className="flex items-start gap-2.5">
              <img src={checkMark} alt="" />
              <p className="text-[13px] leading-normal text-[#0f172a]">No delivery at all → your bond is paid to the buyer. That is not the same as a failed test.</p>
            </div>
            {verifier?.source && (
              <button type="button" onClick={() => setSourceOpen((v) => !v)} className="flex items-center gap-2 text-left text-[12px] text-[#2563eb]">
                <img src={fileCode} alt="" />
                {sourceOpen ? 'Hide test source' : 'View test source ↗'}
              </button>
            )}
            {sourceOpen && verifier?.source && (
              <pre className="max-h-64 overflow-auto whitespace-pre-wrap rounded-lg bg-[#f8fafb] p-3 text-[11px] leading-normal text-[#0f172a]">{verifier.source}</pre>
            )}
            <aside className="flex items-start gap-3 rounded-lg bg-[#dbeafe] p-4 text-[#1e40af]">
              <img src={infoMark} alt="" />
              <div className="min-w-0">
                <p className="text-[13px] leading-normal">One delivery. One result.</p>
                <p className="mt-1 text-[12px] leading-normal">There is no second delivery after the verdict. A failed check voids the whole fee hold. The fee is not split.</p>
              </div>
            </aside>
          </section>

          <section id="agreement-history" className={`flex flex-col gap-[18px] ${CARD}`}>
            <h2 className="text-[17px] leading-normal text-[#0f172a]">Agreement history</h2>
            {offers.length === 0 ? (
              <p className="text-[13px] text-[#64748b]">No offers yet.</p>
            ) : (
              <div className="flex flex-col gap-3">
                {offers.map((offer) => (
                  <div key={offer.id} className="flex gap-3">
                    <p className="min-w-0 flex-1 text-[13px] text-[#64748b]">{partyName(deal, offer.party)} {offer.kind === 'ACCEPT' ? 'accepted' : 'proposed'}</p>
                    <p className="shrink-0 text-[14px] text-[#0f172a]">{money(offer.feeMinor)} · {deliverBy(offer.deadlineAt)}</p>
                  </div>
                ))}
                {ratio && (
                  <div className="flex gap-3">
                    <p className="min-w-0 flex-1 text-[13px] text-[#64748b]">Bond ratio</p>
                    <p className="shrink-0 text-[14px] text-[#0f172a]">{ratio}%</p>
                  </div>
                )}
              </div>
            )}
          </section>
        </div>

        <div className="flex min-w-0 flex-col gap-5">
          <section className={`flex flex-col gap-[18px] ${CARD}`}>
            <p className="text-[11px] font-semibold leading-normal text-[#2563eb]">REVIEW BEFORE COMMITTING</p>
            <h2 className="text-[17px] leading-normal text-[#0f172a]">Accept with a bond</h2>
            <dl className="flex flex-col gap-3">
              <div className="flex gap-3">
                <dt className="flex-1 text-[13px] text-[#64748b]">Proposed job fee</dt>
                <dd className="text-[14px] text-[#0f172a]">{money(fee)}</dd>
              </div>
              <div className="flex gap-3">
                <dt className="flex-1 text-[13px] text-[#64748b]">Seller bond ratio</dt>
                <dd className="text-[14px] text-[#0f172a]">{ratio ? `${ratio}%` : '—'}</dd>
              </div>
              <div>
                <div className="flex gap-3">
                  <dt className="flex-1 text-[13px] text-[#0f172a]">Your bond hold</dt>
                  <dd className="text-[14px] font-bold text-[#0f172a]">{money(bond)}</dd>
                </div>
                <p className="text-[11px] text-[#64748b]">{fee && ratio ? `${money(fee)} × ${ratio}%. Held when you approve it on PayPal.` : 'A bond is required, and it is never above 50% of the fee.'}</p>
              </div>
              <div>
                <div className="flex gap-3">
                  <dt className="flex-1 text-[13px] text-[#64748b]">Client fee authorization</dt>
                  <dd className="text-[14px] text-[#0f172a]">{money(fee)}</dd>
                </div>
                <p className="text-[11px] text-[#64748b]">{willLock ? 'Opens when you accept. Not paid to you yet.' : 'Opens when both of you accept the same terms. Not paid to you yet.'}</p>
              </div>
            </dl>
            <div className="h-px bg-[#e5e7eb]" />
            <p className="text-[13px] font-bold leading-normal text-[#0f172a]">On PASS: {money(fee)} paid to you, {money(bond)} bond returned.</p>
            <p className="text-[12px] leading-normal text-[#64748b]">A final test failure voids the fee hold and pays your bond to the buyer. The fee is not split. If you submit nothing by the deadline, the bond is paid to the buyer.</p>
            <label className="flex items-start gap-2.5 text-[13px] leading-normal text-[#0f172a]">
              <input type="checkbox" className="mt-0.5 size-4 shrink-0 accent-[#2563eb]" checked={reviewed} onChange={(e) => setReviewed(e.target.checked)} />
              <span>I’ve reviewed the exact test, deadline and no-show bond terms.</span>
            </label>
            <button type="button" disabled={!canAccept || !reviewed || busy} onClick={accept} className={BLUE}>
              {busy ? 'Accepting…' : alreadyAccepted ? 'You accepted these terms' : willLock ? `Accept & hold ${money(bond)} bond` : 'Accept these terms'}
            </button>
            <button type="button" onClick={() => document.getElementById('agreement-history')?.scrollIntoView({ behavior: 'smooth', block: 'start' })} className={GHOST}>
              Review negotiated terms
            </button>
            {mandateBlocked && usage && (
              <p className="text-[12px] leading-normal text-[#a63f3f]">This session already has {usage.jobs} of {usage.maxJobs} jobs committed. Accepting these terms would lock another one, so it cannot be sent.</p>
            )}
            {overCap && <p className="text-[12px] leading-normal text-[#a63f3f]">This fee is above Alex’s {money(deal.feeCapMinor)} maximum, so it cannot be accepted.</p>}
          </section>

          <aside className="flex items-start gap-3 rounded-lg bg-[#dbeafe] p-4 text-[#1e40af]">
            <img src={infoMark} alt="" />
            <div className="min-w-0">
              <p className="text-[13px] leading-normal">Your fee is not your bond</p>
              <p className="mt-1 text-[12px] leading-normal">The buyer’s {money(fee)} authorization funds the work. Your separate {money(bond)} bond signals commitment. Bond return is not additional earnings.</p>
            </div>
          </aside>

          <section className="flex flex-col gap-[18px] rounded-xl border border-[#e5e7eb] bg-[#f8fafb] p-6 shadow-[0px_2px_10px_0px_rgba(15,23,42,0.04)]">
            <h2 className="text-[17px] leading-normal text-[#0f172a]">Need to change the terms?</h2>
            <p className="text-[12px] leading-normal text-[#64748b]">Counter the fee, deadline or bond ratio before accepting. Both sides must agree to the same terms. A proposal does not open a hold.</p>
            <button type="button" onClick={onNegotiate} className={GHOST}>Open negotiation</button>
          </section>
        </div>
      </div>

      <footer className="flex flex-wrap items-center justify-between gap-2 text-[11px] text-[#64748b]">
        <p>HireMind · Clear terms. Verified work.</p>
        <p>Accepting is not a hold until both of you agree.</p>
      </footer>
    </div>
  )
}
