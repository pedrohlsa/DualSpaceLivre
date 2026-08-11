package top.niunaijun.blackboxa.bridge

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * Activity-ponte, invisível e efêmera: recebe o pedido externo (ADB), delega a
 * [SpaceBridge] e encerra. Não tem UI e não deve nunca ficar visível.
 *
 * Comando do desktop (Gerenciador de Contas), via ADB:
 * ```
 * adb shell am start \
 *   -n com.dualspace.livre/top.niunaijun.blackboxa.bridge.OpenSpaceActivity \
 *   -a com.dualspace.livre.action.OPEN_IN_SPACE \
 *   --ei space_id 0 \
 *   --es package com.instagram.android
 * ```
 * `space_id` é o id interno do espaço (BUserInfo.id, base 0); `package` é um app
 * já instalado NAQUELE espaço. Falha silenciosa e logada se algo não confere.
 */
class OpenSpaceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // App/engine já vivo: com singleTask reaproveitamos a mesma instância,
        // então tratamos o novo pedido aqui em vez de recriar a Activity.
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        // `nonce` (opcional) é só um eco para o solicitante casar ESTE pedido com
        // a linha de resultado no logcat — `am start` não devolve o desfecho da
        // validação, que acontece aqui dentro. Sempre logamos, sucesso ou não.
        val nonce = intent?.getStringExtra(SpaceBridge.EXTRA_NONCE).orEmpty()
        val result = when (intent?.action) {
            SpaceBridge.ACTION_SET_CLIPBOARD -> SpaceBridge.setClipboard(this, intent)
            else -> SpaceBridge.open(intent)
        }
        Log.w(TAG, "outcome nonce=$nonce result=$result")
        finish()
    }

    companion object {
        private const val TAG = "OpenSpaceActivity"
    }
}
