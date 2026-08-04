package top.niunaijun.blackboxa.view.apps

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import cbfg.rvadapter.RVHolder
import cbfg.rvadapter.RVHolderFactory
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.AppInfo
import top.niunaijun.blackboxa.databinding.ItemAppBinding
import top.niunaijun.blackboxa.view.base.Ambient


class AppsAdapter : RVHolderFactory() {

    companion object {
        private const val TAG = "AppsAdapter"
        private const val MAX_ICON_SIZE = 96
        private val DEFAULT_ICON_COLOR = Color.parseColor("#33808080")
    }

    /**
     * Package currently being launched. The matching tile shows a spinner and
     * the fragment ignores further taps, so a slow cold start cannot be
     * triggered several times in a row.
     */
    var launchingPackage: String? = null

    /** Colour of the space this grid belongs to; tints the halo behind icons. */
    var accentColor: Int = 0

    override fun createViewHolder(parent: ViewGroup?, viewType: Int, item: Any): RVHolder<out Any> {
        return try {
            AppsVH(inflate(R.layout.item_app, parent), this)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating ViewHolder: ${e.message}")
            FallbackAppsVH(inflate(R.layout.item_app, parent))
        }
    }

    class AppsVH(itemView: View, private val factory: AppsAdapter) : RVHolder<AppInfo>(itemView) {

        private val binding = ItemAppBinding.bind(itemView)

        init {
            binding.icon.scaleType = ImageView.ScaleType.FIT_CENTER
        }

        private fun applyHalo() {
            val accent = factory.accentColor
            if (accent == 0) {
                binding.iconHalo.background = null
                return
            }
            val radius = Ambient.dp(itemView.context, 24f)
            binding.iconHalo.background = Ambient.halo(accent, radius, 46)
            binding.iconHalo.scaleX = 1.12f
            binding.iconHalo.scaleY = 1.12f
        }

        override fun setContent(item: AppInfo, isSelected: Boolean, payload: Any?) {
            try {
                setIconSafely(item.icon, item.packageName)
                binding.name.text = item.name ?: item.packageName
                applyHalo()

                binding.cornerLabel.visibility =
                        if (item.isXpModule) View.VISIBLE else View.INVISIBLE

                val launching = item.packageName == factory.launchingPackage
                binding.launchingOverlay.visibility = if (launching) View.VISIBLE else View.GONE
                itemView.alpha = if (factory.launchingPackage != null && !launching) 0.45f else 1f
            } catch (e: Exception) {
                Log.e(TAG, "Error setting content for ${item.packageName}: ${e.message}")
                setSafeDefaults()
            }
        }

        private fun setIconSafely(icon: Drawable?, packageName: String) {
            try {
                binding.icon.setImageDrawable(icon?.let { optimizeIcon(it) } ?: createDefaultIcon())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set icon for $packageName: ${e.message}")
                binding.icon.setImageDrawable(createDefaultIcon())
            }
        }

        private fun optimizeIcon(icon: Drawable): Drawable {
            return try {
                if (icon is BitmapDrawable) {
                    val bitmap = icon.bitmap
                    if (bitmap.width > MAX_ICON_SIZE || bitmap.height > MAX_ICON_SIZE) {
                        BitmapDrawable(
                                itemView.resources,
                                Bitmap.createScaledBitmap(bitmap, MAX_ICON_SIZE, MAX_ICON_SIZE, true))
                    } else {
                        icon
                    }
                } else {
                    icon
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error optimizing icon: ${e.message}")
                icon
            }
        }

        private fun createDefaultIcon(): Drawable = ColorDrawable(DEFAULT_ICON_COLOR)

        private fun setSafeDefaults() {
            try {
                binding.icon.setImageDrawable(createDefaultIcon())
                binding.name.text = ""
                binding.cornerLabel.visibility = View.INVISIBLE
                binding.launchingOverlay.visibility = View.GONE
                itemView.alpha = 1f
            } catch (e: Exception) {
                Log.e(TAG, "Error setting safe defaults: ${e.message}")
            }
        }
    }

    /** Used only if the normal holder fails to inflate. */
    class FallbackAppsVH(itemView: View) : RVHolder<AppInfo>(itemView) {

        private val binding = ItemAppBinding.bind(itemView)

        override fun setContent(item: AppInfo, isSelected: Boolean, payload: Any?) {
            try {
                binding.icon.setImageDrawable(ColorDrawable(DEFAULT_ICON_COLOR))
                binding.name.text = item.name ?: item.packageName
                binding.cornerLabel.visibility = View.INVISIBLE
                binding.launchingOverlay.visibility = View.GONE
            } catch (e: Exception) {
                Log.e(TAG, "Error in fallback ViewHolder: ${e.message}")
            }
        }
    }
}
