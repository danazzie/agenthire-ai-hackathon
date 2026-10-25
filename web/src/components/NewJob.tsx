import { useState } from 'react'
import { api, ApiError, type JobDraft } from '../api.ts'
import { money, shortHash } from '../model.ts'
import { scriptedDeal, US1_SENTENCE } from '../scripted.ts'

/** Screen 1 (US-1): one sentence becomes a cap, a deadline and a compiled test. Nothing touches PayPal until confirmed. */
export function NewJob({ agents, open }: { agents: boolean; open: (id: string) => void }) {
  const [sentence, setSentence] = useState(US1_SENTENCE)
  const [draft, setDraft] = useState<JobDraft | null>(null)
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
    <section className="flex flex-col gap-3 rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
      <h2 className="text-lg font-semibold">1 Post a job</h2>
      <label className="text-sm text-slate-600" htmlFor="sentence">Describe the job in one sentence: what, the most you'll pay, when, and how "done" is checked.</label>
      <div className="flex flex-wrap gap-2">
        <input
          id="sentence"
          value={sentence}
          onChange={(e) => { setSentence(e.target.value); setDraft(null) }}
          className="min-w-72 flex-1 rounded-lg border border-slate-300 px-3 py-2 text-base"
        />
        <button type="button" disabled={busy || !agents} onClick={() => run(async () => setDraft(await api.draftJob(sentence)))}
          title={agents ? undefined : 'The Claude agents are off: ANTHROPIC_API_KEY is not set'}
          className="rounded-lg bg-slate-900 px-3 py-2 text-sm font-medium text-white disabled:opacity-40">
          Draft with the buyer's agent
        </button>
        <button type="button" disabled={busy} onClick={() => run(async () => open((await scriptedDeal()).id))}
          className="rounded-lg border border-slate-300 px-3 py-2 text-sm font-medium disabled:opacity-40">
          Scripted job (no Claude)
        </button>
      </div>
      {!agents && <p className="text-sm text-slate-500">The Claude agents are off (no ANTHROPIC_API_KEY). The scripted job runs the same PROTOTYPES negotiation through the same API.</p>}
      {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
      {draft && (
        <div className="flex flex-col gap-2 rounded-lg bg-slate-50 p-3">
          <dl className="grid gap-2 text-sm sm:grid-cols-4">
            <div><dt className="text-xs uppercase text-slate-500">Job</dt><dd>{draft.jobText}</dd></div>
            <div><dt className="text-xs uppercase text-slate-500">Price cap</dt><dd>{money(draft.feeCapMinor)}</dd></div>
            <div><dt className="text-xs uppercase text-slate-500">Deadline</dt><dd>{new Date(draft.deadlineAt).toLocaleString([], { weekday: 'short', hour: '2-digit', minute: '2-digit' })}</dd></div>
            <div><dt className="text-xs uppercase text-slate-500">Seller</dt><dd>another agent</dd></div>
          </dl>
          <p className="text-sm">
            <span className="text-xs uppercase text-slate-500">The test </span>
            <span className="font-mono">{draft.verifier.spec}</span> <span className="font-mono text-slate-500">{shortHash(draft.verifier.sha256)}</span>
          </p>
          <details className="text-sm">
            <summary className="cursor-pointer">The compiled verifier (the code that will decide)</summary>
            <pre className="mt-2 max-h-72 overflow-auto rounded-lg bg-slate-950 p-3 text-xs text-slate-100">{draft.verifier.source}</pre>
          </details>
          <button type="button" disabled={busy} onClick={() => run(async () => open((await api.startJob(draft)).id))}
            className="self-start rounded-lg bg-emerald-700 px-3 py-2 text-sm font-semibold text-white disabled:opacity-40">
            Confirm this test and let the agents negotiate
          </button>
        </div>
      )}
    </section>
  )
}
