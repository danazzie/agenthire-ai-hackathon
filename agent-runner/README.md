# Agent runner (Astropods)

The buyer's and the seller's Claude agents, run as their own service on [Astropods](https://docs.astropods.com). The control plane on Render keeps the deals and the money. The runner does all of the Claude work: it drafts the job from the person's sentence, negotiates, and delivers.

```
Astropods: agent runner                          Render: control plane
  2 workers, each long-polling   ── claim ──▶    drafts waiting (DraftRelay), then the outbox:
                                                   AGENT_TURN_BUYER / _SELLER / AGENT_DELIVER
  BuyerAgent.draftJob            ── report ──▶   the screen's draft request, still waiting
  Negotiation / SellerAgent      ── deal, propose, accept, dry-run, deliver ──▶  AgentPort (as in process)
  (agents/, unchanged)           ── report ──▶   AgentOutcomes: queue the next turn, or flag the deal
```

**Why the runner calls in.** Astropods puts its own sign-in in front of every hosted agent, so the control plane cannot call the agents. The runner calls out instead, with a bearer token both sides share (`RUNNER_TOKEN`).

**What it cannot do.** It reaches only `/api/runner`, which mirrors `ProtocolPort`: read a deal, propose, accept, dry-run the verifier, deliver. Nothing there captures, voids, pays or confirms. The runner never holds a PayPal credential.

**If it dies mid-step.** A claim is a lease, the same one the outbox uses. If no report arrives within 2 minutes, the step is handed out again, and the offer log makes a repeated turn a no-op.

**Drafting.** Turning the person's sentence into a job (screen 1) is a request the screen waits on, not a deal, so it is not in the outbox. The control plane holds it in memory (`DraftRelay`), hands it to the next claim, and waits up to 100 seconds for the report. The draft carries the control plane's clock and `AGENT_TIMEZONE`, so "by 18:00" means the same as it would in process. The control plane still compiles the test. A runner asks for drafts with `claim?drafts=true`; one built before this change never asks, so it is never handed a draft it cannot read. If no runner that drafts has asked for work in the last 2 minutes, the draft is refused at once instead of waiting: `runner_not_connected`, or `runner_cannot_draft` when only an older runner is there.

**Upgrading a runner from before drafting moved.** Redeploy the runner first (`ast blueprint push`, then deploy), then merge the control plane. The new runner works with the old control plane, which ignores `drafts=true`. In the other order, drafting answers `runner_cannot_draft` until the runner is redeployed; negotiation and delivery keep working.

**The control plane's Claude key.** With `AGENTS_REMOTE` on, the control plane makes no Claude calls, so it needs no `ANTHROPIC_API_KEY`. Keep it set anyway while you might switch back: going back to in-process agents needs it.

## Deploy

You need an Astropods account, the `ast` CLI and Docker. Install the CLI with the command from [docs.astropods.com/install-cli](https://docs.astropods.com/install-cli), then sign in:

```sh
ast login
```

The order matters: turn on remote mode last, so no job waits for a runner that is not there yet.

1. **Deploy the control plane with the runner endpoints.** Merge to `main`; Render redeploys `hire-mind-api`. Agents still run in process.

2. **Pick a shared token and set it on Render.** For example, run `openssl rand -hex 32`. On `hire-mind-api`, under Environment, set `RUNNER_TOKEN` to it. This is harmless while `AGENTS_REMOTE` is off.

3. **Store the runner's secrets on Astropods.** Each command prompts for the value and masks it:

   ```sh
   ast secrets create RUNNER_TOKEN
   ast secrets create ANTHROPIC_API_KEY
   ```

4. **Push and deploy from the repo root.** `ast` reads [astropods.yml](../astropods.yml) and builds [Dockerfile](Dockerfile) with the repo root as its context. `ast blueprint push` refuses a dirty working tree; commit first, or add `--allow-dirty`. Set `AGENT_TIMEZONE` to the same value as on Render.

   ```sh
   ast spec validate
   ast blueprint push
   ast blueprint deploy hiremind-agents --name "HireMind agents" --wait \
     --var CONTROL_PLANE_URL=https://hire-mind-api.onrender.com \
     --var RUNNER_TOKEN=@ --var ANTHROPIC_API_KEY=@ \
     --var AGENT_TIMEZONE=<same as on Render>
   ```

   Until step 5, the runner's page shows `agents_in_process` as its last error, and the runner backs off and keeps trying.

5. **Turn on remote mode.** On `hire-mind-api`, set `AGENTS_REMOTE` to `true`. Render redeploys the API.

6. **Check it.** `ast agent list` shows the agent's hostname. Its page, behind Astropods sign-in, should say **Connected** within about 20 seconds. The control plane's [`/api/status`](https://hire-mind-api.onrender.com/api/status) should show `"agentsHost":"astropods"` and a fresh `runnerSeenAt`. Draft a job on the HireMind screen; the draft and each turn appear on the runner's page and in `ast agent logs`.

To go back to in-process agents, set `AGENTS_REMOTE` to `false` on Render. Steps still queued then run on the control plane.

## Settings

| Variable | Required | Meaning |
|---|---|---|
| `CONTROL_PLANE_URL` | yes | The control plane, e.g. `https://hire-mind-api.onrender.com` |
| `RUNNER_TOKEN` | yes | Same value as on the control plane |
| `ANTHROPIC_API_KEY` | yes | Claude for both agents |
| `AGENT_TIMEZONE` | no | Where "by 18:00" is read if the control plane does not say; drafts carry the control plane's zone. Default UTC |
| `RUNNER_WORKERS` | no | Parallel workers; default 2 |
| `PORT` | no | Status page port; default 80, which is what Astropods routes to |

If a required setting is missing, the runner still serves its page and names the missing setting, rather than crash-looping.

## Run it locally

```sh
# The control plane, in remote mode (Postgres from docker compose, as in the main README)
AGENTS_REMOTE=true RUNNER_TOKEN=local ./gradlew :control-plane:bootRun

# The runner, from source...
CONTROL_PLANE_URL=http://localhost:8088 RUNNER_TOKEN=local ANTHROPIC_API_KEY=... PORT=8090 ./gradlew :agent-runner:run
# ...or as Astropods builds it
docker build -f agent-runner/Dockerfile -t hiremind-agents .
docker run --rm -p 8090:80 -e CONTROL_PLANE_URL=http://host.docker.internal:8088 -e RUNNER_TOKEN=local -e ANTHROPIC_API_KEY=... hiremind-agents
```

The status page is then at http://localhost:8090.

## Tests

- `RemoteRunnerTest` (in control-plane) drives this runner against a running control plane over real HTTP, with Claude scripted. It drafts the job (in the control plane's zone, not the runner's), then runs the PROTOTYPES negotiation, funding, and the delivery that self-tests before submitting.
- `RunnerEndpointsTest` covers the token, the lease, refusals and the reports, and drafts: the claim, the report, a rejection, a runner that does not take drafts, and a runner that is not connected.
