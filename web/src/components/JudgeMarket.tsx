import { useCallback, useEffect, useState } from 'react'
import { api, ApiError, runnerConnected, type Contract, type Status } from '../api.ts'
import { JAR_LABEL, money, stepOf } from '../model.ts'
import { dealPath } from '../role.ts'
import { NewJob } from './NewJob.tsx'
import { Icon, Metric, MetricRow } from './DeskShell.tsx'

function jarOf(deal: Contract, side: 'BUYER_FEE' | 'SELLER_BOND') {
  return deal.holds.find((h) => h.side === side)?.jar ?? 'EMPTY'
}

function ResetDemo({ done }: { done: () => void }) {
  const [armed, setArmed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  async function reset() {
    setBusy(true)
    try {
      const result = await api.resetDemo()
      const voided = result.cancelled.length
      setMessage(`New session started. ${voided === 0 ? 'No deal needed cancelling.' : `${voided} unfunded deal${voided === 1 ? '' : 's'} cancelled; any placed hold is being voided.`}`)
      done()
    } catch (e) {
      setMessage(e instanceof ApiError ? `${e.code}: ${e.message}` : String(e))
    } finally {
      setBusy(false)
      setArmed(false)
    }
  }

  return (
    <div className="flex flex-col gap-3">
      {armed ? (
        <div className="flex flex-col gap-3">
          <p className="text-[13px] text-[var(--bm-on-var)]">Cancel unfunded deals, void their holds and start a new session?</p>
          <div className="flex flex-wrap gap-2">
            <button type="button" disabled={busy} onClick={reset} className="min-h-11 rounded-xl bg-[#2563eb] px-4 text-[13px] font-semibold text-white disabled:opacity-40">Yes, reset</button>
            <button type="button" disabled={busy} onClick={() => setArmed(false)} className="min-h-11 rounded-xl px-4 text-[13px] font-semibold text-[var(--bm-on-var)]">Keep</button>
          </div>
        </div>
      ) : (
        <button type="button" onClick={() => { setMessage(null); setArmed(true) }} className="min-h-11 rounded-xl bg-white px-4 text-[13px] font-semibold shadow-sm">Reset demo</button>
      )}
      {message && <p role="status" className="text-[13px] text-[var(--bm-on-var)]">{message}</p>}
    </div>
  )
}

export function JudgeHome({ status, hash, go }: { status: Status | null; hash: string; go: (p: string) => void }) {
  const [deals, setDeals] = useState<Contract[]>([])
  const load = useCallback(() => { api.contracts().then(setDeals).catch(() => setDeals([])) }, [])
  useEffect(load, [load])
  useEffect(() => {
    if (hash !== '#judge-deals') return
    document.getElementById('judge-deals')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }, [hash, deals.length])

  const held = deals.reduce((n, d) => n + d.holds.filter((h) => h.jar === 'HELD').reduce((a, h) => a + h.amountMinor, 0), 0)
  const open = deals.filter((d) => d.state !== 'CANCELLED' && d.state !== 'SETTLED')

  return (
    <div className="mx-auto flex min-w-0 max-w-7xl flex-col gap-5 px-4 py-5 sm:gap-8 sm:px-6 sm:py-6">
      <div className="flex min-w-0 flex-col justify-between gap-4 lg:flex-row lg:items-center">
        <div className="min-w-0">
          <p className="text-[11px] font-semibold uppercase tracking-[0.6px] text-[#2563eb]">Judge / Control room</p>
          <h1 className="mt-1 text-[30px] leading-tight tracking-[-0.5px] text-[#0f172a]">Control room</h1>
          <p className="mt-1 max-w-3xl text-[14px] leading-normal text-[#64748b]">See both sides of a job. Alex approves the fee. Maya approves the bond and delivers the work. Reset starts a fresh session.</p>
          {status && (
            <p className="mt-2 text-[13px] text-[var(--bm-on-var)]">
              Session cap {money(status.session.capMinor)} · {status.session.maxJobs} jobs
              {status.session.firstCaptureConfirmedAt ? ' · first capture confirmed' : ' · first capture still needs a person'}
              {' · '}webhooks {status.webhooksConfigured ? 'on' : 'off'}
            </p>
          )}
        </div>
        <ResetDemo done={load} />
      </div>

      <MetricRow className="max-sm:order-2">
        <Metric icon="hub" label="Session deals" value={`${deals.length}`} sub={open.length ? `${open.length} still moving` : 'None open'} />
        <Metric icon="lock" label="Held, not charged" value={money(held || 0)} sub={held ? 'PayPal authorizations' : 'No hold yet'} />
        <Metric icon="account_balance" label="PayPal sandbox" value={status?.paypalConfigured ? 'Connected' : 'Not configured'} sub={status ? (status.webhooksConfigured ? 'Webhooks on' : 'Webhooks off') : 'Status unavailable'} />
        <Metric icon="psychology" label="Claude agents" value={status?.agentsConfigured ? 'On' : 'Off'} sub={status?.agentsHost === 'astropods' ? (runnerConnected(status) ? 'Negotiating on Astropods' : 'Astropods runner not connected') : status?.agentsConfigured ? 'A draft can negotiate' : 'Start a job below'} />
      </MetricRow>

      <div className="grid min-w-0 items-start gap-8 max-sm:order-1 lg:grid-cols-12">
        <aside className="order-2 flex min-w-0 flex-col gap-6 lg:order-1 lg:col-span-4 xl:col-span-3">
          <div className="rounded-2xl bg-white p-6 shadow-sm">
            <h2 className="flex items-center gap-2 text-[17px] font-semibold"><Icon name="groups" className="text-[20px] text-[var(--bm-primary)]" /> The two desks</h2>
            <p className="mt-2 text-[13px] text-[var(--bm-on-var)]">Alex approves the fee. Maya approves the bond and delivers the work. Open either desk to act as that person.</p>
            <div className="mt-4 flex flex-col gap-2">
              <button type="button" onClick={() => go('/buyer')} className="min-h-11 rounded-xl bg-[#2563eb] px-4 text-[13px] font-semibold text-white">Alex's marketplace</button>
              <button type="button" onClick={() => go('/seller')} className="min-h-11 rounded-xl bg-white px-4 text-[13px] font-semibold shadow-sm">Maya's inbox</button>
            </div>
          </div>
        </aside>

        <section className="order-1 flex min-w-0 flex-col gap-6 lg:order-2 lg:col-span-8 xl:col-span-9">
          <NewJob heading="Start a job for the session" agents={status?.agentsConfigured ?? false} open={(id) => go(dealPath('judge', id))} />

          <section id="judge-deals" className="scroll-mt-24 rounded-2xl bg-white p-6 shadow-sm">
            <h2 className="text-[17px] font-semibold">This session's deals</h2>
            {deals.length === 0 ? (
              <p className="mt-2 text-sm text-[var(--bm-on-var)]">None yet. Start a job below, or hire Maya from Alex's desk.</p>
            ) : (
              <ul className="mt-4 flex flex-col gap-3">
                {deals.map((d) => (
                  <li key={d.id} className="rounded-xl bg-[var(--bm-low)] p-4">
                    <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
                      <div className="min-w-0">
                        <div className="text-[11px] font-semibold uppercase tracking-wider text-[var(--bm-on-var)]">
                          {d.state === 'CANCELLED' || d.state === 'SETTLED' ? d.state : `${d.state} · ${stepOf(d)}`}
                        </div>
                        <div className="mt-1 break-words font-semibold">{d.jobText}</div>
                        {d.outcome && <p className="mt-1 text-[13px] text-[var(--bm-on-var)]">{d.outcome}</p>}
                      </div>
                      <div className="flex flex-wrap gap-2 text-[12px] font-semibold">
                        <span className="rounded-full bg-white px-2.5 py-1">Fee {money(d.feeMinor)} · {JAR_LABEL[jarOf(d, 'BUYER_FEE')]}</span>
                        <span className="rounded-full bg-white px-2.5 py-1">Bond {money(d.bondMinor)} · {JAR_LABEL[jarOf(d, 'SELLER_BOND')]}</span>
                      </div>
                    </div>
                    <div className="mt-3 flex flex-wrap gap-3 text-[13px] font-semibold">
                      <button type="button" onClick={() => go(dealPath('judge', d.id))} className="text-[var(--bm-primary)] underline-offset-4 hover:underline">Open both sides</button>
                      <button type="button" onClick={() => go(dealPath('buyer', d.id))} className="text-[var(--bm-on-var)] underline-offset-4 hover:underline">Alex's desk</button>
                      <button type="button" onClick={() => go(dealPath('seller', d.id))} className="text-[var(--bm-on-var)] underline-offset-4 hover:underline">Maya's desk</button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </section>
      </div>
    </div>
  )
}
