# GURU by Unus Lumen — Roadmap

GURU is free. Fully free and open source. There is no premium tier, no subscription, no tether. You bring your own model, an API key for a flagship model or a local LLM on your own hardware, and GURU belongs to everyone.

The full app source is coming as open source, end of this week. Below is the honest work between here and there, updated after the pivot to free.

---

## Why we changed course

This project spent 10 months pointed at a paid launch. Then the people who mattered, my dad and my oldest friends, told me plainly: they wouldn't pay for an app they don't understand. Meanwhile they use ChatGPT, Gemini and Grok every day. I sat with that for a week. They were right. I wouldn't pay for an app I don't understand either. And selling GURU would have made the privacy promise impossible, because taking money legally forces data collection.

So the product changes shape: GURU belongs to everyone. The code is going public.

---

## The new architecture

- **GURU the app** — fully open source, AGPL-3.0. Runs standalone. No accounts, no login, nothing to sign up for.
- **Bring your own model** — point GURU at a flagship model's API key, or a local LLM on your own hardware. No Unus Lumen dependency for inference. One bootstrap screen, once, on first launch.
- **The open server** — AGPL-3.0, a deliberately stateless publisher: [GURUbyUnusLumen-Server](https://github.com/GuruProduction/GURUbyUnusLumen-Server). Serves prompts, skills, tool definitions, agent templates, canvas packs and config defaults. No accounts, no conversation capture, nothing to leak. Run your own or point GURU at ours.
- **Agents run both ways** — GURU ships with its mask system running on-device (21 specialists and counting: Director, Wingman, Archivist, Concierge, Mechanic, Code Engineer, Research Guru, Seer, Deep Thinker, Fixer, Architect, Project Manager, Foreigner, Forge, Doctor, Silk, Signal, Grafter, Vault, Sentinel, Self-Improvement Engine), and the server catalog publishes agent templates the app pulls and registers locally. User GURUs create and persist their own agents locally, and can register them back for community review.
- **On-device memory** — stays local and stays private. Keyword-driven memory injection happens on-device: GURU's recall layer hears trigger phrases in what you say and pulls the right memories into context.
- **Content you control** — the app ships with a bundled core prompt pack, syncs from the server of your choosing, blankable, no hard tether.

---

## The run to release

**Now: the untangle.** Strip user accounts and login flow from the app. Remove the login gate, remove the account state, replace it with the bootstrap model connect.

**Next: the ecosystem wiring.** Content sync against the new open server, the keyword-driven memory injection layer fully on-device, tools and skills downloading and registering locally.

**Then: the open server goes live** for public sync, and a public catalog goes up so anyone can browse everything published through the review pipeline.

**Before code drop: licence and cleanup pass.** The AGPL-3.0 licence and trademark notice are already on the app repo; legacy cruft gets swept before the flip to public.

**Then: ship it.** The repo goes public. Sideloaded APK, no app store, no gatekeepers, every region at once.

**Ongoing, forever: in public.** Bugs get fixed with the community. Tools, skills, masks and agent templates flow through the community review pipeline, human-reviewed before they hit the public catalog. And when it works, a marketplace follows: creators selling what they make, the free core never changing.

---

## Get involved

- **Bugs and builds:** file issues right here on the repo.
- **Security researchers:** welcome, always. steven@unuslumen.com
- **Everything else:** hello@unuslumen.com

---

**Unus Lumen** — Bristol, UK
