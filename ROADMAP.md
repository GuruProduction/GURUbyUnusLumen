# GURU by Unus Lumen — Roadmap

GURU is free. Fully free and open source. There is no premium tier, no subscription, no tether. You bring your own model, an API key for a flagship model or a local LLM on your own hardware, and GURU belongs to everyone.

The full app source is coming as open source, end of September 2026. Below is the honest work between here and there, updated after the pivot to free.

## Why we changed course

This project spent 10 months pointed at a paid launch. Then the people who mattered, my dad and my oldest friends, told me plainly: they wouldn't pay for an app they don't understand. Meanwhile they use ChatGPT, Gemini and Grok every day. I sat with that for a week. They were right. I wouldn't pay for an app I don't understand either. And selling GURU would have made the privacy promise impossible, because taking money legally forces data collection.

So the product changes shape: GURU belongs to everyone. The fine-tuned commercial model launch is shelved, the funding chase is over, and the code is going public.

## The new architecture

- GURU the app: fully open source, GPL-3.0. Runs standalone. No accounts, no login, nothing to sign up for.
- Bring your own model: point GURU at a flagship model's API key, or a local LLM on your own hardware. No Unus Lumen dependency for inference. One bootstrap screen, once, on first launch.
- The open server: AGPL-3.0, a deliberately stateless publisher. Serves prompts, skills, tool definitions, agent templates, canvas packs and config defaults. No accounts, no conversation capture, nothing to leak. Run your own or point GURU at ours.
- Agents run both ways: GURU's mask system runs on-device as part of the hive mind, and the server catalog publishes mask templates the app pulls down and registers locally. Your GURU can create and persist its own agents locally, and register them back for community review.
- On-device memory stays local and stays private.
- Content you control: the app ships with a bundled core prompt pack, syncs from the server of your choosing, blankable, no hard tether.

## The run to release

**Now: the untangle.** Strip user accounts and login flow from the app. The app currently talks to our legacy API for auth, config and conversation sync, none of which survives the pivot. Remove the login gate, remove the account state, replace it all with the bootstrap model connect. This is where the real work is right now.

**Next: the ecosystem wiring.** Content sync against the new open server, the keyword-driven memory injection layer moving fully on-device, masks shipping in-app, tools and skills downloading and registering locally.

**Then: the open server goes live** for public sync, and a public catalog goes up so anyone can browse everything published through the review pipeline.

**Before code drop: licence and cleanup pass.** GPL-3.0 licence lands on the app repo, trademark notice goes in, legacy cruft gets swept, the server directory gets stripped from the public tree.

**Then: ship it.** The repo goes public. Sideloaded APK, no app store, no gatekeepers, every region at once.

**Ongoing, forever: in public.** Bugs get fixed with the community. Tools, skills and masks flow through the community review pipeline, human-reviewed before they hit the public catalog. The ecosystem becomes the product. And when it works, a marketplace follows: creators selling what they make, Unus Lumen taking a small cut, the free core never changing.

## Security, still non-negotiable

Open sourcing shifts the audit model, it doesn't remove it. The permission boundaries, the on-device encryption, the Tor routing and the self-evolution safety model stay under review, and going public makes every one of them checkable by anyone. Community security research is welcome from day one.

## Get involved

- Bugs and builds: file issues right here on the repo.
- Security researchers: welcome, always. steven@unuslumen.com
- Everything else: hello@unuslumen.com
