package dev.degoogle.app.microg

/**
 * Single source of truth for the modules that make up microG.
 * GmsCore + Companion (FakeStore, package com.android.vending) always go together:
 * same source, same download, same installation through prepare.
 */
object MicrogPackages {
    const val GMS = "com.google.android.gms"
    const val VENDING = "com.android.vending"

    val all: List<String> = listOf(GMS, VENDING)
}
