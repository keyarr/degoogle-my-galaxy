package dev.degoogle.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class KnownGoodDatabaseDocument(
    val schemaVersion: Int = 1,
    val devices: List<KnownGoodProfile> = emptyList(),
)

@Serializable
data class KnownGoodProfile(
    val id: String,
    val manufacturer: String,
    val model: String,
    val androidSdk: Int,
    val buildFingerprintPattern: String,
    val buildIdPattern: String? = null,
    val oneUiVersionPattern: String? = null,
    val securityPatchPattern: String? = null,
    val rootBackend: RootBackendType,
    val rebootStrategy: String,
    val firmwareValidated: Boolean = false,
    val status: String = "KNOWN_GOOD",
    val expectedPaths: Map<String, String> = emptyMap(),
)

enum class KnownGoodMatch {
    EXACT_MATCH,
    FIRMWARE_FAMILY_MATCH,
    MODEL_ONLY_MATCH,
    NO_MATCH,
}

data class KnownGoodMatchResult(
    val level: KnownGoodMatch,
    val profile: KnownGoodProfile? = null,
    val evidence: List<String> = emptyList(),
)

/** Banco local, revisável em Git; nenhuma atualização remota é feita aqui. */
class KnownGoodDatabase private constructor(
    private val document: KnownGoodDatabaseDocument,
) {
    fun match(facts: DeviceFacts, rootBackend: RootBackendType): KnownGoodMatchResult {
        val model = facts.model.orEmpty()
        val manufacturer = facts.manufacturer.orEmpty()
        val sdk = facts.sdk
        val candidates = document.devices.filter {
            it.manufacturer.equals(manufacturer, ignoreCase = true) &&
                it.model.equals(model, ignoreCase = true)
        }
        if (candidates.isEmpty()) return KnownGoodMatchResult(KnownGoodMatch.NO_MATCH)

        val exact = candidates.firstOrNull { profile ->
            profile.status.equals("KNOWN_GOOD", ignoreCase = true) &&
            sdk == profile.androidSdk &&
                rootBackend == profile.rootBackend &&
                regexMatches(profile.buildFingerprintPattern, facts.buildFingerprint) &&
                optionalMatches(profile.buildIdPattern, facts.buildId) &&
                optionalMatches(profile.oneUiVersionPattern, facts.oneUiVersion) &&
                optionalMatches(profile.securityPatchPattern, facts.securityPatch)
        }
        if (exact != null) {
            return KnownGoodMatchResult(
                level = KnownGoodMatch.EXACT_MATCH,
                profile = exact,
                evidence = listOf("modelo, SDK, fingerprint, root e metadados do firmware conferem"),
            )
        }

        val family = candidates.firstOrNull { profile ->
            sdk == profile.androidSdk &&
                rootBackend == profile.rootBackend &&
                facts.buildFingerprint != null
        }
        if (family != null) {
            return KnownGoodMatchResult(
                level = KnownGoodMatch.FIRMWARE_FAMILY_MATCH,
                profile = family,
                evidence = listOf("modelo, SDK e backend conferem; fingerprint não homologado"),
            )
        }

        return KnownGoodMatchResult(
            level = KnownGoodMatch.MODEL_ONLY_MATCH,
            profile = candidates.first(),
            evidence = listOf("somente modelo Samsung conhecido; isso não prova compatibilidade"),
        )
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun fromJson(json: String): KnownGoodDatabase =
            KnownGoodDatabase(JSON.decodeFromString<KnownGoodDatabaseDocument>(json))

        fun fallback(): KnownGoodDatabase = fromJson(
            """
            {"schemaVersion":1,"devices":[
              {"id":"samsung-sm-s928b-android16-kernelsu",
               "manufacturer":"samsung","model":"SM-S928B","androidSdk":36,
               "buildFingerprintPattern":"^samsung/e3qxxx/e3q:16/UP1A\\.231005\\.007/S928BXXU1AXK1:user/release-keys$",
               "buildIdPattern":"^UP1A\\.231005\\.007$",
               "rootBackend":"KERNELSU","rebootStrategy":"KSUD_SOFT_REBOOT",
               "firmwareValidated":true,"status":"KNOWN_GOOD",
               "expectedPaths":{"gms":"/product/priv-app/GmsCore","gsf":"/system_ext/priv-app/GoogleServicesFramework","store":"/product/priv-app/Phonesky"}}
            ]}
            """.trimIndent(),
        )

        private fun regexMatches(pattern: String?, value: String?): Boolean =
            !pattern.isNullOrBlank() && value != null && runCatching { Regex(pattern).matches(value) }.getOrDefault(false)

        private fun optionalMatches(pattern: String?, value: String?): Boolean =
            pattern.isNullOrBlank() || regexMatches(pattern, value)
    }
}
