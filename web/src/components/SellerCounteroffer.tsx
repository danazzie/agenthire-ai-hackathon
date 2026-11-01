import { useEffect, useState } from 'react'
import { api, ApiError, type Contract, type Offer, type Status } from '../api.ts'
import { money, shortHash } from '../model.ts'
import shieldMark from '../assets/figma-shield-offer.svg'
import clockMark from '../assets/figma-clock.svg'

const CARD = 'rounded-xl border border-[#e5e7eb] bg-white p-6 shadow-[0px_2px_8px_0px_rgba(15,23,42,0.04)]'
const FIELD = 'min-h-[46px] w-full rounded-lg border border-[#e5e7eb] bg-white px-[13px] py-[13px] text-[14px] leading-normal text-[#0f172a]'

function workspaceDate(now = new Date()): string {
  const months = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC']
  return `${String(now.getUTCDate()).padStart(2, '0')} ${months[now.getUTCMonth()]} ${now.getUTCFullYear()} · UTC`
}

function utcStamp(iso: string): string {
  const d = new Date(iso)
  const hm = d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', timeZone: 'UTC', hour12: false })
  const same = d.toISOString().slice(0, 10) === new Date().toISOString().slice(0, 10)
  return same ? `Today · ${hm}` : `${d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', timeZone: 'UTC' })} · ${hm}`
}

