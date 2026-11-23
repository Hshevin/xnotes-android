package com.xnotes.core.text

class FlowPaginationTimeoutTest {
    @kotlin.test.Test
    fun aFormulaTallerThanThePageIsPlacedWhereItIsRatherThanBumpedOn() {
        val start = kotlin.time.TimeSource.Monotonic.markNow()
        FlowPaginationTest().aFormulaTallerThanThePageIsPlacedWhereItIsRatherThanBumpedOn()
        kotlin.test.assertTrue(start.elapsedNow() <= kotlin.time.Duration.parse("5s"))
    }

    @kotlin.test.Test
    fun anOversizedFormulaAfterTextTakesOneNewPageAndNoMore() {
        val start = kotlin.time.TimeSource.Monotonic.markNow()
        FlowPaginationTest().anOversizedFormulaAfterTextTakesOneNewPageAndNoMore()
        kotlin.test.assertTrue(start.elapsedNow() <= kotlin.time.Duration.parse("5s"))
    }

    @kotlin.test.Test
    fun textCarriesOnPastAnOverflowingFormula() {
        val start = kotlin.time.TimeSource.Monotonic.markNow()
        FlowPaginationTest().textCarriesOnPastAnOverflowingFormula()
        kotlin.test.assertTrue(start.elapsedNow() <= kotlin.time.Duration.parse("5s"))
    }

    @kotlin.test.Test
    fun aRunOfOversizedFormulasTakesOnePageEachAndTerminates() {
        val start = kotlin.time.TimeSource.Monotonic.markNow()
        FlowPaginationTest().aRunOfOversizedFormulasTakesOnePageEachAndTerminates()
        kotlin.test.assertTrue(start.elapsedNow() <= kotlin.time.Duration.parse("5s"))
    }

    @kotlin.test.Test
    fun aFormulaWiderThanThePageOverflowsRatherThanDisappearing() {
        val start = kotlin.time.TimeSource.Monotonic.markNow()
        FlowPaginationTest().aFormulaWiderThanThePageOverflowsRatherThanDisappearing()
        kotlin.test.assertTrue(start.elapsedNow() <= kotlin.time.Duration.parse("5s"))
    }

    @kotlin.test.Test
    fun anOversizedFormulaAcrossNarrowingPagesStillTerminates() {
        val start = kotlin.time.TimeSource.Monotonic.markNow()
        FlowPaginationTest().anOversizedFormulaAcrossNarrowingPagesStillTerminates()
        kotlin.test.assertTrue(start.elapsedNow() <= kotlin.time.Duration.parse("5s"))
    }

}
