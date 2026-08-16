package dev.degoogle.app.domain

import kotlinx.serialization.Serializable

/** Fatos de identificação; valores ausentes permanecem nulos. */
@Serializable
data class DeviceFacts(
    val manufacturer: String? = null,
    val model: String? = null,
    val device: String? = null,
    val product: String? = null,
    val board: String? = null,
    val hardware: String? = null,
    val androidRelease: String? = null,
    val sdk: Int? = null,
    val buildFingerprint: String? = null,
    val buildId: String? = null,
    val securityPatch: String? = null,
    val oneUiVersion: String? = null,
    val kernelVersion: String? = null,
    val abiList: List<String> = emptyList(),
) {
    companion object {
        fun fromSystemFacts(facts: SystemFacts): DeviceFacts = DeviceFacts(
            manufacturer = facts.manufacturer.nullIfBlank(),
            model = facts.model.nullIfBlank(),
            device = facts.device.nullIfBlank(),
            product = facts.product.nullIfBlank(),
            board = facts.board.nullIfBlank(),
            hardware = facts.hardware.nullIfBlank(),
            androidRelease = facts.androidRelease.nullIfBlank(),
            sdk = facts.androidSdk.toIntOrNull(),
            buildFingerprint = facts.fingerprint.nullIfBlank(),
            buildId = facts.buildId.nullIfBlank(),
            securityPatch = facts.securityPatch.nullIfBlank(),
            oneUiVersion = facts.oneUiVersion.nullIfBlank(),
            kernelVersion = facts.kernelVersion.nullIfBlank(),
            abiList = facts.abiList.ifEmpty { facts.abi.split(',').map(String::trim).filter(String::isNotBlank) },
        )

        fun fromProperties(properties: Map<String, String>, kernelVersion: String? = null): DeviceFacts =
            DeviceFacts(
                manufacturer = properties["ro.product.manufacturer"].nullIfBlank(),
                model = properties["ro.product.model"].nullIfBlank(),
                device = properties["ro.product.device"].nullIfBlank(),
                product = properties["ro.product.name"].nullIfBlank(),
                board = properties["ro.product.board"].nullIfBlank(),
                hardware = properties["ro.hardware"].nullIfBlank(),
                androidRelease = properties["ro.build.version.release"].nullIfBlank(),
                sdk = properties["ro.build.version.sdk"]?.toIntOrNull(),
                buildFingerprint = properties["ro.build.fingerprint"].nullIfBlank(),
                buildId = properties["ro.build.id"].nullIfBlank(),
                securityPatch = properties["ro.build.version.security_patch"].nullIfBlank(),
                oneUiVersion = (
                    properties["ro.build.version.oneui"]
                        ?: properties["ro.build.version.sem"]
                        ?: properties["ro.build.PDA"]
                    ).nullIfBlank(),
                kernelVersion = kernelVersion.nullIfBlank(),
                abiList = (
                    properties["ro.product.cpu.abilist"]
                        ?: properties["ro.product.cpu.abi"]
                    ).orEmpty().split(',').map(String::trim).filter(String::isNotBlank),
            )
    }
}

private fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() }
