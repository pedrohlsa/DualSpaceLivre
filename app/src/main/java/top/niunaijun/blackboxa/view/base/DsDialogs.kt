package top.niunaijun.blackboxa.view.base

import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.radiobutton.MaterialRadioButton
import top.niunaijun.blackboxa.R

/**
 * Dialogs that belong to the same visual system as the rest of Dual Space.
 *
 * The project used afollestad/material-dialogs directly in every feature. Its
 * stock input field, uppercase actions and Roboto fallbacks survived the UI
 * redesign and made confirmations look like a different application. Keeping
 * construction here makes spacing, type, action hierarchy and destructive
 * colour consistent without changing any feature's behaviour.
 */
object DsDialogs {

    fun show(
        context: Context,
        @StringRes title: Int,
        message: CharSequence? = null,
        @StringRes positive: Int = R.string.done,
        onPositive: (() -> Unit)? = null,
        @StringRes negative: Int? = null,
        onNegative: (() -> Unit)? = null,
        @StringRes neutral: Int? = null,
        onNeutral: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null,
        destructive: Boolean = false
    ): AlertDialog {
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(title)

        if (!message.isNullOrBlank()) builder.setMessage(message)
        builder.setPositiveButton(positive) { _, _ -> onPositive?.invoke() }
        negative?.let { label ->
            builder.setNegativeButton(label) { _, _ -> onNegative?.invoke() }
        }
        neutral?.let { label ->
            builder.setNeutralButton(label) { _, _ -> onNeutral?.invoke() }
        }

        val dialog = builder.create()
        onCancel?.let { callback -> dialog.setOnCancelListener { callback() } }
        dialog.setOnShowListener { polish(dialog, destructive) }
        dialog.show()
        return dialog
    }

    fun confirm(
        context: Context,
        @StringRes title: Int,
        message: CharSequence,
        @StringRes positive: Int,
        destructive: Boolean = false,
        onCancel: (() -> Unit)? = null,
        onConfirm: () -> Unit
    ): AlertDialog = show(
        context = context,
        title = title,
        message = message,
        positive = positive,
        onPositive = onConfirm,
        negative = R.string.cancel,
        onNegative = onCancel,
        onCancel = onCancel,
        destructive = destructive
    )

    fun input(
        context: Context,
        @StringRes title: Int,
        @StringRes hint: Int,
        prefill: CharSequence,
        @StringRes positive: Int = R.string.done,
        onSubmit: (String) -> Unit
    ): AlertDialog {
        val content = LayoutInflater.from(context)
            .inflate(R.layout.dialog_text_input, null, false)
        val inputLayout = content.findViewById<TextInputLayout>(R.id.inputLayout)
        val editText = content.findViewById<TextInputEditText>(R.id.inputEdit)
        inputLayout.hint = context.getString(hint)
        editText.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        editText.setText(prefill)
        editText.selectAll()

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(positive, null)
            .create()

        dialog.setOnShowListener {
            polish(dialog, destructive = false)
            val submit = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            submit.setOnClickListener {
                val value = editText.text?.toString()?.trim().orEmpty()
                if (value.isEmpty()) {
                    inputLayout.error = context.getString(R.string.field_required)
                    return@setOnClickListener
                }
                inputLayout.error = null
                onSubmit(value)
                dialog.dismiss()
            }
            editText.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submit.performClick()
                    true
                } else {
                    false
                }
            }
            editText.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
        return dialog
    }

    fun singleChoice(
        context: Context,
        @StringRes title: Int,
        options: Array<String>,
        checkedIndex: Int,
        onSelected: (Int) -> Unit
    ): AlertDialog {
        val content = LayoutInflater.from(context)
            .inflate(R.layout.dialog_single_choice, null, false)
        val choices = content.findViewById<LinearLayout>(R.id.choiceContainer)
        val regular = ResourcesCompat.getFont(context, R.font.inter_regular)
        val tint = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(
                ContextCompat.getColor(context, R.color.ds_accent),
                ContextCompat.getColor(context, R.color.ds_on_surface_muted)
            )
        )

        lateinit var dialog: AlertDialog
        options.forEachIndexed { index, label ->
            choices.addView(MaterialRadioButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    Ambient.dp(context, 56f).toInt()
                )
                text = label
                textSize = 16f
                typeface = regular ?: Typeface.DEFAULT
                setTextColor(ContextCompat.getColor(context, R.color.ds_on_surface))
                buttonTintList = tint
                isChecked = index == checkedIndex
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(
                    Ambient.dp(context, 4f).toInt(), 0,
                    Ambient.dp(context, 4f).toInt(), 0
                )
                setOnClickListener {
                    dialog.dismiss()
                    // Night-mode choices may immediately re-theme the current
                    // Activity. Run the callback on the next main-loop turn so
                    // this window is fully detached first; otherwise Android can
                    // briefly keep/repaint the stale dialog over the new theme.
                    Handler(Looper.getMainLooper()).post { onSelected(index) }
                }
            })
        }

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener { polish(dialog, destructive = false) }
        dialog.show()
        return dialog
    }

    private fun polish(dialog: AlertDialog, destructive: Boolean) {
        val context = dialog.context
        dialog.window?.apply {
            setBackgroundDrawableResource(R.drawable.bg_dialog)
            val margin = Ambient.dp(context, 20f).toInt()
            val maxWidth = Ambient.dp(context, 560f).toInt()
            val width = (context.resources.displayMetrics.widthPixels - margin * 2)
                .coerceAtMost(maxWidth)
            setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val regular = ResourcesCompat.getFont(context, R.font.inter_regular)
        val semibold = ResourcesCompat.getFont(context, R.font.inter_semibold)

        dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.apply {
            typeface = semibold ?: Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(context, R.color.ds_on_surface))
            textSize = 20f
        }
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            typeface = regular ?: Typeface.DEFAULT
            setTextColor(ContextCompat.getColor(context, R.color.ds_on_surface_muted))
            textSize = 14f
            setLineSpacing(0f, 1.22f)
        }

        listOf(
            DialogInterface.BUTTON_POSITIVE,
            DialogInterface.BUTTON_NEGATIVE,
            DialogInterface.BUTTON_NEUTRAL
        ).forEach { which ->
            dialog.getButton(which)?.apply {
                isAllCaps = false
                typeface = semibold ?: Typeface.DEFAULT_BOLD
                textSize = 14f
                letterSpacing = 0.01f
                minHeight = Ambient.dp(context, 48f).toInt()
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (which == DialogInterface.BUTTON_POSITIVE && destructive) {
                            R.color.ds_danger
                        } else {
                            R.color.ds_accent
                        }
                    )
                )
            }
        }
    }
}
