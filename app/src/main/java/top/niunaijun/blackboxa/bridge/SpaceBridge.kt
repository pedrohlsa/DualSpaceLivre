package top.niunaijun.blackboxa.bridge

import android.content.Intent
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore

/**
 * Ponte de controle externo, desacoplada da UI e do ciclo de vida.
 *
 * Abre um app já clonado (`package`) dentro de um espaço específico (`space_id`)
 * reaproveitando a engine — sem automação por toques/coordenadas. Pensada para o
 * Gerenciador de Contas (desktop) comandar via ADB, mas o contrato é genérico.
 *
 * Princípios:
 *  - **IDs internos, não nomes.** O espaço é identificado pelo `BUserInfo.id`
 *    persistente. A associação pessoa ↔ device ↔ spaceId vive no Gerenciador,
 *    nunca aqui — esta ponte só conhece ids internos.
 *  - **Superfície mínima.** Aceita só os dois parâmetros estritamente
 *    necessários e valida cada um; nada destrutivo (só abre um clone existente).
 *  - **Desacoplada.** Toda a lógica de validação/lançamento fica aqui, fora da
 *    Activity, para um futuro `MultiSpaceProvider` (sobre outro engine) reusar o
 *    mesmo contrato e as mesmas checagens.
 */
object SpaceBridge {
    const val ACTION_OPEN_IN_SPACE = "com.dualspace.livre.action.OPEN_IN_SPACE"
    const val EXTRA_SPACE_ID = "space_id"
    const val EXTRA_PACKAGE = "package"

    private const val TAG = "SpaceBridge"
    private const val INVALID_ID = Int.MIN_VALUE

    /** Resultado explícito para log/telemetria — nunca lança para fora. */
    enum class Result { OK, BAD_ACTION, MISSING_PARAMS, UNKNOWN_SPACE, NOT_INSTALLED, LAUNCH_FAILED }

    fun open(intent: Intent?): Result {
        if (intent == null || intent.action != ACTION_OPEN_IN_SPACE) return Result.BAD_ACTION

        val spaceId = intent.getIntExtra(EXTRA_SPACE_ID, INVALID_ID)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)?.trim().orEmpty()
        if (spaceId == INVALID_ID || spaceId < 0 || pkg.isEmpty()) return Result.MISSING_PARAMS

        val core = BlackBoxCore.get()

        val spaceExists = try {
            core.users.any { it.id == spaceId }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao listar espaços: ${e.message}")
            false
        }
        if (!spaceExists) return Result.UNKNOWN_SPACE

        if (!core.isInstalled(pkg, spaceId)) return Result.NOT_INSTALLED

        val launched = try {
            core.launchApk(pkg, spaceId)
        } catch (e: Exception) {
            Log.e(TAG, "launchApk falhou (space=$spaceId, pkg=$pkg): ${e.message}")
            false
        }
        return if (launched) Result.OK else Result.LAUNCH_FAILED
    }
}
