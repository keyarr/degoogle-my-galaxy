package dev.degoogle.app.domain

/**
 * Perfil de aparelho: caminhos de sistema esperados + restrições.
 *
 * A v1 suporta um único perfil (Samsung Galaxy S24 Ultra — SM-S928x), o mesmo
 * usado para desenvolver/testar o script original. Nenhum caminho é assumido
 * sem antes ser confirmado via `pm path`.
 */
data class DeviceProfile(
    val id: String,
    val manufacturer: String,
    val models: Set<String>,
    /** Caminho do diretório priv-app do Google Play Services stock. */
    val gmsSystemDir: String,
    /** Caminho do diretório priv-app do Google Services Framework stock. */
    val gsfSystemDir: String,
    /** Caminho do diretório priv-app da Play Store (Phonesky) stock. */
    val storeSystemDir: String,
    /** SDK mínimo exigido (null = qualquer). */
    val minSdk: Int? = null,
    /** Máscara do nome do APK dentro do diretório GMS. */
    val gmsApkName: String = "GmsCore.apk",
    /** Máscara do nome do APK dentro do diretório da loja. */
    val storeApkName: String = "Companion.apk",
)

object DeviceProfiles {

    val SUPPORTED: List<DeviceProfile> = listOf(
        DeviceProfile(
            id = "samsung_sm-s928b",
            manufacturer = "samsung",
            models = setOf("SM-S928B", "SM-S928U", "SM-S928W", "SM-S928N", "SM-S9280"),
            gmsSystemDir = "/product/priv-app/GmsCore",
            gsfSystemDir = "/system_ext/priv-app/GoogleServicesFramework",
            storeSystemDir = "/product/priv-app/Phonesky",
        ),
    )

    /** Encontra o primeiro perfil compatível com os fatos coletados. */
    fun matching(
        manufacturer: String,
        model: String,
        sdk: String,
    ): DeviceProfile? {
        val man = manufacturer.lowercase()
        return SUPPORTED.firstOrNull { profile ->
            profile.manufacturer == man &&
                profile.models.any { it.equals(model, ignoreCase = true) } &&
                (profile.minSdk == null || sdk.toIntOrNull()?.let { it >= profile.minSdk } == true)
        }
    }
}
