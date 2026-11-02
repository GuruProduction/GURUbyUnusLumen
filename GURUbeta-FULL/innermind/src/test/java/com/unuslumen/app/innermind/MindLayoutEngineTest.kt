// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.innermind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * The layout is the whole of this room's arithmetic, and it is the part a person
 * cannot check by looking: a map that rearranges itself every time it opens still
 * looks fine in a screenshot and is useless in the hand. These tests pin the
 * properties that make it a map rather than a random scatter.
 */
class MindLayoutEngineTest {

    private fun node(
        id: String,
        family: MindFamily = MindFamily.MEMORY,
        kind: NodeKind = NodeKind.ITEM,
        weight: Float = 0.5f,
        label: String = id,
    ) = MindNode(
        id = id,
        family = family,
        label = label,
        body = label,
        weight = weight,
        updatedAt = 0L,
        kind = kind,
    )

    private fun graphOf(vararg families: MindFamily, perFamily: Int = 4): MindGraph {
        val nodes = ArrayList<MindNode>()
        val edges = ArrayList<MindEdge>()
        nodes.add(node(MindSource.CORE_ID, kind = NodeKind.CORE))
        families.forEach { family ->
            nodes.add(node("hub:${family.name}", family, NodeKind.HUB, label = family.label))
            edges.add(MindEdge(MindSource.CORE_ID, "hub:${family.name}", EdgeKind.SPOKE))
            repeat(perFamily) { index ->
                val id = "${family.name.lowercase()}-$index"
                nodes.add(node(id, family, weight = index / perFamily.toFloat()))
                edges.add(MindEdge("hub:${family.name}", id, EdgeKind.SPOKE))
            }
        }
        return MindGraph(
            nodes = nodes,
            edges = edges,
            totalByFamily = families.associateWith { perFamily },
        )
    }

    @Test
    fun `the same graph always lays out the same way`() {
        val graph = graphOf(MindFamily.MEMORY, MindFamily.TASK, perFamily = 5)

        val first = MindLayoutEngine.layout(graph)
        val second = MindLayoutEngine.layout(graph)

        assertEquals(first.placed.size, second.placed.size)
        first.placed.zip(second.placed).forEach { (a, b) ->
            assertEquals("${a.node.id} moved between runs", a.position.x, b.position.x, 0.001f)
            assertEquals("${a.node.id} moved between runs", a.position.y, b.position.y, 0.001f)
        }
    }

    @Test
    fun `the centre is pinned and every other node stays off it`() {
        val layout = MindLayoutEngine.layout(
            graphOf(MindFamily.MEMORY, MindFamily.SESSION, MindFamily.TASK, perFamily = 6)
        )

        val centre = layout.byId.getValue(MindSource.CORE_ID)
        assertEquals(0f, centre.position.x, 0.001f)
        assertEquals(0f, centre.position.y, 0.001f)

        layout.placed
            .filter { it.node.id != MindSource.CORE_ID }
            .forEach { placed ->
                val distance = hypot(placed.position.x, placed.position.y)
                assertTrue(
                    "${placed.node.id} sat on the centre at $distance",
                    distance > MindLayoutEngine.CORE_RADIUS,
                )
            }
    }

    @Test
    fun `two nodes are never drawn on top of each other`() {
        val layout = MindLayoutEngine.layout(
            graphOf(MindFamily.MEMORY, MindFamily.AGENT, perFamily = 8)
        )

        val placed = layout.placed
        for (i in placed.indices) {
            for (j in i + 1 until placed.size) {
                val a = placed[i]
                val b = placed[j]
                val distance = hypot(
                    a.position.x - b.position.x,
                    a.position.y - b.position.y,
                )
                // Overlap is inevitable somewhere in a dense graph, but two nodes
                // sharing a centre is the failure that hides one completely.
                assertTrue(
                    "${a.node.id} and ${b.node.id} shared a centre",
                    distance > 1f,
                )
            }
        }
    }

    @Test
    fun `a hub is the nearest node to its own members`() {
        val layout = MindLayoutEngine.layout(
            graphOf(MindFamily.MEMORY, MindFamily.SKILL, perFamily = 6)
        )

        val hub = layout.byId.getValue("hub:MEMORY")
        val members = layout.placed.filter { it.node.family == MindFamily.MEMORY && it.node.kind == NodeKind.ITEM }
        assertTrue(members.isNotEmpty())

        members.forEach { member ->
            val toOwnHub = hypot(
                member.position.x - hub.position.x,
                member.position.y - hub.position.y,
            )
            val toCentre = hypot(member.position.x, member.position.y)
            assertTrue(
                "${member.node.id} was further from its own hub ($toOwnHub) than from the centre ($toCentre)",
                toOwnHub < toCentre + 40f,
            )
        }
    }

