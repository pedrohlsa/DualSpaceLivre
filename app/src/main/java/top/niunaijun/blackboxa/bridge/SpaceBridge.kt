package top.niunaijun.blackboxa.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
    const val ACTION_SET_CLIPBOARD = "com.dualspace.livre.action.SET_CLIPBOARD"
    const val EXTRA_SPACE_ID = "space_id"
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_TEXT = "text"
    /** Eco opcional: o solicitante casa o resultado no logcat com este valor. */
    const val EXTRA_NONCE = "nonce"

    private const val TAG = "SpaceBridge"
    private const val INVALID_ID = Int.MIN_VALUE

    /** Resultado explícito para log/telemetria — nunca lança para fora. */
    enum class Result { OK, BAD_ACTION, MISSING_PARAMS, UNKNOWN_SPACE, NOT_INSTALLED, LAUNCH_FAILED, CLIPBOARD_FAILED }

    /**
     * Coloca um texto na área de transferência DESTE perfil Android.
     *
     * O Android mantém um clipboard por perfil. Um `adb shell` roda no perfil
     * principal, então o que se copia no PC nunca aparece para os clones, que
     * vivem no perfil de trabalho — medido: o hook de clipboard da engine é
     * instalado nos processos do clone e **nenhuma** leitura passa por ele,
     * porque não há o que ler. Como este app roda no mesmo perfil dos clones,
     * escrever daqui torna o texto visível para eles.
     *
     * Exige foreground: a partir do Android 10 só o app em foco pode escrever no
     * clipboard, e é por isso que quem chama é a Activity-ponte.
     */
    fun setClipboard(context: Context, intent: Intent?): Result {
        if (intent == null || intent.action != ACTION_SET_CLIPBOARD) return Result.BAD_ACTION

        val text = intent.getStringExtra(EXTRA_TEXT) ?: return Result.MISSING_PARAMS

        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Dual Space", text))
            Log.i(TAG, "clipboard definido (${text.length} caracteres)")
            Result.OK
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao definir o clipboard: ${e.message}")
            Result.CLIPBOARD_FAILED
        }
    }

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
