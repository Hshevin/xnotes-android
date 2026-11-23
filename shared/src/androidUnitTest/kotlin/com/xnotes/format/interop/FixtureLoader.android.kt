package com.xnotes.format.interop

actual fun interopFixtureNames() = goldenHex.keys.toList()
actual fun interopFixture(name: String) = goldenHex.getValue(name).hexToByteArray()
