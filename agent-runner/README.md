# Agent runner (Astropods)

The buyer's and the seller's Claude agents, run as their own service on [Astropods](https://docs.astropods.com). The control plane on Render keeps the deals and the money. The runner only negotiates and delivers.

```
Astropods: agent runner                          Render: control plane
  2 workers, each long-polling   ── claim ──▶    outbox: AGENT_TURN_BUYER / _SELLER / AGENT_DELIVER
  Negotiation / SellerAgent      ── deal, propose, accept, dry-run, deliver ──▶  AgentPort (as in process)
  (agents/, unchanged)           ── report ──▶   AgentOutcomes: queue the next turn, or flag the deal
```

**Why the runner calls in.** Astropods puts its own sign-in in front of every hosted agent, so the control plane cannot call the agents. The runner calls out instead, with a bearer token both sides share (`RUNNER_TOKEN`).

**What it cannot do.** It reaches only `/api/runner`, which mirrors `ProtocolPort`: read a deal, propose, accept, dry-run the verifier, deliver. Nothing there captures, voids, pays or confirms. The runner never holds a PayPal credential.

**If it dies mid-step.** A claim is a lease, the same one the outbox uses. If no report arrives within 2 minutes, the step is handed out again, and the offer log makes a repeated turn a no-op.

**Where drafting runs.** Turning the person's sentence into a job (screen 1) is a synchronous request. It still runs on the control plane, so the control plane keeps its own `ANTHROPIC_API_KEY`.

## Deploy

You need an Astropods account, the `ast` CLI and Docker. Install the CLI with the command from [docs.astropods.com/install-cli](https://docs.astropods.com/install-cli), then sign in:

```sh
ast login
```

1. **Pick a shared token.** For example, run `openssl rand -hex 32`. You will set the same value on both sides.

2. **On Render** (`hire-mind-api`, Environment), set these, then redeploy:
   - `RUNNER_TOKEN`: the token from step 1.
   - `AGENTS_REMOTE`: `true`.

   Until a runner is connected, agent jobs wait in the queue. `GET /api/status` shows `agentsHost` and `runnerSeenAt`.

3. **Store the runner's secrets on Astropods.** Each command prompts for the value and masks it:

   ```sh
   ast secrets create RUNNER_TOKEN
   ast secrets create ANTHROPIC_API_KEY
   ```

4. **Push and deploy from the repo root.** `ast` reads [astropods.yml](../astropods.yml) and builds [Dockerfile](Dockerfile) with the repo root as its context.

   ```sh
   ast spec validate
   ast blueprint push
   ast blueprint deploy hiremind-agents --name "HireMind agents" --wait \
     --var CONTROL_PLANE_URL=https://mutual-stake-api.onrender.com \
     --var RUNNER_TOKEN=@ --var ANTHROPIC_API_KEY=@ \
     --var AGENT_TIMEZONE=Europe/Istanbul
   ```

5. **Check it.** `ast agent list` shows the agent's hostname. Its page, behind Astropods sign-in, should say **Connected** within about 20 seconds, and the control plane's `/api/status` should show a fresh `runnerSeenAt`. Start a job on the HireMind screen, and each turn appears on the runner's page and in `ast agent logs`.

To go back to in-process agents, set `AGENTS_REMOTE` to `false` on Render. Steps still queued then run on the control plane.

## Settings

| Variable | Required | Meaning |
|---|---|---|
| `CONTROL_PLANE_URL` | yes | The control plane, e.g. `https://mutual-stake-api.onrender.com` |
| `RUNNER_TOKEN` | yes | Same value as on the control plane |
| `ANTHROPIC_API_KEY` | yes | Claude for both agents |
| `AGENT_TIMEZONE` | no | Where "by 18:00" is read; default UTC |
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

- `RemoteRunnerTest` (in control-plane) drives this runner against a running control plane over real HTTP, with Claude scripted. It runs the PROTOTYPES negotiation, funding, and the delivery that self-tests before submitting.
- `RunnerEndpointsTest` covers the token, the lease, refusals and the reports.
