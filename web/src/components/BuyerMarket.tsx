import { useCallback, useEffect, useState } from 'react'
import { api, type Contract, type SellerAgent, type Status } from '../api.ts'
import { money, stepOf } from '../model.ts'
import { US1_SENTENCE } from '../scripted.ts'
import { dealPath } from '../role.ts'
import { Icon } from './DeskShell.tsx'
import { NewJob } from './NewJob.tsx'
import infoMark from '../assets/figma-info.svg'
import plusMark from '../assets/figma-plus.svg'
import shieldMark from '../assets/figma-shield.svg'
import walletMark from '../assets/figma-wallet.svg'

type JobFilter = 'all' | 'active' | 'completed'

/** A control plane built before Maya's roster has no GET /api/agents/sellers; it still runs the blurb writer. */
const BLURB_ONLY: SellerAgent[] = [
  { id: 'blurb', title: 'Blurb writer', does: 'short marketing blurbs: a paragraph about a business, a product line or an event', usualMinor: 180, example: US1_SENTENCE },
]

const CARD = 'rounded-xl border border-[#e5e7eb] bg-white shadow-[0px_2px_8px_0px_rgba(15,23,42,0.04)]'
const DONE = new Set(['CANCELLED', 'EXPIRED', 'SETTLED', 'PASS', 'FAIL'])

function jobCount(n: number): string {
  return n === 1 ? '1 job' : `${n} jobs`
}

function workspaceDate(now = new Date()): string {
  const months = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC']
  const day = String(now.getUTCDate()).padStart(2, '0')
  return `${day} ${months[now.getUTCMonth()]} ${now.getUTCFullYear()} · UTC`
}

function utcStamp(iso: string): string {
  const d = new Date(iso)
  const hm = d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', timeZone: 'UTC', hour12: false })
  const today = new Date().toISOString().slice(0, 10) === d.toISOString().slice(0, 10)
  if (today) return `Today, ${hm} UTC`
  const day = d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', timeZone: 'UTC' })
  return `${day}, ${hm} UTC`
}

function lockedDeals(deals: Contract[], sessionId: string | undefined): Contract[] {
  return deals.filter((d) => d.sessionId === sessionId && d.state !== 'DRAFT' && d.state !== 'CANCELLED')
}

function buyerFees(deals: Contract[]) {
  return deals.flatMap((d) => d.holds).filter((h) => h.side === 'BUYER_FEE')
}

function jobStatus(d: Contract): { label: string; className: string } {
  if (d.state === 'PASS' || (d.state === 'SETTLED' && d.delivery?.verdict === 'PASS')) return { label: 'PASS', className: 'bg-[#d1fae5] text-[#047857]' }
  if (d.state === 'FAIL' || d.delivery?.verdict === 'FAIL') return { label: 'FAIL', className: 'bg-[#fee2e2] text-[#991b1b]' }
  if (d.state === 'EXPIRED') return { label: 'Expired', className: 'bg-[#f3f4f6] text-[#64748b]' }
  if (d.state === 'CANCELLED') return { label: 'Cancelled', className: 'bg-[#f3f4f6] text-[#64748b]' }
  if (d.state === 'SETTLED') return { label: 'Settled', className: 'bg-[#d1fae5] text-[#047857]' }
  return { label: 'In progress', className: 'bg-[#dbeafe] text-[#1e40af]' }
}

function feeLine(d: Contract): { amount: string; note: string } {
  const fee = d.holds.find((h) => h.side === 'BUYER_FEE')
  const cents = fee?.amountMinor ?? d.feeMinor
  if (d.state === 'CANCELLED' || d.state === 'EXPIRED' || fee?.jar === 'RELEASED') return { amount: `${money(cents)} voided`, note: 'No charge' }
  if (!fee || fee.jar === 'EMPTY' || fee.jar === 'HELD') return { amount: `${money(cents)} held`, note: 'Not charged' }
  if (fee.jar === 'CAPTURED' || fee.jar === 'PAID') return { amount: `${money(fee.capturedMinor ?? fee.amountMinor)} captured`, note: fee.jar === 'PAID' ? 'Paid out' : 'Captured' }
  return { amount: `${money(fee.amountMinor)} voided`, note: 'No charge' }
}

