package com.xnotes.core.text

class FlowPaginationTimeoutTest {
    @org.junit.Test(timeout = 5_000)
    fun aFormulaTallerThanThePageIsPlacedWhereItIsRatherThanBumpedOn() {
        FlowPaginationTest().aFormulaTallerThanThePageIsPlacedWhereItIsRatherThanBumpedOn()
    }

    @org.junit.Test(timeout = 5_000)
    fun anOversizedFormulaAfterTextTakesOneNewPageAndNoMore() {
        FlowPaginationTest().anOversizedFormulaAfterTextTakesOneNewPageAndNoMore()
    }

    @org.junit.Test(timeout = 5_000)
    fun textCarriesOnPastAnOverflowingFormula() {
        FlowPaginationTest().textCarriesOnPastAnOverflowingFormula()
    }

    @org.junit.Test(timeout = 5_000)
    fun aRunOfOversizedFormulasTakesOnePageEachAndTerminates() {
        FlowPaginationTest().aRunOfOversizedFormulasTakesOnePageEachAndTerminates()
    }

    @org.junit.Test(timeout = 5_000)
    fun aFormulaWiderThanThePageOverflowsRatherThanDisappearing() {
        FlowPaginationTest().aFormulaWiderThanThePageOverflowsRatherThanDisappearing()
    }

    @org.junit.Test(timeout = 5_000)
    fun anOversizedFormulaAcrossNarrowingPagesStillTerminates() {
        FlowPaginationTest().anOversizedFormulaAcrossNarrowingPagesStillTerminates()
    }

}
