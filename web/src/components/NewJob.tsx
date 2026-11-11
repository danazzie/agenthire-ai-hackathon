import { useEffect, useState } from 'react'
import { api, ApiError, type JobDraft, type SellerAgent } from '../api.ts'
import { money, shortHash } from '../model.ts'
import { scriptedDeal, US1_SENTENCE } from '../scripted.ts'

const primary = 'inline-flex min-h-11 w-full items-center justify-center rounded-xl bg-[#2563eb] px-4 py-2 text-[13px] font-semibold text-white disabled:opacity-40 sm:w-auto'
const secondary = 'inline-flex min-h-11 w-full items-center justify-center rounded-xl border border-[var(--bm-highest)] bg-white px-4 py-2 text-[13px] font-semibold disabled:opacity-40 sm:w-auto'

/** Screen 1 (US-1): one sentence becomes a cap, a deadline and a compiled test. Nothing touches PayPal until confirmed. */
export function NewJob({ agents, open, heading = '1 Post a job', initial = US1_SENTENCE }: { agents: boolean; open: (id: string) => void; heading?: string; initial?: string }) {
  const [sentence, setSentence] = useState(initial)
  const [draft, setDraft] = useState<JobDraft | null>(null)
  const [sellers, setSellers] = useState<SellerAgent[]>([])

  // The seller's agents, each with a sentence to try. Needs no Claude; an older control plane has none to list.
  useEffect(() => {
    api.sellers().then(setSellers).catch(() => setSellers([]))
  }, [])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function run(work: () => Promise<void>) {
    setBusy(true)
    setError(null)
    try {
      await work()
    } catch (e) {
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="flex min-w-0 flex-col gap-4 rounded-2xl bg-white p-6 shadow-sm">
      {heading && <h2 className="text-[17px] font-semibold">{heading}</h2>}
      <label className="text-sm text-[var(--bm-on-var)]" htmlFor="sentence">Describe the job in one sentence: what, the most you'll pay, when, and how "done" is checked.</label>
      <textarea
        id="sentence"
        rows={3}
        value={sentence}
        onChange={(e) => { setSentence(e.target.value); setDraft(null) }}
        className="min-h-24 w-full min-w-0 resize-y rounded-xl bg-[var(--bm-low)] px-3 py-2 text-base"
      />
      {sellers.length > 0 && (
        <div className="flex min-w-0 flex-col gap-2">
          <p className="text-xs uppercase text-[var(--bm-on-var)]">Maya's agents: pick one to try its kind of job</p>
          <div className="flex min-w-0 flex-wrap gap-2">
            {sellers.map((s) => (
              <button key={s.id} type="button" disabled={busy}
                onClick={() => { setSentence(s.example); setDraft(null); setError(null) }}
                title={`${s.does} · usually ${money(s.usualMinor)}`}
                aria-pressed={sentence === s.example}
                className={`min-h-9 rounded-full px-3 text-[13px] font-semibold disabled:opacity-40 ${sentence === s.example ? 'bg-[#0f172a] text-white' : 'bg-[var(--bm-low)] text-[var(--bm-on-var)]'}`}>
                {s.title}
              </button>
            ))}
          </div>
        </div>
      )}
      <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:flex-wrap">
        <button type="button" disabled={busy || !agents} onClick={() => run(async () => setDraft(await api.draftJob(sentence)))}
          title={agents ? undefined : 'The Claude agents are off: ANTHROPIC_API_KEY is not set'}
          className={primary}>
          Draft with the buyer's agent
        </button>
        <button type="button" disabled={busy} onClick={() => run(async () => open((await scriptedDeal()).id))}
          className={secondary}>
          Scripted job (no Claude)
        </button>
      </div>
      {!agents && <p className="text-sm text-[var(--bm-on-var)]">The Claude agents are off (no ANTHROPIC_API_KEY). The scripted job ignores this box and runs the prototype negotiation through the same API.</p>}
      {error && <p role="alert" className="text-sm text-red-800">{error}</p>}
      {draft && (
        <div className="flex min-w-0 flex-col gap-3 rounded-xl bg-[var(--bm-low)] p-4">
          <dl className="grid min-w-0 gap-3 text-sm sm:grid-cols-2 lg:grid-cols-4">
            <div className="min-w-0"><dt className="text-xs uppercase text-[var(--bm-on-var)]">Job</dt><dd className="break-words">{draft.jobText}</dd></div>
            <div><dt className="text-xs uppercase text-[var(--bm-on-var)]">Price cap</dt><dd>{money(draft.feeCapMinor)}</dd></div>
            <div><dt className="text-xs uppercase text-[var(--bm-on-var)]">Deadline</dt><dd>{new Date(draft.deadlineAt).toLocaleString([], { weekday: 'short', hour: '2-digit', minute: '2-digit' })}</dd></div>
            <div><dt className="text-xs uppercase text-[var(--bm-on-var)]">Seller</dt><dd>{draft.sellerAgent ? `Maya's ${draft.sellerAgent.title.toLowerCase()}` : 'another agent'}</dd></div>
          </dl>
          <p className="break-words text-sm">
            <span className="text-xs uppercase text-[var(--bm-on-var)]">The test </span>
            <span className="font-mono">{draft.verifier.spec}</span> <span className="font-mono text-[var(--bm-on-var)]">{shortHash(draft.verifier.sha256)}</span>
          </p>
          <details className="text-sm">
            <summary className="cursor-pointer">The compiled verifier (the code that will decide)</summary>
            <pre className="mt-2 max-h-72 overflow-auto rounded-xl bg-slate-950 p-3 text-xs text-slate-100">{draft.verifier.source}</pre>
          </details>
          <button type="button" disabled={busy} onClick={() => run(async () => open((await api.startJob(draft)).id))}
            className="inline-flex min-h-11 items-center justify-center self-start rounded-xl bg-paid px-4 py-2 text-[13px] font-semibold text-white disabled:opacity-40">
            Confirm this test and let the agents negotiate
          </button>
        </div>
      )}
    </section>
  )
}
