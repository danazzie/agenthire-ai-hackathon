# HireMind web

The screen for a deal: the buyer's agent on the left, the two jars in the middle, the seller's agent on the right, and one panel per step below. It shows only what the control plane reports, and the control plane shows only what PayPal has confirmed.

```sh
npm install
npm run dev        # http://localhost:5199, proxies /api and /paypal to the control plane on :8088
npm test           # node --test on the pure model code
npm run build      # typecheck, then a static build in dist/
```

`VITE_API_URL` sets the control plane's address for a static build (for example, a Render static site). When it is unset, calls go to the same origin, which is what the dev proxy expects.

`/gallery` draws every jar state and every step from fixtures, so the layout can be checked without a live deal. It is a development aid; its deals are fixtures, not API data.
