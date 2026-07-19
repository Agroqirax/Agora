package com.newoether.agora.ui.settings

import org.junit.Assert.*
import org.junit.Test

class CollapsibleTrustListTest {

    @Test
    fun fewerThanThreshold_showsEverythingWithNoHiddenCount() {
        val (visible, hidden) = collapsibleTrustList(listOf("a", "b"), expanded = false)
        assertEquals(listOf("a", "b"), visible)
        assertEquals(0, hidden)
    }

    @Test
    fun exactlyAtThreshold_showsEverythingWithNoHiddenCount() {
        val (visible, hidden) = collapsibleTrustList(listOf("a", "b", "c"), expanded = false)
        assertEquals(listOf("a", "b", "c"), visible)
        assertEquals(0, hidden)
    }

    @Test
    fun moreThanThreshold_collapsedByDefault_hidesTheRest() {
        val (visible, hidden) = collapsibleTrustList(listOf("a", "b", "c", "d", "e"), expanded = false)
        assertEquals(listOf("a", "b", "c"), visible)
        assertEquals(2, hidden)
    }

    @Test
    fun moreThanThreshold_expanded_showsEverything() {
        val (visible, hidden) = collapsibleTrustList(listOf("a", "b", "c", "d", "e"), expanded = true)
        assertEquals(listOf("a", "b", "c", "d", "e"), visible)
        assertEquals(0, hidden)
    }

    @Test
    fun customThreshold_isRespected() {
        val (visible, hidden) = collapsibleTrustList(listOf("a", "b", "c", "d"), expanded = false, collapseThreshold = 1)
        assertEquals(listOf("a"), visible)
        assertEquals(3, hidden)
    }

    @Test
    fun zeroThreshold_collapsesEvenASingleEntry() {
        val (visible, hidden) = collapsibleTrustList(listOf("a"), expanded = false, collapseThreshold = 0)
        assertEquals(emptyList<String>(), visible)
        assertEquals(1, hidden)
    }
}