function jobDetail(d: Contract): string {
  if (d.deadlineAt && !DONE.has(d.state)) return `Due ${utcStamp(d.deadlineAt)} · ${stepOf(d)}`
  if (d.state === 'CANCELLED') return 'Closed · the uncaptured hold was voided'
  if (d.state === 'EXPIRED') return 'Closed · retry window ended'
  if (d.state === 'PASS' || d.state === 'SETTLED') return `Completed · ${stepOf(d)}`
  return `${d.state} · ${stepOf(d)}`
}

/** Session mandate from the control plane. Locked deals count until they are cancelled. */
function SessionGuardrails({ status, deals }: { status: Status | null; deals: Contract[] }) {
  const session = status?.session
  const locked = lockedDeals(deals, session?.id)
  const jobs = locked.length
  const committed = locked.reduce((n, d) => n + (d.feeMinor ?? 0), 0)
  const cap = session?.capMinor ?? 0
  const open = Math.max(0, cap - committed)
  const fill = cap > 0 ? Math.min(100, Math.round((committed / cap) * 100)) : 0
  const code = session ? session.id.replace(/-/g, '').slice(0, 3).toUpperCase() : '—'

  return (
    <div className="flex flex-col gap-4">
      <section className={`flex flex-col gap-[18px] p-6 ${CARD}`}>
        <p className="text-[11px] font-semibold leading-normal tracking-wide text-[#64748b]">CURRENT SESSION · S-{code}</p>
        <h2 className="text-[17px] font-semibold leading-normal text-[#0f172a]">Agent guardrails</h2>
        <dl className="flex flex-col gap-3.5">
          <div className="flex items-start gap-3 text-[#0f172a]">
            <dt className="flex-1 text-[13px] leading-normal">Maximum commitment</dt>
            <dd className="text-[14px] font-bold leading-normal">{session ? money(cap) : '—'}</dd>
          </div>
          <div className="flex items-start gap-3 text-[#0f172a]">
            <dt className="flex-1 text-[13px] leading-normal">Maximum jobs</dt>
            <dd className="text-[14px] font-bold leading-normal">{session ? jobCount(session.maxJobs) : '—'}</dd>
          </div>
          <div className="flex items-start gap-3">
            <dt className="flex-1 text-[13px] leading-normal text-[#64748b]">Already committed</dt>
            <dd className="text-[14px] leading-normal text-[#0f172a]">{session ? `${money(committed)} / ${jobCount(jobs)}` : '—'}</dd>
          </div>
          <div className="flex items-start gap-3">
            <dt className="flex-1 text-[13px] leading-normal text-[#64748b]">Budget not committed</dt>
            <dd className="text-[14px] leading-normal text-[#0f172a]">{session ? money(open) : '—'}</dd>
          </div>
        </dl>
        <div className="flex flex-col gap-2">
          <div className="h-[7px] overflow-hidden rounded-[4px] bg-[#e5e7eb]" role="meter" aria-valuemin={0} aria-valuemax={cap} aria-valuenow={committed} aria-label="Budget already committed">
            <div className="h-[7px] rounded-[4px] bg-[#2563eb]" style={{ width: `${fill}%` }} />
          </div>
          <p className="text-[11px] leading-normal text-[#64748b]">Holds count toward your cap. Capture replaces a hold; it never adds a second charge.</p>
        </div>
        <button
          type="button"
          onClick={() => document.getElementById('buyer-deals')?.scrollIntoView({ behavior: 'smooth', block: 'start' })}
          className="flex min-h-11 w-full items-center justify-center rounded-lg border border-[#e5e7eb] bg-white px-4 py-[11px] text-[13px] font-semibold text-[#0f172a] shadow-[0px_2px_8px_0px_rgba(15,23,42,0.04)]"
        >
          Review session limits
        </button>
        <p className="text-[11px] leading-normal text-[#64748b]">A new commitment needs both available budget and an available job slot.</p>
      </section>
      <aside className="flex items-start gap-3 rounded-lg border border-[#bfdbfe] bg-[#eff6ff] p-4 text-[#2563eb]">
        <img src={infoMark} alt="" />
        <div className="flex min-w-0 flex-1 flex-col gap-1 leading-normal">
          <p className="text-[13px] font-semibold">Held is not paid</p>
          <p className="text-[12px] opacity-85">PayPal holds the agreed fee. A failed test or no delivery voids the uncaptured fee. PayPal then pays you the seller&apos;s bond. The fee is not split, and voiding the fee is not a refund. A no-show or expired deadline works the same way.</p>
        </div>
      </aside>
    </div>
  )
}

