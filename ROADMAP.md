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
- **Bring your own model** — point GURU at a flagship model's API key, or a local LLM on your own hardware. No Unus Lumen dependency for model inference. One bootstrap screen, once, on first launch.
- **The open server** — AGPL-3.0, a deliberately stateless publisher: [GURUbyUnusLumen-Server](https://github.com/GuruProduction/GURUbyUnusLumen-Server). Serves prompts, skills, tool definitions, agent templates, canvas packs and config defaults. No accounts, no conversation capture, nothing to leak. Run your own or point GURU at ours.
- **Tools stay on device** — GURU's existing 500+ tool toolkit ships bundled in the app. New tools arrive through the API as download-and-register definitions: the code itself runs on the user's device, never on any server. A GURU can also build its own tools, and register its best ones back through the API for community review into the open toolkit.
- **Agents run both ways** — GURU ships with its mask system running on-device (21 specialists and counting: Director, Wingman, Archivist, Concierge, Mechanic, Code Engineer, Research Guru, Seer, Deep Thinker, Fixer, Architect, Project Manager, Foreigner, Forge, Doctor, Silk, Signal, Grafter, Vault, Sentinel, Self-Improvement Engine), and the server catalog publishes agent templates the app pulls and registers locally. User GURUs create and persist their own agents locally, and can register them back for community review.
- **On-device memory** — stays local and stays private. Keyword-driven memory injection happens on-device: GURU's recall layer hears trigger phrases in what you say (Jaro-Winkler fuzzy phrase matching, ported from the old server proxy) and pulls the right memories into context, two-layer retrieval running on the on-device database.
- **The loading space survives** — the interactive canvas that beams Lottie animations, videos, stickmen playing chess and minigames while GURU thinks stays in the app. The old advertising machinery around it dies; the canvas itself survives as canvas packs served from the API, community-submittable and marketplace-ready.
- **Content you control** — the app ships with a bundled core prompt pack, syncs from the server of your choosing, blankable, no hard tether.

---

## The run to release

**Now: the untangle.** Strip user accounts and login flow from the app. Remove the login gate, remove the account state, replace it with the bootstrap model connect.

**Next: the ecosystem wiring.** Content sync against the new open server for everything it serves: prompt sections, skills (SKILL.md bodies pulled and installed), tool definitions (downloaded and registered on-device), agent mask templates, canvas packs and config defaults. The keyword-driven memory injection layer fully on-device. Community submissions flow into the review pipeline: tools, skills, agents, packs.

**Then: the open server goes live** for public sync, and a public catalog goes up so anyone can browse everything published through the review pipeline: every prompt, every skill, every tool, every agent template, every canvas pack. Browsable by users and creators from outside the app, not just silent sync behind a screen.

**Before code drop: licence and cleanup pass.** The AGPL-3.0 licence and trademark notice are already on the app repo; legacy cruft gets swept before the flip to public.

**Then: ship it.** The repo goes public. Sideloaded APK, no app store, no gatekeepers, every region at once.

**Straight about the launch state: public beta, honest.** The source publishing end of this week has not been security hardened yet, the audit pass on the permission boundaries, encryption and Tor routing is still ahead, and there are bugs sitting in corners the author's own use case never touches. The first users are the beta testers. That is not a caveat buried in fine print, it is the deal stated plainly: everyone who installs from here forward is helping find what a 10-month solo build could not catch alone, and the community bug-fixing loop starts on day one.

**Ongoing, forever: in public.** Bugs get fixed with the community. Everything user GURUs build, tools, skills, masks, canvas packs, flows back through the review pipeline: human-reviewed before it hits the public catalog, nothing ships without a human approving it. The app pulls the best of it down and registers it on-device. The private superadmin keeps doing what only Unus Lumen should control: pushing master prompts and skills, holding config, running the review queue. And when it works, a marketplace follows: creators selling what they make, tools, skills, mask packs, canvas packs, with the free core never changing.

---

## Get involved

- **Bugs and builds:** file issues right here on the repo.
- **Security researchers:** welcome, always. steven@unuslumen.com
- **Everything else:** hello@unuslumen.com

---

**Unus Lumen** — Bristol, UK
