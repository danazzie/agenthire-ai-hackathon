import { useCallback, useEffect, useState } from 'react'
import { api, type Contract } from '../api.ts'
import { JAR_LABEL, money, stepOf } from '../model.ts'
import { dealPath, nextAction } from '../role.ts'
import { Icon, Metric, MetricRow } from './DeskShell.tsx'

type Filter = 'all' | 'needs' | 'waiting' | 'done' | 'talk' | 'deliver'

function bucket(deal: Contract): Filter {
  const kind = nextAction('seller', deal).kind
  if (kind === 'done') return 'done'
  if (kind === 'approve' || kind === 'deliver') return 'needs'
  return 'waiting'
}

export function SellerHome({ hash, go }: { hash: string; go: (p: string) => void }) {
  const [deals, setDeals] = useState<Contract[]>([])
  const [filter, setFilter] = useState<Filter>('all')
  const load = useCallback(() => { api.contracts().then(setDeals).catch(() => setDeals([])) }, [])
  useEffect(load, [load])
  useEffect(() => {
    if (hash === '#seller-talk') setFilter('talk')
    else if (hash === '#seller-deliver') setFilter('deliver')
    else if (hash === '') setFilter((current) => (current === 'talk' || current === 'deliver' ? 'all' : current))
    const id = hash === '#seller-wallet' ? 'seller-wallet' : hash === '#seller-talk' || hash === '#seller-deliver' ? 'seller-jobs' : ''
    if (id) document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }, [hash, deals.length])

  const open = deals.filter((d) => d.state !== 'CANCELLED' && d.state !== 'SETTLED')
  const needs = deals.filter((d) => bucket(d) === 'needs')
  const bondHeld = deals.reduce((n, d) => n + d.holds.filter((h) => h.side === 'SELLER_BOND' && h.jar === 'HELD').reduce((a, h) => a + h.amountMinor, 0), 0)
  const shown = deals.filter((d) => filter === 'all' || (filter === 'talk' ? d.state === 'DRAFT' : filter === 'deliver' ? nextAction('seller', d).kind === 'deliver' : bucket(d) === filter))
  function choose(id: Filter) {
    setFilter(id)
    const href = id === 'talk' ? '/seller#seller-talk' : id === 'deliver' ? '/seller#seller-deliver' : '/seller'
    if (window.location.pathname + window.location.hash !== href && (id === 'talk' || id === 'deliver' || window.location.hash.startsWith('#seller-'))) go(href)
  }

  const filters: [Filter, string, string, number][] = [
    ['all', 'inbox', 'All jobs', deals.length],
    ['talk', 'forum', 'Negotiations', deals.filter((d) => d.state === 'DRAFT').length],
    ['deliver', 'local_shipping', 'Deliveries', deals.filter((d) => nextAction('seller', d).kind === 'deliver').length],
    ['needs', 'priority_high', 'Needs you', needs.length],
    ['waiting', 'hourglass_empty', 'Waiting', deals.filter((d) => bucket(d) === 'waiting').length],
    ['done', 'check_circle', 'Settled', deals.filter((d) => bucket(d) === 'done').length],
  ]

  return (
    <div className="mx-auto flex min-w-0 max-w-7xl flex-col gap-5 px-4 py-5 sm:gap-8 sm:px-6 sm:py-6">
      <div className="flex min-w-0 flex-col justify-between gap-4 lg:flex-row lg:items-center">
        <div className="min-w-0">
          <p className="text-[11px] font-semibold uppercase tracking-[0.6px] text-[#2563eb]">Seller / Inbox</p>
          <h1 className="mt-1 text-[30px] leading-tight tracking-[-0.5px] text-[#0f172a]">Your inbox</h1>
          <p className="mt-1 max-w-3xl text-[14px] leading-normal text-[#64748b]">Alex posts the job. You post a $0.40 bond — held, not charged. The compiled test, not a mood, decides who gets paid.</p>
        </div>
        <button type="button" onClick={() => go('/buyer')} className="inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-xl bg-white px-4 py-2 text-[15px] font-semibold shadow-sm sm:w-auto sm:justify-start">
          <Icon name="storefront" className="text-[18px] text-[var(--bm-primary)]" /> Open buyer marketplace
        </button>
      </div>

      <div id="seller-wallet" className="scroll-mt-24 max-sm:order-2">
      <MetricRow>
        <Metric icon="edit_document" label="Your listing" value="50-word blurbs" sub="Maya's agent · copy" />
        <Metric icon="lock" label="Bond held" value={money(bondHeld || 0)} sub={bondHeld ? 'PayPal authorizations' : 'No bond held yet'} />
        <Metric icon="priority_high" label="Needs you" value={`${needs.length} job${needs.length === 1 ? '' : 's'}`} sub="Approve or deliver" />
        <Metric icon="work" label="Open jobs" value={`${open.length}`} sub={open.length ? 'Terms locked or moving' : 'Waiting for Alex'} />
      </MetricRow>
      </div>

      <div className="grid min-w-0 items-start gap-8 max-sm:order-1 lg:grid-cols-12">
        <aside className="order-2 flex min-w-0 flex-col gap-6 lg:order-1 lg:col-span-4 xl:col-span-3">
          <article className="flex flex-col gap-4 rounded-2xl bg-white p-6 shadow-sm">
            <div className="flex items-center gap-3.5">
              <span className="flex size-14 items-center justify-center rounded-2xl bg-[var(--bm-low)]"><Icon name="edit_document" className="text-[28px] text-[var(--bm-primary)]" /></span>
              <div>
                <h2 className="flex items-center gap-1.5 text-[17px] font-semibold">Maya's agent <Icon name="verified" className="text-[18px] text-[var(--bm-primary-c)]" /></h2>
                <p className="text-[13px] text-[var(--bm-on-var)]">The seller in this demo</p>
              </div>
            </div>
            <p className="text-[13px] leading-relaxed text-[var(--bm-on-var)]">You write the coffee-shop blurb. Your $0.40 bond is a PayPal authorization. It is released if the test passes, never charged if you deliver on time and the verifier says PASS.</p>
            <div className="flex min-w-0 flex-wrap items-center justify-between gap-2 rounded-xl bg-[color-mix(in_srgb,var(--bm-primary-c)_10%,white)] p-3 text-[11px] font-semibold">
              <span className="flex min-w-0 items-center gap-2 break-words"><Icon name="security" className="shrink-0 text-[18px] text-[var(--bm-primary)]" /> $0.40 bond, held not charged</span>
              <span className="text-[var(--bm-primary)]">Released if you pass</span>
            </div>
          </article>
          <div className="hidden flex-col gap-2 rounded-2xl bg-white p-6 shadow-sm lg:flex">
            <h2 className="flex items-center gap-2 text-[17px] font-semibold"><Icon name="filter_list" className="text-[20px] text-[var(--bm-primary)]" /> Filter inbox</h2>
            {filters.map(([id, icon, label, n]) => (
              <button key={id} type="button" onClick={() => choose(id)} className={`flex w-full items-center justify-between rounded-xl px-3 py-2 text-[13px] ${filter === id ? 'bg-[var(--bm-low)] font-semibold' : 'text-[var(--bm-on-var)] hover:bg-[var(--bm-low)]'}`}>
                <span className="flex items-center gap-2"><Icon name={icon} className="text-[16px]" /> {label}</span>
                <span className="rounded-full bg-[var(--bm-low)] px-2 text-[11px]">{n}</span>
              </button>
            ))}
          </div>
          <div className="rounded-2xl bg-[var(--bm-low)] p-6">
            <Icon name="verified_user" className="text-[22px] text-[var(--bm-primary)]" />
            <h3 className="mt-3 text-[17px] font-semibold">Why you post a bond</h3>
            <p className="mt-1 text-[13px] text-[var(--bm-on-var)]">Alex's fee and your bond are both holds. If the work fails its test, the bond can move as compensation. Void is not a refund: the money was never taken.</p>
          </div>
        </aside>

        <section id="seller-jobs" className="order-1 flex min-w-0 scroll-mt-24 flex-col gap-3 sm:gap-6 lg:order-2 lg:col-span-8 xl:col-span-9">
          <div className="desk-metrics flex gap-2 overflow-x-auto pb-1 lg:hidden">
            {filters.map(([id, , label, n]) => (
              <button key={id} type="button" onClick={() => choose(id)} className={`min-h-11 shrink-0 rounded-full px-4 text-[13px] font-semibold ${filter === id ? 'bg-[#2563eb] text-white' : 'bg-white text-[var(--bm-on-var)] shadow-sm'}`}>{label} {n}</button>
            ))}
          </div>
          <div className="hidden flex-wrap items-center justify-between gap-3 rounded-2xl bg-white p-4 shadow-sm sm:flex">
            <p className="text-[13px] font-semibold">Showing: <span className="text-[var(--bm-primary)]">{shown.length === 1 ? '1 job' : `${shown.length} jobs`}</span></p>
          </div>
          {shown.length === 0 ? (
            <div className="rounded-2xl bg-white p-8 shadow-sm">
              <h2 className="text-[17px] font-semibold">{filter === 'all' ? 'Waiting for a job' : 'Nothing in this view'}</h2>
              <p className="mt-2 max-w-xl text-[13px] text-[var(--bm-on-var)]">Jobs from Alex show up here. Open one to review the test, then agree the fee and bond.</p>
              <button type="button" onClick={() => go('/buyer')} className="mt-4 min-h-11 w-full rounded-xl bg-[#2563eb] px-4 text-[13px] font-semibold text-white sm:w-auto">Hire Maya as Alex</button>
            </div>
          ) : (
            <div className="grid gap-6">
              {shown.map((d) => {
                const next = nextAction('seller', d)
                const mine = d.holds.find((h) => h.side === 'SELLER_BOND')
                return (
                  <article key={d.id} className="flex min-w-0 flex-col justify-between gap-4 rounded-2xl bg-white p-5 shadow-sm sm:gap-5 sm:p-6">
                    <div className="order-1 flex min-w-0 flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                      <div className="min-w-0">
                        <span className="rounded-full bg-[var(--bm-low)] px-2.5 py-1 text-[11px] font-semibold">{d.state === 'CANCELLED' || d.state === 'SETTLED' ? d.state : `${d.state} · ${stepOf(d)}`}</span>
                        <h3 className="mt-3 break-words text-[17px] font-semibold">{d.jobText}</h3>
                        <p className="mt-1 text-[13px] text-[var(--bm-on-var)]">Alex's agent · fee {money(d.feeMinor)} · your bond {money(d.bondMinor)}</p>
                      </div>
                      <div className="text-left sm:text-right">
                        <div className="text-[11px] uppercase tracking-wider text-[var(--bm-on-var)]">Your bond</div>
                        <div className="text-[28px] font-extrabold leading-none">{money(d.bondMinor)}</div>
                        <div className="text-[13px] text-[var(--bm-on-var)]">{mine ? JAR_LABEL[mine.jar] : 'Empty'}</div>
                      </div>
                    </div>
                    <div className="order-3 rounded-xl bg-[var(--bm-low)] p-4 sm:order-2">
                      <div className="text-[11px] font-semibold uppercase tracking-wider text-[var(--bm-on-var)]">Next for you</div>
                      <div className="mt-1 text-[15px] font-semibold">{next.title}</div>
                      <p className="mt-1 break-words text-[13px] text-[var(--bm-on-var)]">{next.body}</p>
                    </div>
                    <div className="order-2 flex flex-col gap-3 border-t border-[var(--bm-low)] pt-4 sm:order-3 sm:flex-row sm:items-center sm:justify-between">
                      <span className="text-[13px] text-[var(--bm-on-var)]">{d.buyer.displayName} is the buyer</span>
                      <button type="button" onClick={() => go(dealPath('seller', d.id))} className="min-h-11 w-full rounded-xl bg-[#2563eb] px-4 text-[13px] font-semibold text-white sm:w-auto">
                        {next.kind === 'approve' ? 'Approve the bond' : next.kind === 'deliver' ? 'Deliver the work' : 'Open this job'}
                      </button>
                    </div>
                  </article>
                )
              })}
            </div>
          )}
        </section>
      </div>
    </div>
  )
}
