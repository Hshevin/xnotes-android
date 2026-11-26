package com.xnotes.format

import kotlin.test.Test
import kotlin.test.assertEquals

class XfdfDecimalTest {
    @Test fun exportUsesAndroidDecimalRoundingIncludingTiesAndCarry() {
        for ((input, precision, expected) in listOf(Triple(2.675,2,"2.68"),Triple(-0.125,2,"-0.13"),
            Triple(0.0005,3,"0.001"),Triple(9.9995,3,"10.000"),Triple(-0.0,2,"-0.00"),
            Triple(1e-8,3,"0.000"),Triple(1e20,2,"100000000000000000000.00"))) {
            assertEquals(expected,fixedDecimal(input,precision))
        }
    }
}
