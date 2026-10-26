import { STEPS, type Step } from '../model.ts'

/** 1 Post job → 2 Negotiate → … → 7 Appeal. The current step comes from the deal's state; Appeal is not built yet. */
export function StepPills({ current, shown, onShow }: { current: Step; shown: Step; onShow: (s: Step) => void }) {
  const at = STEPS.indexOf(current)
  return (
    <nav aria-label="Steps" className="flex flex-wrap items-center gap-1.5">
      {STEPS.map((step, i) => {
        const done = i < at
        const now = i === at
        const unbuilt = step === 'Appeal'
        const base = 'rounded-full border px-3 py-1 text-sm font-medium transition'
        const tone = now
          ? 'border-slate-900 bg-slate-900 text-white'
          : done
            ? 'border-emerald-600 bg-emerald-50 text-emerald-800'
            : 'border-slate-300 bg-white text-slate-500'
        return (
          <span key={step} className="flex items-center gap-1.5">
            <button
              type="button"
              disabled={unbuilt}
              onClick={() => onShow(step)}
              aria-current={now ? 'step' : undefined}
              title={unbuilt ? 'Appeal to PayPal Disputes is not built in this version' : undefined}
              className={`${base} ${tone} ${shown === step && !now ? 'ring-2 ring-slate-400' : ''} ${unbuilt ? 'cursor-not-allowed opacity-60' : 'cursor-pointer'}`}
            >
              {done ? '✓ ' : `${i + 1} `}
              {step}
            </button>
            {i < STEPS.length - 1 && <span className="text-slate-400" aria-hidden>→</span>}
          </span>
        )
      })}
    </nav>
  )
}