    @Test
    fun `a heavier node is drawn larger`() {
        val light = MindNode("light", MindFamily.MEMORY, "light", "light", weight = 0.05f, updatedAt = 0L)
        val heavy = MindNode("heavy", MindFamily.MEMORY, "heavy", "heavy", weight = 1f, updatedAt = 0L)

        assertTrue(
            MindLayoutEngine.itemRadius(heavy) > MindLayoutEngine.itemRadius(light),
        )
        // And neither end escapes its clamp, or one memory would swallow its family.
        assertTrue(MindLayoutEngine.itemRadius(light) >= 5.4f)
        assertTrue(MindLayoutEngine.itemRadius(heavy) <= 14.1f)
    }

    @Test
    fun `every family hub sits on the ring at its own bearing`() {
        val count = MindFamily.entries.size
        val layout = MindLayoutEngine.layout(graphOf(*MindFamily.entries.toTypedArray(), perFamily = 2))

        MindFamily.entries.forEach { family ->
            val hub = layout.byId.getValue("hub:${family.name}")
            val radius = hypot(hub.position.x, hub.position.y)
            assertEquals(
                "hub for $family was off the ring",
                MindLayoutEngine.HUB_RING,
                radius,
                0.001f,
            )
        }

        // Two families must never share a bearing, or their clusters merge and the
        // map stops distinguishing them. Compared with a tolerance rather than on
        // exact float equality: two hubs a tenth of a degree apart are the same
        // bearing to the eye, and the exact bits are an accident of trigonometry.
        val bearings = MindFamily.entries.map { family ->
            val hub = layout.byId.getValue("hub:${family.name}")
            Math.toDegrees(kotlin.math.atan2(hub.position.y.toDouble(), hub.position.x.toDouble()))
        }
        val expectedGap = 360.0 / count
        bearings.indices.forEach { index ->
            val here = bearings[index]
            val next = bearings[(index + 1) % bearings.size]
            val rawGap = Math.abs(here - next)
            val gap = minOf(rawGap, 360.0 - rawGap)
            assertTrue(
                "hubs at ${bearings[index]} and ${bearings[(index + 1) % bearings.size]} are only $gap apart",
                gap > expectedGap * 0.5,
            )
        }
    }

    @Test
    fun `bounds contain every node and are padded on all sides`() {
        val layout = MindLayoutEngine.layout(graphOf(MindFamily.MEMORY, MindFamily.DEVICE, perFamily = 5))
        val bounds = layout.bounds

        layout.placed.forEach { placed ->
            assertTrue(placed.position.x - placed.radius >= bounds.left - 0.01f)
            assertTrue(placed.position.x + placed.radius <= bounds.right + 0.01f)
            assertTrue(placed.position.y - placed.radius >= bounds.top - 0.01f)
            assertTrue(placed.position.y + placed.radius <= bounds.bottom + 0.01f)
        }

        val padded = bounds.padded(MindLayoutEngine.FIT_PADDING)
        assertTrue(padded.width > bounds.width)
        assertTrue(padded.height > bounds.height)
    }

    @Test
    fun `an empty graph lays out without throwing`() {
        val empty = MindGraph(emptyList(), emptyList(), emptyMap())
        val layout = MindLayoutEngine.layout(empty)
        assertTrue(layout.placed.isEmpty())
        assertEquals(0f, layout.bounds.width, 0.001f)
    }

    @Test
    fun `no two neighbouring families share a colour`() {
        // Ten families and eight house colours, so exactly two pairs must share.
        // What must never happen is a shared colour on two families that sit next
        // to each other on the ring: those two clusters touch, and one colour
        // across two touching clusters reads as a single family.
        val families = MindFamily.entries
        families.indices.forEach { index ->
            val here = families[index]
            val next = families[(index + 1) % families.size]
            assertNotEquals(
                "${here.name} and its neighbour ${next.name} share a colour",
                MindStyle.inkFor(here),
                MindStyle.inkFor(next),
            )
        }

        // And the shared pairs must be far apart, not merely non-neighbouring:
        // a duplicate two steps away is still the same shape to the eye.
        val byColour = families.groupBy { MindStyle.inkFor(it) }
        byColour.values.filter { it.size > 1 }.forEach { sharing ->
            val positions = sharing.map { it.ordinal }.sorted()
            val gap = positions.last() - positions.first()
            assertTrue(
                "${sharing.map { it.name }} share a colour only $gap places apart",
                gap >= 4,
            )
        }
    }
}