function asUtcInput(iso: string): string {
  const d = new Date(iso)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())}T${p(d.getUTCHours())}:${p(d.getUTCMinutes())}`
}

function fromUtcInput(value: string): string {
  return new Date(`${value}:00Z`).toISOString()
}

function centsOf(dollars: string): number | null {
  const n = Number(dollars.replace(/[$,\s]/g, ''))
  if (!Number.isFinite(n) || n <= 0) return null
  return Math.round(n * 100)
}

function latest(offers: Offer[], party: string): Offer | undefined {
  return [...offers].reverse().find((o) => o.party === party)
}

function ratioOf(bond: number, fee: number): string {
  const pct = Math.round((bond / fee) * 10000) / 100
  return String(pct)
}

function defaultDeadline(): string {
  const due = new Date()
  due.setUTCHours(18, 0, 0, 0)
  if (due.getTime() - Date.now() < 3_600_000) due.setUTCDate(due.getUTCDate() + 1)
  return asUtcInput(due.toISOString())
}

/** Maya's counteroffer. A PROPOSE does not lock terms or open a hold. */
export function SellerCounteroffer({ deal, onBack }: { deal: Contract; onBack?: () => void }) {
  const buyer = latest(deal.offers, 'alex')
  const mine = latest(deal.offers, 'maya')
  const verifierId = buyer?.verifierId ?? mine?.verifierId ?? deal.verifier?.id ?? deal.offers[0]?.verifierId ?? null
  const originalFee = buyer?.feeMinor ?? deal.feeCapMinor
  const originalBond = buyer?.bondMinor ?? null
  const originalRatio = originalBond != null && originalFee > 0 ? Number(ratioOf(originalBond, originalFee)) : null
  const originalDeadline = buyer?.deadlineAt ?? deal.deadlineAt

  const [fee, setFee] = useState(() => ((mine?.feeMinor ?? buyer?.feeMinor ?? deal.feeCapMinor) / 100).toFixed(2))
  const [ratio, setRatio] = useState(() => (originalBond != null && originalFee > 0 ? ratioOf(originalBond, originalFee) : '20'))
  const [deadline, setDeadline] = useState(() => (mine?.deadlineAt ?? originalDeadline) ? asUtcInput((mine?.deadlineAt ?? originalDeadline)!) : defaultDeadline())
  const [note, setNote] = useState(() => mine?.note ?? '')
  const [saved, setSaved] = useState(false)
  const [sent, setSent] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState<Status | null>(null)

  useEffect(() => { api.status().then(setStatus).catch(() => setStatus(null)) }, [])

  const feeCents = centsOf(fee)
  const ratioN = Number(ratio)
  const bondCents = feeCents != null && Number.isFinite(ratioN) ? Math.round((feeCents * ratioN) / 100) : null
  const deadlineIso = deadline ? fromUtcInput(deadline) : ''
  const deadlineOk = !!deadline && new Date(deadlineIso).getTime() > Date.now()
  const overCap = feeCents != null && feeCents > deal.feeCapMinor
  const bondTooBig = feeCents != null && bondCents != null && bondCents * 2 > feeCents
  const bondMissing = bondCents == null || bondCents <= 0
  const problems = [
    feeCents == null ? 'The fee has to be a positive amount.' : null,
    overCap ? `The fee is above Alex’s ${money(deal.feeCapMinor)} maximum. A proposal can say so; he cannot accept it.` : null,
    bondMissing ? 'The bond has to be more than zero.' : null,
    bondTooBig ? 'The bond is never above 50% of the fee.' : null,
    !deadlineOk ? 'Choose a future time in UTC.' : null,
    !verifierId ? 'Alex has not attached a compiled test yet, so this proposal cannot be sent.' : null,
  ].filter((p): p is string => !!p)
  const ready = problems.length === 0 && feeCents != null && bondCents != null && verifierId

  const feeDelta = feeCents == null ? null : feeCents - originalFee
  const bondDeltaLabel = originalRatio == null ? 'new' : Number(ratio) === originalRatio ? 'same' : `${Number(ratio) > originalRatio ? '+' : '−'}${Math.abs(Number(ratio) - originalRatio)} pts`
  const hourDelta = originalDeadline && deadlineOk ? Math.round((new Date(deadlineIso).getTime() - new Date(originalDeadline).getTime()) / 3_600_000) : null

  function edit<T>(set: (v: T) => void, value: T) {
    setSaved(false)
    setSent(false)
    set(value)
  }

  async function send() {
    if (!ready || !verifierId || feeCents == null || bondCents == null) return
    setBusy(true)
    setError(null)
    try {
      await api.offer(deal.id, 'maya', { kind: 'PROPOSE', feeMinor: feeCents, bondMinor: bondCents, deadlineAt: deadlineIso, verifierId, note: note.trim() || undefined })
      setSent(true)
    } catch (e) {
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="mx-auto flex min-w-0 max-w-7xl flex-col gap-6 px-4 py-6 sm:px-6 sm:py-8">
      <header className="flex flex-col gap-2.5">
        <div className="flex items-center justify-between gap-3 text-[11px] leading-normal">
          <p className="font-semibold text-[#2563eb]">SELLER AGENT / COUNTEROFFER</p>
          <p className="shrink-0 text-[#64748b]">{workspaceDate()}</p>
        </div>
        {onBack && (
          <button type="button" onClick={onBack} className="self-start text-[13px] font-semibold text-[#2563eb]">Back to the opportunity</button>
        )}
        <h1 className="text-[30px] font-semibold leading-normal text-[#0f172a]">Better terms. Same clear test.</h1>
        <p className="text-[14px] leading-normal text-[#64748b]">
          {deal.jobText} · {deal.id.slice(0, 8)} · {deal.buyer.displayName} ↔ {deal.seller.displayName}
          {deal.verifier ? ` · ${shortHash(deal.verifier.sha256)}` : ''}
        </p>
      </header>

      <div className="flex flex-wrap items-center gap-3">
        <span className="rounded-md border border-[#bfdbfe] bg-[#dbeafe] px-[9px] py-1 text-[11px] font-semibold text-[#1e40af]">{sent ? 'Counteroffer sent' : 'Draft counteroffer'}</span>
        <p className="text-[12px] text-[#64748b]">{sent ? 'Sent · Alex has not accepted these terms, and nothing is held' : saved ? 'Saved on this page · Not sent' : 'Not sent · No agreement or bond hold yet'}</p>
      </div>

      {error && <p role="alert" className="rounded-lg bg-[#fceeee] px-4 py-3 text-sm text-[#a63f3f]">{error}</p>}

      <div className="grid min-w-0 items-start gap-6 lg:grid-cols-[minmax(0,1fr)_352px]">
        <div className="flex min-w-0 flex-col gap-6">
          <section className={`flex flex-col gap-[18px] ${CARD}`}>
            <h2 className="text-[17px] font-semibold text-[#0f172a]">Original request</h2>
            <div className="flex items-start justify-between gap-3 text-[12px]">
              <p className="text-[#0f172a]">{deal.buyer.displayName} · Buyer</p>
              <p className="shrink-0 text-[11px] text-[#64748b]">{buyer ? utcStamp(buyer.createdAt) : 'No offer yet'}</p>
            </div>
            <p className="text-[15px] leading-normal text-[#0f172a]">“{buyer?.note || deal.jobText}”</p>
          </section>

          <section className={`flex flex-col gap-[18px] ${CARD}`}>
            <h2 className="text-[17px] font-semibold text-[#0f172a]">Your counteroffer</h2>
            <div className="grid gap-6 sm:grid-cols-2">
              <label className="flex flex-col gap-[7px]">
                <span className="text-[12px] font-semibold text-[#0f172a]">Proposed fee · USD</span>
                <input className={FIELD} inputMode="decimal" value={fee} onChange={(e) => edit(setFee, e.target.value)} aria-label="Proposed fee in dollars" />
                <span className="text-[11px] text-[#64748b]">{overCap ? `Above the client’s ${money(deal.feeCapMinor)} maximum.` : `Within the client’s ${money(deal.feeCapMinor)} maximum.`}</span>
              </label>
              <label className="flex flex-col gap-[7px]">
                <span className="text-[12px] font-semibold text-[#0f172a]">Proposed bond ratio</span>
                <input className={FIELD} inputMode="numeric" value={ratio} onChange={(e) => edit(setRatio, e.target.value)} aria-label="Proposed bond ratio percent" />
                <span className="text-[11px] text-[#64748b]">Your bond: {feeCents != null && bondCents != null ? `${money(feeCents)} × ${ratio}% = ${money(bondCents)}` : '—'}. Never above 50%.</span>
              </label>
            </div>
            <label className="flex flex-col gap-[7px]">
              <span className="text-[12px] font-semibold text-[#0f172a]">Proposed delivery deadline · UTC</span>
              <input type="datetime-local" className={FIELD} value={deadline} onChange={(e) => edit(setDeadline, e.target.value)} aria-label="Proposed deadline in UTC" />
              <span className="text-[11px] text-[#64748b]">{hourDelta == null ? 'Alex has to accept a changed deadline. It is not automatic.' : hourDelta === 0 ? 'Same deadline Alex asked for.' : `${Math.abs(hourDelta)} hour${Math.abs(hourDelta) === 1 ? '' : 's'} ${hourDelta > 0 ? 'later' : 'earlier'} than requested. Alex has to accept it.`}</span>
            </label>
            <label className="flex flex-col gap-[7px]">
              <span className="text-[12px] font-semibold text-[#0f172a]">Message to client</span>
              <textarea className={`${FIELD} min-h-[104px]`} rows={3} value={note} onChange={(e) => edit(setNote, e.target.value)} placeholder="The compiled test stays the same." />
            </label>
          </section>

          <section className={`flex flex-col gap-[18px] ${CARD}`}>
            <h2 className="text-[17px] font-semibold text-[#0f172a]">Compare before you send</h2>
            <div className="grid gap-6 sm:grid-cols-2">
              <div className="flex flex-col gap-4 rounded-lg bg-[#f8fafc] p-4">
                <p className="text-[11px] font-semibold text-[#64748b]">ORIGINAL REQUEST</p>
                <dl className="flex flex-col gap-3.5 text-[13px]">
                  <div className="flex gap-3"><dt className="flex-1 text-[#64748b]">Fee</dt><dd className="text-[14px] text-[#0f172a]">{money(originalFee)}</dd></div>
                  <div className="flex gap-3"><dt className="flex-1 text-[#64748b]">Deadline</dt><dd className="text-[14px] text-[#0f172a]">{originalDeadline ? utcStamp(originalDeadline) : '—'}</dd></div>
                  <div className="flex gap-3"><dt className="flex-1 text-[#64748b]">Bond ratio</dt><dd className="text-[14px] text-[#0f172a]">{originalRatio == null ? '—' : `${originalRatio}%`}</dd></div>
                  <div className="flex gap-3"><dt className="flex-1 text-[#64748b]">Seller bond hold</dt><dd className="text-[14px] text-[#0f172a]">{originalBond == null ? '—' : money(originalBond)}</dd></div>
                </dl>
              </div>
              <div className="flex flex-col gap-4 rounded-lg bg-[#eff6ff] p-4">
                <p className="text-[11px] font-semibold text-[#2563eb]">YOUR PROPOSAL</p>
                <dl className="flex flex-col gap-3.5 text-[13px]">
                  <div className="flex gap-3"><dt className="flex-1 text-[#0f172a]">Fee · {feeDelta == null ? '—' : feeDelta === 0 ? 'same' : `${feeDelta > 0 ? '+' : '−'}${money(Math.abs(feeDelta))}`}</dt><dd className="text-[14px] font-bold text-[#0f172a]">{feeCents == null ? '—' : money(feeCents)}</dd></div>
                  <div className="flex gap-3"><dt className="flex-1 text-[#64748b]">Deadline · {hourDelta == null ? '—' : hourDelta === 0 ? 'same' : `${hourDelta > 0 ? '+' : '−'}${Math.abs(hourDelta)} hour${Math.abs(hourDelta) === 1 ? '' : 's'}`}</dt><dd className="text-[14px] text-[#0f172a]">{deadlineOk ? utcStamp(deadlineIso) : '—'}</dd></div>
                  <div className="flex gap-3"><dt className="flex-1 text-[#64748b]">Bond ratio · {bondDeltaLabel}</dt><dd className="text-[14px] text-[#0f172a]">{Number.isFinite(ratioN) ? `${ratio}%` : '—'}</dd></div>
                  <div className="flex gap-3"><dt className="flex-1 text-[#64748b]">Seller bond hold</dt><dd className="text-[14px] text-[#0f172a]">{bondCents == null ? '—' : money(bondCents)}</dd></div>
                </dl>
              </div>
            </div>
            <p className="text-[12px] leading-normal text-[#64748b]">Unchanged: {deal.jobText}. The compiled test stays the same. There is no second delivery after the deadline.</p>
          </section>
        </div>

        <div className="flex min-w-0 flex-col gap-5">
          <section className={`flex flex-col gap-[18px] ${CARD}`}>
            <h2 className="text-[17px] font-semibold text-[#0f172a]">What you’re proposing</h2>
            <dl className="flex flex-col gap-3.5">
              <div className="flex gap-3"><dt className="flex-1 text-[13px] text-[#0f172a]">Fee on full PASS</dt><dd className="text-[14px] font-bold text-[#0f172a]">{feeCents == null ? '—' : money(feeCents)}</dd></div>
              <div className="flex gap-3"><dt className="flex-1 text-[13px] text-[#64748b]">Your bond to hold</dt><dd className="text-[14px] text-[#0f172a]">{bondCents == null ? '—' : money(bondCents)}</dd></div>
              <div className="flex gap-3"><dt className="flex-1 text-[13px] text-[#64748b]">Bond released on PASS</dt><dd className="text-[14px] text-[#0f172a]">{bondCents == null ? '—' : money(bondCents)}</dd></div>
              <div className="flex gap-3"><dt className="flex-1 text-[13px] text-[#0f172a]">Net work earnings</dt><dd className="text-[14px] font-bold text-[#0f172a]">{feeCents == null ? '—' : money(feeCents)}</dd></div>
            </dl>
            <div className="h-px bg-[#e5e7eb]" />
            <p className="text-[12px] leading-normal text-[#64748b]">Sending does not accept the job or hold your bond. After Alex accepts the same terms, you both accept once more and the holds open. Nothing is charged.</p>
            <button type="button" disabled={!ready || busy} onClick={send} className="flex min-h-11 w-full items-center justify-center rounded-lg border border-[#2563eb] bg-[#2563eb] text-[13px] font-semibold text-white disabled:opacity-40">{busy ? 'Sending…' : 'Send counteroffer'}</button>
            <button type="button" onClick={() => setSaved(true)} className="flex min-h-11 w-full items-center justify-center rounded-lg border border-[#e5e7eb] bg-white text-[13px] font-semibold text-[#0f172a]">Save draft</button>
          </section>

          <aside className="flex items-start gap-3 rounded-lg bg-[#eff6ff] p-4 text-[#2563eb]">
            <img src={shieldMark} alt="" />
            <div>
              <p className="text-[13px] font-semibold">Client guardrails respected</p>
              <p className="mt-1 text-[12px] leading-normal">
                {feeCents == null ? 'Enter a fee to check it against the cap.' : `${money(feeCents)} ${overCap ? 'is above' : 'fits'} Alex’s ${money(deal.feeCapMinor)} maximum${status ? ` and the ${money(status.session.capMinor)} session cap` : ''}. A later deadline is not auto-approved. Alex must accept it.`}
              </p>
            </div>
          </aside>

          <section className={`flex flex-col gap-[18px] ${CARD}`}>
            <h2 className="text-[17px] font-semibold text-[#0f172a]">What happens next</h2>
            {[
              'Alex reviews the changed terms.',
              `Both of you accept the same fee, bond, deadline and test. PayPal then holds ${feeCents == null ? 'the fee' : money(feeCents)}. Nothing is charged.`,
              `You approve the ${bondCents == null ? 'bond' : money(bondCents)} bond. Only then does the work begin.`,
            ].map((step, i) => (
              <div key={step} className="flex items-start gap-2.5">
                <span className="flex size-[23px] shrink-0 items-center justify-center rounded-full bg-[#f4f6f8] text-[11px] text-[#192b3a]">{i + 1}</span>
                <p className="text-[12px] leading-normal text-[#64748b]">{step}</p>
              </div>
            ))}
          </section>

          {!ready && (
            <aside className="flex items-start gap-3 rounded-lg bg-[#fff7ed] p-4 text-[#c2410c]">
              <img src={clockMark} alt="" />
              <div>
                <p className="text-[13px] font-semibold">This proposal cannot be sent yet</p>
                <p className="mt-1 text-[12px] leading-normal">{problems[0]}</p>
              </div>
            </aside>
          )}
        </div>
      </div>

      <footer className="flex flex-wrap items-center justify-between gap-2 text-[11px] text-[#64748b]">
        <p>HireMind · Clear terms. Verified work.</p>
        <p>A proposal is not a hold.</p>
      </footer>
    </div>
  )
}
