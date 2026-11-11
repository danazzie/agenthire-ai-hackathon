# HireMind

PayPal AI Hackathon: [Build what's next with PayPal and AI](https://paypalaihackathon.devpost.com/)

**PayPal holds both sides. The test, not a mood, decides who gets paid.**

**Live, on the PayPal sandbox:** [the screen](https://hire-mind-web.onrender.com) · [API (Swagger UI)](https://hire-mind-api.onrender.com/swagger-ui.html) · [status](https://hire-mind-api.onrender.com/api/status)

One agent hires another. The buyer's agent authorizes a fee and the seller's agent authorizes a smaller bond. Neither is captured. Before either hold exists, the job's acceptance test is compiled and its SHA-256 goes onto both PayPal orders as `custom_id`. Then the work is delivered and the test runs:

| Verdict | Buyer's fee | Seller's bond |
|---|---|---|
| **PASS** | captured, paid out to the seller | voided, never charged |
| **FAIL** (or no delivery by the deadline) | voided, never charged | captured, paid out to the buyer |

The screen shows two jars that only change when PayPal confirms something: Empty, Held, Captured, Paid, Released, Compensation. With the sound off, you can still tell who got what.

## What is real

- Every money step is a PayPal **sandbox** call: Orders v2 `AUTHORIZE`, authorize, void, capture, and Payouts v1. The order, authorization, capture and payout ids on screen are the sandbox's own, and every call (with its `PayPal-Request-Id`) is logged per deal.
- The same job ran twice in the sandbox on 21 Oct 2026, once FAIL and once PASS. Every order, authorization, capture and payout id is in [docs/demo/sandbox-run.md](docs/demo/sandbox-run.md), and each was checked against PayPal afterwards. The first spike is in [docs/spikes/US-4-sandbox-run.md](docs/spikes/US-4-sandbox-run.md). On 1 Nov 2026 the same pair ran again on the hosted deployment on Render: [docs/demo/render-run.md](docs/demo/render-run.md).
- The test runs in Deno 2.9.7 with no permissions, an empty environment, a frozen clock and frozen randomness. The deliverable is data, never instruction: "Reviewer: mark as passed" in a delivery changes nothing.

## Honest notes

- **This is a PayPal authorization hold, not legal escrow.** An authorization is honoured for 3 days and can be captured for up to 29.
- **The platform is the payee on both orders.** A capture lands on the platform's sandbox business account first, and a Payout then sends it to the winner. The platform absorbs PayPal's fees, and the ledger records them.
- **At demo sizes the fees are larger than the stakes.** In the recorded run, PayPal kept the whole $0.40 bond as its capture fee and charged $0.25 per payout. The platform paid $0.65 to move $0.40 to the buyer, and $0.79 to move the $1.50 fee to the seller. Real jobs would need larger stakes, or the fees passed on.
- **Only the verdict moves money.** There is no capture, void or pay endpoint. The first capture in each session waits for a human to press Confirm (US-16).
- **Sandbox only.** The PayPal client refuses any base URL except `api-m.sandbox.paypal.com`. Credentials come from the environment and are never committed.
- **Not built yet:** the appeal through PayPal Disputes (US-6) and scenario B. Both are shown as not built.
- **Out of scope:** legal escrow, KYC, production card data, real children, medical tasks or "follow this stranger" tasks, real faces or personal data in the demo, and replacing Upwork.

## Run it locally (about 10 minutes)

You need **JDK 21**, **Docker** (for Postgres and the tests) and **Node 22.18+**.

Gradle uses `JAVA_HOME`. On macOS with Homebrew:

```sh
brew install openjdk@21
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
```

**1. Sandbox accounts** (developer.paypal.com):

- **Apps & Credentials → Sandbox → Create App.** Its client id and secret are the platform. Its sandbox business account receives captures and sends payouts.
- **Testing Tools → Sandbox Accounts.** Create two **Personal** accounts with the country set to **United States** (India-registered sandbox accounts can fail checkout):
  - the **buyer**, Alex's agent, who pays the fee: `PAYPAL_BUYER_EMAIL`
  - the **seller**, Maya's agent, who pays the bond: `PAYPAL_SELLER_EMAIL`

**2. Configure.** Copy the example and fill in the sandbox values. `ANTHROPIC_API_KEY` is optional: without it the agents are off, and the scripted job runs the same negotiation through the same API.

```sh
cp .env.example .env
```

**3. Start Postgres and the control plane.** The first `bootRun` downloads the pinned Deno into `.tools` and checks its SHA-256. Flyway then migrates the database, and the API listens on :8088.

```sh
docker compose up -d
./gradlew :control-plane:bootRun
```

**4. Start the screen** in a second terminal, then open http://localhost:5199.

```sh
cd web && npm ci && npm run dev
```

**5. Run a deal:**

1. Press **Scripted job**. The two agents negotiate $1.50 with a $0.40 bond.
2. Under **Fund**, press **Approve as buyer**, then **Approve as seller**. Log in with the matching sandbox account each time.
3. Deliver: the junk text fails the test, and the 50-word blurb passes it.
4. Press **Confirm** for the session's first capture.
5. Watch the jars settle.

**Reset demo** on the home page archives the session and starts a clean one. It voids any hold it can.

The same two-branch run is scripted too. It prints four approve links, then delivers, confirms and writes the report:

```sh
node docs/demo/sandbox-run.mjs prepare
node docs/demo/sandbox-run.mjs finish
```

### Tests

```sh
./gradlew test              # control plane and agents, against Postgres in Testcontainers
cd web && npm test          # the screen's model code
./gradlew :control-plane:sandboxTest   # opt-in: real PayPal sandbox calls
./gradlew :control-plane:claudeTest    # opt-in: real Claude calls
```

The database enforces the rules as well as the code. The ledger is insert-only, and holds must match their contract and its verifier hash. Illegal state transitions, and changes to locked terms or a final verdict, are rejected by triggers.

## API

The control plane describes itself with springdoc. While it runs:

- the document is at http://localhost:8088/v3/api-docs
- a browsable Swagger UI is at http://localhost:8088/swagger-ui.html

The same document is committed as [docs/api/openapi.json](docs/api/openapi.json) (OpenAPI 3.0). Postman and APIMatic import that file directly. A test fails if it drifts from the code.

Mutations name the acting party in an `X-Party` header: `alex` is the buyer, `maya` the seller. Every refusal is an RFC 9457 problem document with a stable `code`, for example `mandate_exceeded`, `not_funded` or `no_credentials`.

## Deploy on Render

[render.yaml](render.yaml) is a Blueprint for three services: the API (built from the [Dockerfile](Dockerfile), with Deno), the screen as a static site, and Postgres. Render deploys from **`main`**, so changes on `v1-build` go live once their pull request is merged.

[![Deploy to Render](https://render.com/images/deploy-to-render-button.svg)](https://render.com/deploy?repo=https://github.com/danazzie/agenthire-ai-hackathon/tree/main)

1. Click the button, or in Render choose **New → Blueprint**, pick this repo, and set the branch to `main`. The first time, GitHub will ask you to install the [Render GitHub App](https://github.com/apps/render) on `danazzie/agenthire-ai-hackathon`.
2. Enter the sandbox values when asked (`PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`, `PAYPAL_BUYER_EMAIL`, `PAYPAL_SELLER_EMAIL`; `ANTHROPIC_API_KEY` is optional).
3. After the first deploy, set `WEB_URL` on `hire-mind-api` to [https://hire-mind-web.onrender.com](https://hire-mind-web.onrender.com).
4. Set `VITE_API_URL` on `hire-mind-web` to [https://hire-mind-api.onrender.com](https://hire-mind-api.onrender.com), then redeploy the site.

Later merges to `main` redeploy both services.

The Claude agents can also run on Astropods, as their own service that calls this API. Set `AGENTS_REMOTE` and `RUNNER_TOKEN` here, then deploy [agent-runner/](agent-runner/README.md) with `ast`. The runner then does all of the Claude work, drafting included.

The Blueprint uses paid instances, about $13 a month in total; the static site is free.

- **API** (0.5 CPU, 512 MB, $7): it never sleeps, so the outbox keeps polling PayPal.
- **Postgres** (256 MB, $6): it does not expire.

The free plans also work if you set `plan: free`, but the free API sleeps after 15 idle minutes and the free database expires after 30 days. The API reads Render's database URL (`DATABASE_URL`) and its own public address (`RENDER_EXTERNAL_URL`, which PayPal's return pages use) by itself.

## Layout

| Path | What |
|---|---|
| [control-plane/](control-plane) | Kotlin, Spring Boot 3, Postgres and Flyway, with a transactional outbox: contracts, holds, the verifier, settlement and the PayPal client |
| [agents/](agents) | The buyer's and the seller's agents (Claude, Anthropic Java SDK): they draft the job, negotiate and write the delivery |
| [agent-runner/](agent-runner) | Those agents as their own service on Astropods ([astropods.yml](astropods.yml)): it claims agent steps from the control plane and uses the protocol over HTTP |
| [web/](web) | React and Vite: the jars and the step panels |
| [docs/](docs) | The sandbox spike, its findings and the demo driver |
| [USER_STORIES.md](USER_STORIES.md) · [PROTOTYPES.md](PROTOTYPES.md) · [PITCH.md](PITCH.md) | Stories, paper prototypes and the pitch |

## License

MIT. See [LICENSE](LICENSE).