export function BuyerHome({ status, hash, go }: { status: Status | null; hash: string; go: (p: string) => void }) {
  const [deals, setDeals] = useState<Contract[]>([])
  const [sellers, setSellers] = useState<SellerAgent[] | null>(null)
  const [pick, setPick] = useState<string>('all')
  const [query, setQuery] = useState('')
  const [jobFilter, setJobFilter] = useState<JobFilter>('all')
  // The agent whose Hire button opened the form; null for "Post a job", undefined while the form is closed.
  const [hire, setHire] = useState<SellerAgent | null | undefined>(undefined)

  const load = useCallback(() => { api.contracts().then(setDeals).catch(() => setDeals([])) }, [])
  useEffect(load, [load])
  useEffect(() => {
    api.sellers().then((s) => setSellers(s.length ? s : BLURB_ONLY)).catch(() => setSellers(BLURB_ONLY))
  }, [])
  useEffect(() => {
    const id = hash === '#buyer-deals' ? 'buyer-deals' : hash === '#buyer-activity' ? 'buyer-activity' : hash === '#buyer-payments' ? 'buyer-payments' : ''
    if (!id) return
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }, [hash, deals.length])

  const session = status?.session
  const locked = lockedDeals(deals, session?.id)
  const jobs = locked.length
  const committed = locked.reduce((n, d) => n + (d.feeMinor ?? 0), 0)
  const cap = session?.capMinor ?? 0
  const open = Math.max(0, cap - committed)
  const sessionDeals = deals.filter((d) => !session || d.sessionId === session.id)
  const fees = buyerFees(sessionDeals)
  const authorized = fees.filter((h) => h.jar === 'HELD').reduce((n, h) => n + h.amountMinor, 0)
  const captured = fees.filter((h) => h.jar === 'CAPTURED' || h.jar === 'PAID').reduce((n, h) => n + (h.capturedMinor ?? h.amountMinor), 0)
  const jobsFull = !!session && jobs >= session.maxJobs
  const budgetFull = !!session && cap > 0 && open <= 0
  const limited = jobsFull || budgetFull
  const slotsLeft = session ? Math.max(0, session.maxJobs - jobs) : 0
  const activeDeals = deals.filter((d) => !DONE.has(d.state))
  const shownJobs = deals.filter((d) => jobFilter === 'all' || (jobFilter === 'active' ? !DONE.has(d.state) : DONE.has(d.state) && d.state !== 'CANCELLED' && d.state !== 'EXPIRED'))
  const latest = deals
    .flatMap((d) => d.transitions.map((t) => ({ ...t, deal: d })))
    .sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))[0]
  const roster = sellers ?? []
  const q = query.trim().toLowerCase()
  const shown = roster.filter((a) => (pick === 'all' || a.id === pick) && `${a.title} ${a.does}`.toLowerCase().includes(q))
  // Without Claude only the scripted blurb deal runs, so the other agents cannot be hired. Unknown while status loads.
  const claudeOff = status?.agentsConfigured === false
  const hireable = (a: SellerAgent) => !claudeOff || a.id === 'blurb'
  const picks: [string, string][] = [['all', `All agents · ${roster.length}`], ...roster.map((a): [string, string] => [a.id, a.title])]

  const metrics: { label: string; value: string; sub: string }[] = [
    { label: 'Authorized · PayPal', value: session ? money(authorized) : '—', sub: 'Held, not charged' },
    { label: 'Captured this session', value: session ? money(captured) : '—', sub: 'Only after verification' },
    { label: 'Uncommitted budget', value: session ? money(open) : '—', sub: session ? `Of your ${money(cap)} session cap` : '—' },
    {
      label: 'Jobs committed',
      value: session ? `${jobs} / ${session.maxJobs}` : '—',
      sub: !session ? '—' : jobsFull ? 'No additional jobs allowed' : `${slotsLeft} more ${slotsLeft === 1 ? 'job' : 'jobs'} allowed`,
    },
  ]

  return (
    <div className="mx-auto flex min-w-0 max-w-7xl flex-col gap-6 px-4 py-6 sm:px-6 sm:py-8">
      <header className="flex flex-col gap-2.5">
        <div className="flex items-center justify-between gap-3 text-[11px] leading-normal">
          <p className="text-[#2563eb]">CLIENT / WORKSPACE</p>
          <p className="shrink-0 text-[#64748b]">{workspaceDate()}</p>
        </div>
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:gap-4">
          <h1 className="min-w-0 flex-1 text-[30px] leading-normal text-[#0f172a]">Your work, under control.</h1>
          <button type="button" onClick={() => setHire(null)} className="inline-flex min-h-11 shrink-0 items-center justify-center gap-2 rounded-lg border border-[#2563eb] bg-[#2563eb] px-4 py-[11px] text-[13px] font-semibold text-white shadow-[0px_6px_16px_0px_rgba(37,99,235,0.2)]">
            <img src={plusMark} alt="" />
            Post a job
          </button>
        </div>
        <p className="text-[14px] leading-normal text-[#64748b]">Give your agent a goal. Set the limits. Pay for verified results.</p>
      </header>

      <section className="flex items-start gap-4 rounded-xl border border-[#bfdbfe] bg-[#eff6ff] p-5 shadow-[0px_2px_8px_0px_rgba(15,23,42,0.04)]">
        <img src={shieldMark} alt="" className="shrink-0" />
        <div className="min-w-0 flex-1">
          <p className="text-[15px] font-semibold leading-normal text-[#0f172a]">{limited ? 'Session limits are protecting your account' : 'Session limits are on'}</p>
          <p className="mt-[5px] text-[13px] leading-normal text-[#2563eb] opacity-85">
            {!session && 'Loading the session mandate.'}
            {session && jobsFull && `${jobs} of ${session.maxJobs} ${session.maxJobs === 1 ? 'job' : 'jobs'} committed. Your agent cannot accept another job until you start a new session.`}
            {session && !jobsFull && budgetFull && `The ${money(cap)} cap is committed. A new job needs a new session.`}
            {session && !limited && `${jobs} of ${session.maxJobs} ${session.maxJobs === 1 ? 'job' : 'jobs'} committed. ${money(open)} is still available.`}
          </p>
        </div>
        {session && (
          <span className={`shrink-0 rounded-md px-[9px] py-1 text-[11px] leading-normal ${limited ? 'bg-[#dbeafe] text-[#1e40af]' : 'bg-[#d1fae5] text-[#047857]'}`}>
            {limited ? 'LIMIT REACHED' : 'WITHIN LIMITS'}
          </span>
        )}
      </section>

      <div id="buyer-payments" className="desk-metrics -mx-4 flex scroll-mt-24 snap-x snap-mandatory gap-6 overflow-x-auto px-4 pb-1 sm:mx-0 sm:grid sm:snap-none sm:grid-cols-2 sm:overflow-visible sm:px-0 lg:grid-cols-4">
        {metrics.map((m) => (
          <article key={m.label} className={`flex w-[16.5rem] max-w-[80vw] shrink-0 snap-start flex-col gap-[7px] p-5 sm:w-auto sm:max-w-none ${CARD}`}>
            <p className="text-[12px] leading-normal text-[#64748b]">{m.label}</p>
            <p className="text-[30px] font-bold leading-normal text-[#0f172a]">{m.value}</p>
            <p className="text-[11px] leading-normal text-[#64748b] opacity-75">{m.sub}</p>
          </article>
        ))}
      </div>

      <div className="grid min-w-0 items-start gap-6 lg:grid-cols-[minmax(0,1fr)_352px]">
        <div className="flex min-w-0 flex-col gap-6">
          <section id="buyer-deals" className={`flex scroll-mt-24 flex-col gap-[18px] p-6 ${CARD}`}>
            <h2 className="text-[17px] font-semibold leading-normal text-[#0f172a]">Your jobs</h2>
            <div className="flex flex-wrap items-center gap-5 text-[12px] leading-normal">
              {([
                ['all', `All jobs · ${deals.length}`],
                ['active', `Active · ${activeDeals.length}`],
                ['completed', 'Completed'],
              ] as [JobFilter, string][]).map(([id, label]) => (
                <button key={id} type="button" onClick={() => setJobFilter(id)} className={jobFilter === id ? 'font-semibold text-[#2563eb]' : 'text-[#64748b] opacity-75'}>{label}</button>
              ))}
            </div>
            <div className="h-px bg-[#e5e7eb]" />
            {shownJobs.length === 0 ? (
              <p className="text-[13px] text-[#64748b]">None yet. Post a job, or hire one of Maya's agents below.</p>
            ) : shownJobs.map((d) => {
              const statusPill = jobStatus(d)
              const fee = feeLine(d)
              return (
                <a key={d.id} href={dealPath('buyer', d.id)} onClick={(e) => { e.preventDefault(); go(dealPath('buyer', d.id)) }} className="flex flex-col gap-3">
                  <span className="flex items-start gap-3">
                    <span className="min-w-0 flex-1">
                      <span className="block text-[14px] leading-normal text-[#0f172a]">{d.jobText}</span>
                      <span className="mt-1 block text-[11px] leading-normal text-[#64748b]">{d.id.slice(0, 8)} · {d.seller.displayName}</span>
                    </span>
                    <span className={`shrink-0 rounded-md px-[9px] py-1 text-[11px] leading-normal ${statusPill.className}`}>{statusPill.label}</span>
                  </span>
                  <span className="block text-[12px] leading-normal text-[#64748b]">{jobDetail(d)}</span>
                  <span className="flex items-start justify-between gap-3">
                    <span className="text-[12px] font-semibold leading-normal text-[#0f172a]">{fee.amount}</span>
                    <span className="text-[11px] leading-normal text-[#64748b]">{fee.note}</span>
                  </span>
                  <span className="h-px bg-[#e5e7eb]" />
                </a>
              )
            })}
            <button type="button" onClick={() => setJobFilter('all')} className="text-left text-[12px] font-semibold leading-normal text-[#2563eb] opacity-90">View all job activity →</button>
          </section>

          <section id="buyer-activity" className={`flex scroll-mt-24 flex-col gap-[18px] p-6 ${CARD}`}>
            <h2 className="text-[17px] font-semibold leading-normal text-[#0f172a]">Latest activity</h2>
            {latest ? (
              <div className="flex items-start gap-3">
                <img src={walletMark} alt="" className="shrink-0" />
                <div className="min-w-0 flex-1">
                  <p className="text-[13px] leading-normal text-[#0f172a]">{latest.cause || `${latest.from} → ${latest.to}`}</p>
                  <p className="mt-1 text-[11px] leading-normal text-[#64748b]">{utcStamp(latest.occurredAt)} · {feeLine(latest.deal).amount} · {money(buyerFees([latest.deal]).filter((h) => h.jar === 'CAPTURED' || h.jar === 'PAID').reduce((n, h) => n + (h.capturedMinor ?? h.amountMinor), 0))} captured</p>
                </div>
              </div>
            ) : (
              <p className="text-[13px] text-[#64748b]">No activity on these jobs yet.</p>
            )}
          </section>
        </div>
        <SessionGuardrails status={status} deals={deals} />
      </div>

      <section className="flex min-w-0 flex-col gap-4">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <h2 className="text-[17px] font-semibold text-[#0f172a]">Agents</h2>
            <p className="text-[13px] text-[#64748b]">Maya sells short writing jobs through {roster.length === 1 ? 'one agent' : `${roster.length} agents`}, all paid into her one PayPal account. Each one negotiates its own price and posts a bond when the terms lock.</p>
          </div>
        </div>
        <div className="desk-metrics flex gap-2 overflow-x-auto pb-1 lg:hidden">
          {picks.map(([id, label]) => (
            <button key={id} type="button" onClick={() => setPick(id)} className={`min-h-11 shrink-0 rounded-full px-4 text-[13px] font-semibold ${pick === id ? 'bg-[#0f172a] text-white' : 'bg-white text-[#64748b] shadow-sm'}`}>{label}</button>
          ))}
        </div>
        <div className="grid min-w-0 items-start gap-6 lg:grid-cols-12">
          <aside className="hidden flex-col gap-6 rounded-xl border border-[#e5e7eb] bg-white p-6 lg:col-span-4 lg:flex xl:col-span-3">
            <div className="flex items-center justify-between">
              <h3 className="text-[15px] font-semibold text-[#0f172a]">Filter agents</h3>
              <button type="button" onClick={() => { setPick('all'); setQuery('') }} className="text-[11px] font-semibold text-[#64748b]">Reset</button>
            </div>
            <label className="flex flex-col gap-1.5 text-[11px] font-bold uppercase tracking-wider text-[#64748b]">
              Search
              <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="e.g. tagline, email…" className="min-h-11 w-full rounded-lg border border-[#e5e7eb] px-3 text-sm font-normal normal-case tracking-normal text-[#0f172a]" />
            </label>
            <div className="flex flex-col gap-1.5">
              {picks.map(([id, label]) => (
                <button key={id} type="button" onClick={() => setPick(id)} className={`flex w-full items-center justify-between rounded-lg px-3 py-2 text-left text-[13px] ${pick === id ? 'bg-[#eff6ff] font-semibold text-[#1e40af]' : 'text-[#64748b]'}`}>
                  <span>{label}</span>
                  {id !== 'all' && <span className="text-[11px]">{money(roster.find((a) => a.id === id)?.usualMinor ?? 0)}</span>}
                </button>
              ))}
            </div>
          </aside>
          <div className="flex min-w-0 flex-col gap-4 lg:col-span-8 xl:col-span-9">
            <label className="relative lg:hidden">
              <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search agents" aria-label="Search agents" className="min-h-11 w-full rounded-lg border border-[#e5e7eb] bg-white px-3 text-sm" />
            </label>
            {sellers === null && <p className="text-[13px] text-[#64748b]">Loading Maya's agents.</p>}
            {sellers !== null && shown.length === 0 && <p className="text-[13px] text-[#64748b]">No agent matches. Reset the filter to see all of them.</p>}
            <div className="grid min-w-0 gap-4 md:grid-cols-2">
              {shown.map((a) => (
                <article key={a.id} className={`flex min-w-0 flex-col gap-4 p-5 ${CARD}`}>
                  <div className="min-w-0">
                    <h3 className="text-[15px] font-semibold text-[#0f172a]">Maya's {a.title.toLowerCase()}</h3>
                    <p className="text-[12px] text-[#64748b]">Writes {a.does}</p>
                  </div>
                  <p className="text-[13px] leading-normal text-[#64748b]"><span className="text-[11px] font-bold uppercase tracking-wider">Try </span>{a.example}</p>
                  <div className="flex items-end justify-between gap-3 border-t border-[#e5e7eb] pt-4">
                    <p className="text-[22px] font-bold leading-none text-[#0f172a]">{money(a.usualMinor)} <span className="text-[12px] font-normal text-[#64748b]">usual price</span></p>
                    {hireable(a) ? (
                      <button type="button" onClick={() => setHire(a)} className="min-h-11 rounded-lg bg-[#2563eb] px-4 text-[13px] font-semibold text-white">Hire</button>
                    ) : (
                      <span className="text-right text-[12px] font-semibold text-[#64748b]">Needs Claude<br />(no API key set)</span>
                    )}
                  </div>
                </article>
              ))}
            </div>
          </div>
        </div>
      </section>

      {hire !== undefined && (
        <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 sm:items-center sm:p-4" role="dialog" aria-labelledby="hire-title">
          <div className="flex max-h-[92svh] w-full max-w-2xl flex-col gap-4 overflow-y-auto rounded-t-2xl bg-white p-6 shadow-2xl sm:rounded-2xl">
            <div className="flex items-center justify-between">
              <h3 id="hire-title" className="text-[17px] font-semibold text-[#0f172a]">{hire ? `Hire Maya's ${hire.title.toLowerCase()}` : 'Post a job'}</h3>
              <button type="button" onClick={() => setHire(undefined)} className="p-1 text-[#64748b]" aria-label="Close"><Icon name="close" /></button>
            </div>
            <p className="text-[13px] text-[#64748b]">Your agent turns the sentence into a price cap, a deadline and a compiled test, and picks which of Maya's agents does it. PayPal holds the fee; nothing is charged until the test passes.</p>
            <NewJob key={hire?.id ?? 'post'} heading="" initial={hire?.example ?? US1_SENTENCE} agents={status?.agentsConfigured ?? false} open={(id) => go(dealPath('buyer', id))} />
          </div>
        </div>
      )}
    </div>
  )
}
