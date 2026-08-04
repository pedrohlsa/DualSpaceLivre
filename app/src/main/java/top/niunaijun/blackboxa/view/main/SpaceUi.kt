package top.niunaijun.blackboxa.view.main

import android.graphics.Color
import android.util.Log
import androidx.core.content.edit
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.app.App
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.R

/**
 * Presentation-only helpers for spaces: display name, colour and app count.
 *
 * None of this touches the engine's user ids — a space is still identified by
 * the virtual user id returned by [BlackBoxCore.getUsers]; this object only
 * decorates it. Name and colour live in the "UserRemark" preferences under
 * `Remark<id>` / `Color<id>`.
 */
object SpaceUi {

    private const val TAG = "SpaceUi"

    /** Distinct hues that stay readable on both the dark and the light theme. */
    val palette = intArrayOf(
            0xFF7C5CFF.toInt(), // violeta
            0xFF2E9BFF.toInt(), // azul
            0xFF00C2A8.toInt(), // turquesa
            0xFF3DD68C.toInt(), // verde
            0xFFF2B33D.toInt(), // âmbar
            0xFFFF8A5C.toInt(), // coral
            0xFFFF5C93.toInt(), // rosa
            0xFFB06CFF.toInt(), // lilás
            0xFF6C7BFF.toInt(), // índigo
            0xFF00B5C8.toInt(), // ciano
            0xFFFF5A5A.toInt(), // vermelho
            0xFFA8CC3D.toInt(), // limão
            0xFFC08457.toInt(), // bronze
            0xFF8794B0.toInt()  // ardósia
    )

    val paletteNames = arrayOf(
            "Violeta", "Azul", "Turquesa", "Verde", "Âmbar",
            "Coral", "Rosa", "Lilás", "Índigo", "Ciano",
            "Vermelho", "Limão", "Bronze", "Ardósia")

    fun sortedUsers(): List<Int> = try {
        BlackBoxCore.get().users.map { it.id }.sorted()
    } catch (e: Exception) {
        Log.e(TAG, "Error listing users: ${e.message}")
        emptyList()
    }

    fun defaultName(userId: Int): String =
            App.getContext().getString(R.string.space_default_name, userId + 1)

    fun nameOf(userId: Int): String {
        return try {
            val stored = AppManager.mRemarkSharedPreferences.getString("Remark$userId", null)
            if (stored.isNullOrEmpty()) defaultName(userId) else stored
        } catch (e: Exception) {
            defaultName(userId)
        }
    }

    fun setName(userId: Int, name: String) {
        val trimmed = name.trim()
        AppManager.mRemarkSharedPreferences.edit {
            if (trimmed.isEmpty()) remove("Remark$userId") else putString("Remark$userId", trimmed)
        }
    }

    /** Colours already claimed by spaces other than [userId]. */
    fun colorsUsedByOthers(userId: Int, allIds: List<Int> = sortedUsers()): Set<Int> {
        val prefs = AppManager.mRemarkSharedPreferences
        return allIds.asSequence()
                .filter { it != userId }
                .map { prefs.getInt("Color$it", 0) }
                .filter { it != 0 }
                .toSet()
    }

    /**
     * Two spaces must never look alike, and a stored colour does not have to be
     * an exact palette entry (spaces created by older versions carry the old
     * palette). So availability is decided by perceptual distance rather than
     * by equality.
     */
    private const val MIN_COLOR_DISTANCE = 45.0

    fun isColorTaken(color: Int, used: Set<Int>): Boolean =
            used.any { colorDistance(it, color) < MIN_COLOR_DISTANCE }

    private fun colorDistance(a: Int, b: Int): Double {
        val dr = (Color.red(a) - Color.red(b)).toDouble()
        val dg = (Color.green(a) - Color.green(b)).toDouble()
        val db = (Color.blue(a) - Color.blue(b)).toDouble()
        return Math.sqrt(dr * dr + dg * dg + db * db)
    }

    /**
     * Colour of a space, assigning (and persisting) a free one on first use.
     * Two spaces are never allowed to share a colour.
     */
    fun colorOf(userId: Int, allIds: List<Int> = sortedUsers()): Int {
        return try {
            val prefs = AppManager.mRemarkSharedPreferences
            val used = colorsUsedByOthers(userId, allIds)
            val stored = prefs.getInt("Color$userId", 0)
            if (stored != 0 && stored !in used) return stored

            val color = palette.firstOrNull { !isColorTaken(it, used) }
                    ?: generateUnique(userId, used)
            prefs.edit { putInt("Color$userId", color) }
            color
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving space colour: ${e.message}")
            palette[Math.floorMod(userId, palette.size)]
        }
    }

    fun setColor(userId: Int, color: Int) {
        AppManager.mRemarkSharedPreferences.edit { putInt("Color$userId", color) }
    }

    fun ensureUniqueColors(allIds: List<Int> = sortedUsers()) {
        migrateLegacyColors(allIds)
        allIds.forEach { colorOf(it, allIds) }
    }

    private const val MIGRATION_KEY = "palette_v2"

    /**
     * Spaces created by earlier versions stored colours from the previous
     * palette, which no longer match any current entry — that made the
     * "no two spaces share a colour" rule silently pass for near-identical
     * hues. Remap each space once to its closest free palette entry so the
     * spaces keep their look while uniqueness becomes an exact comparison.
     */
    private fun migrateLegacyColors(allIds: List<Int>) {
        try {
            val prefs = AppManager.mRemarkSharedPreferences
            if (prefs.getBoolean(MIGRATION_KEY, false)) return

            val taken = mutableSetOf<Int>()
            allIds.sorted().forEach { id ->
                val stored = prefs.getInt("Color$id", 0)
                val free = palette.filter { it !in taken }
                val mapped = when {
                    stored != 0 && stored in palette && stored !in taken -> stored
                    stored != 0 -> free.minByOrNull { colorDistance(it, stored) }
                    else -> free.firstOrNull()
                } ?: generateUnique(id, taken)

                prefs.edit { putInt("Color$id", mapped) }
                taken.add(mapped)
            }
            prefs.edit { putBoolean(MIGRATION_KEY, true) }
        } catch (e: Exception) {
            Log.e(TAG, "Error migrating space colours: ${e.message}")
        }
    }

    private fun generateUnique(userId: Int, used: Set<Int>): Int {
        var hue = Math.floorMod(userId * 137 + 23, 360).toFloat()
        repeat(360) {
            val candidate = Color.HSVToColor(floatArrayOf(hue, 0.62f, 0.92f))
            if (!isColorTaken(candidate, used)) return candidate
            hue = (hue + 37f) % 360f
        }
        return palette[Math.floorMod(userId, palette.size)]
    }

    fun appCount(userId: Int): Int = try {
        BlackBoxCore.get().getInstalledApplications(0, userId)?.size ?: 0
    } catch (e: Exception) {
        Log.e(TAG, "Error counting apps of space $userId: ${e.message}")
        0
    }

    /** First id not taken by an existing space — the id the "new space" page uses. */
    fun nextAvailableId(existingIds: Collection<Int> = sortedUsers()): Int {
        val ids = existingIds.toSet()
        var candidate = 0
        while (candidate in ids) candidate++
        return candidate
    }

    /** Slightly darker/lighter variant, used for tinted accents. */
    fun shade(color: Int, factor: Float): Int = Color.rgb(
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255))
}
