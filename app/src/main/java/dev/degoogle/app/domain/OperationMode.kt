package dev.degoogle.app.domain

import kotlinx.serialization.Serializable

@Serializable
enum class OperationMode {
    PURE,
    MICROG,
}
