# TASKS.md — Release Week Task Board

GURU goes public end of this week. This is the live board. Update as we go.

---

## NOW — The Untangle

- [x] Strip user accounts and login flow from the app
- [x] Remove the login gate
- [x] Remove account state
- [ ] Replace login with bootstrap model connect (one-time first launch)
- [x] Verify the app runs fully with no account anywhere

## NEXT — Ecosystem Wiring

- [x] Prompt section sync against the open server
- [ ] Skills sync (SKILL.md bodies pulled and installed on-device)
- [ ] Tool definitions sync (downloaded and registered on-device)
- [ ] Agent mask templates sync
- [ ] Canvas packs sync
- [ ] Config defaults sync
- [ ] Keyword-driven memory injection running fully on-device (Jaro-Winkler trigger matching, two-layer retrieval)
- [x] Community submissions flow into review pipeline (tools, skills, agents, packs)

## THEN — Open Server Live

- [ ] Publisher server up for public sync (source stays private — see Roadmap reasoning; protocol is documented and open to any compatible publisher)
- [ ] Public catalog live, browsable from outside the app
- [x] Catalog covers prompts, skills, tools, agent templates, canvas packs

## BEFORE CODE DROP

- [x] AGPL-3.0 licence file confirmed on app repo
- [x] Trademark notice on app repo
- [x] Legacy cruft swept from the codebase (33 cerebrum-dwm .bak/.checkpoint files, "gradlew 2", .DS_Store — swept 2026-09-21, .gitignore now blocks their return)
- [x] Build guide (BUILD.md) reviewed and committed
- [ ] Security policy (SECURITY.md) reviewed and committed
- [x] README cross-checked against final app state

## SHIP IT

- [ ] Flip repo to public
- [ ] Push source
- [ ] Sideloaded APK attached to release
- [ ] Release notes say plainly: public beta, first users are beta testers

## LAUNCH DAY SUPPORT

- [ ] Watch issues tab and triage day one
- [ ] Answer build issues as they land (BUILD.md living doc, update as people hit snags)
- [ ] Acknowledge first security reports within 72h per SECURITY.md
- [ ] Post honest "state of the launch" summary in Discussions or a pinned issue

## AFTER — Ongoing, Forever

- [ ] Hardening audit: permission boundaries
- [ ] Hardening audit: encryption
- [ ] Hardening audit: Tor routing
- [ ] Bug-fixing loop with community
- [ ] Community review pipeline humming (human-approved before catalog)
- [ ] Marketplace design (later, free core never changes)