# Cerebrum audit — what is missing

Measured 2026-11-22 on `lux/cerebrum-remediation` at `121b53b`. Every line here comes from
reading or counting, not from memory.

## 1. Six crates cannot be reached from the app at all

Of 24 crates, 18 are reachable from `cerebrum-android` by dependency. These six are not, so
nothing GURU does can call them and no prompt can teach the model they exist:

`cerebrum-cli`, `cerebrum-cluster`, `cerebrum-http`, `cerebrum-mcp`, `cerebrum-node`,
`cerebrum-rl`.

Two of those are legitimately outside the app: the CLI and the MCP server are alternative
front doors for a desktop. The other four are not: a cluster, an HTTP API, a node bridge and
the reinforcement-learning policy are capabilities that were built and never joined up.

## 2. Two engines are constructed on every boot and never used

`CerebrumState` holds 20 engines. Counting references in `handler.rs`, where every frame is
dispatched:

- `storage` is referenced 0 times.
- `snapshots` is referenced 0 times.
- `query_classifier` is referenced 0 times, because the classifier is reached through
  `query_engine` instead.

`SnapshotManager` appears nowhere outside the state struct and its own crate. Durable
persistence goes through the encrypted vault in `persistence.rs`, which never mentions the
snapshot manager. So the snapshot layer is dead weight that allocates on boot and does
nothing.

## 3. The deepest retrieval tier returns a fabricated answer

`cerebrum-query`, tier 4. When the hot cache, the signature search and the graph traversal
all come back empty, `retrieve` calls `deterministic_fallback`, which builds a memory id by
copying the query text's bytes into 32 bytes and returning it as a result.

That id belongs to no memory. It is not a "no results" signal; it is an invented hit with a
confidence score attached (0.95 minus a latency penalty). Any caller that trusts the QUERY
frame's tier field is being told the brain found something in a tier that does not exist.

Comment in the source: "pgvector fallback — deterministic synthetic result for testing."

## 4. The event enricher writes a self-loop and a copied id, then reports success

`cerebrum-events`, `Enricher::enrich`. For every pending event it:

- splits the text on full stops and calls each fragment a "fact";
- pushes a graph edge from the memory to itself;
- sets `signature_bytes` to `event.memory_id.0`, the id copied into the signature slot.

`handle_consolidate` runs `enrich_all` on every pass and adds the count of *successful*
results to `events_enriched`, so a report saying "40 events enriched" means 40 self-loops
were written. The comment says the real signatures come from `cerebrum-signatures`, which
consolidation already has in hand and does not pass.

## 5. The reinforcement-learning crate is a scaffold, not a policy

`cerebrum-rl` describes itself as the loop structure for a policy that "will later" exist.
Its `decide` returns a hard-coded action from thresholds, its "training" nudges those
thresholds by fixed amounts, and its candidate generation perturbs the state by adding ten
tokens per variant. Its own header calls the tests smoke tests.

It is unreachable from the app, so it changes nothing today, but it is counted among the
crates as though it were a working subsystem.

## 6. Nothing in the app's memory surface can compress, embed or prefetch on demand

`cerebrum-token` (compression and reconstruction) and `cerebrum-prefetch` are reachable and
are used internally, but there is no frame for either, so no tool and no prompt can ask for
a compressed memory or a prefetch. The model can store, search, retrieve, list, traverse,
consolidate and read history. It cannot ask what is likely needed next.

## 7. The seccomp hardening filter refuses to install, correctly

Covered in the completion report: the assembled program is now shaped correctly and its
jumps land inside it, but every syscall constant is x86_64 numbering and aarch64 numbers the
same calls differently, so it cannot run on the arm64 phones this app ships to. This is an
acknowledged gap, not a silent one.

## What this means for the prompts

Writing prompt rows now would produce a model that can use six of the brain's faculties and
cannot tell that the other fourteen exist. Three of the gaps above are defects rather than
missing features: the fabricated tier-4 answer, the self-loop enrichment counted as success,
and two engines instantiated and never touched. Those should be fixed before a prompt tells
the model to trust what the brain reports.
