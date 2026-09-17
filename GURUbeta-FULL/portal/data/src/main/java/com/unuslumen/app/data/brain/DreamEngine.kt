package com.unuslumen.app.data.brain

import com.unuslumen.app.database.dao.MemoryFactDao
import com.unuslumen.app.database.dao.MemoryCrossReferenceDao
import com.unuslumen.app.database.entity.MemoryCrossReferenceEntity
import com.unuslumen.app.database.entity.MemoryFactEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DreamEngine — Sleep consolidation.
 *
 * Merges duplicates using signature bucketing to avoid O(n²) comparisons.
 * Facts are grouped by their first 16 bits of signature, so only facts in
 * the same bucket are compared. This reduces comparisons from n² to roughly
 * n * (bucket_size), which is near-linear for well-distributed signatures.
 *
 * Ported from Cerebrum's dream crate to Kotlin.
 */
class DreamEngine(
    private val memoryFactDao: MemoryFactDao,
    private val crossReferenceDao: MemoryCrossReferenceDao,
    private val graphEngine: GraphEngine,
    private val decayEngine: DecayEngine
) {

    /**
     * Run a full dream cycle.
     */
    suspend fun dream(currentTime: Long): DreamReport = withContext(Dispatchers.Default) {
        val report = DreamReport()

        // Phase 1: Apply decay
        report.prunedCount = decayEngine.applyDecayAll(currentTime)

        // Phase 2: Find and merge duplicates using signature bucketing
        val allFacts = memoryFactDao.getAllFacts()
        report.mergedCount = mergeDuplicatesBucketed(allFacts, currentTime)

        // Phase 3: Promote eligible facts
        report.promotedCount = promoteEligibleFacts(allFacts)

        // Phase 4: Build semantic edges for facts that have signatures
        val factsWithSigs = allFacts.filter { it.signature.isNotBlank() }
        for (fact in factsWithSigs.take(50)) {
            graphEngine.buildSemanticEdges(fact, factsWithSigs.filter { it.id != fact.id }, currentTime)
        }
        report.edgesBuilt = factsWithSigs.size.coerceAtMost(50)

        // Phase 5: Generate signatures for facts that don't have them
        val factsWithoutSigs = allFacts.filter { it.signature.isBlank() }
        for (fact in factsWithoutSigs.take(100)) {
            val sig = SignatureEngine.generateSignature(fact.fact)
            memoryFactDao.updateSignature(fact.id, sig)
        }
        report.signaturesGenerated = factsWithoutSigs.size.coerceAtMost(100)

        report
    }

    /**
     * Merge duplicates using signature bucketing.
     * Groups facts by first 16 bits of signature, only compares within buckets.
     * Also groups by category first since duplicates must share a category.
     */
    private suspend fun mergeDuplicatesBucketed(facts: List<MemoryFactEntity>, currentTime: Long): Int {
        var merged = 0
        val processed = mutableSetOf<String>()

        // Group by category first, then by signature bucket
        val byCategory = facts.filter { it.signature.isNotBlank() }.groupBy { it.category }

        for ((_, categoryFacts) in byCategory) {
            // Bucket by first 16 bits of signature
            val buckets = mutableMapOf<Int, MutableList<MemoryFactEntity>>()
            for (fact in categoryFacts) {
                val sigBytes = SignatureEngine.decodeSignature(fact.signature)
                val bucketKey = ((sigBytes[0].toInt() and 0xFF) shl 8) or (sigBytes[1].toInt() and 0xFF)
                buckets.getOrPut(bucketKey) { mutableListOf() }.add(fact)
            }

            // Only compare within the same bucket
            for ((_, bucketFacts) in buckets) {
                if (bucketFacts.size < 2) continue

                for (i in bucketFacts.indices) {
                    if (bucketFacts[i].id in processed) continue

                    val sigA = SignatureEngine.decodeSignature(bucketFacts[i].signature)

                    for (j in i + 1 until bucketFacts.size) {
                        if (bucketFacts[j].id in processed) continue

                        val sigB = SignatureEngine.decodeSignature(bucketFacts[j].signature)
                        val similarity = SignatureEngine.similarity(sigA, sigB)

                        if (similarity > 0.9f) {
                            val keep = if (bucketFacts[i].confidence >= bucketFacts[j].confidence) bucketFacts[i] else bucketFacts[j]
                            val discard = if (keep.id == bucketFacts[i].id) bucketFacts[j] else bucketFacts[i]

                            // Migrate cross-references to the kept fact
                            val refs = crossReferenceDao.getCrossReferencesFor(discard.id)
                            for (ref in refs) {
                                val otherId = if (ref.factIdA == discard.id) ref.factIdB else ref.factIdA
                                if (otherId != keep.id) {
                                    crossReferenceDao.insertCrossReference(
                                        MemoryCrossReferenceEntity(
                                            factIdA = keep.id,
                                            factIdB = otherId,
                                            refType = ref.refType,
                                            createdAt = currentTime
                                        )
                                    )
                                }
                            }

                            memoryFactDao.deleteFact(discard.id)
                            processed.add(discard.id)
                            merged++
                        }
                    }
                }
            }
        }

        return merged
    }

    private suspend fun promoteEligibleFacts(facts: List<MemoryFactEntity>): Int {
        var promoted = 0
        for (fact in facts) {
            val newLayer = decayEngine.checkPromotion(fact)
            if (newLayer != fact.layer) {
                memoryFactDao.promoteLayer(fact.id, newLayer)
                promoted++
            }
        }
        return promoted
    }
}

data class DreamReport(
    var prunedCount: Int = 0,
    var mergedCount: Int = 0,
    var promotedCount: Int = 0,
    var edgesBuilt: Int = 0,
    var signaturesGenerated: Int = 0
)
