package top.niunaijun.blackboxa.view.list

import android.view.View
import android.view.ViewGroup
import cbfg.rvadapter.RVHolder
import cbfg.rvadapter.RVHolderFactory
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.InstalledAppBean
import top.niunaijun.blackboxa.databinding.ItemPackageBinding


class ListAdapter : RVHolderFactory() {

    /** Packages ticked for the current batch add. Owned by [ListActivity]. */
    val selected = linkedSetOf<String>()

    override fun createViewHolder(parent: ViewGroup?, viewType: Int, item: Any): RVHolder<out Any> {
        return ListVH(inflate(R.layout.item_package, parent), this)
    }

    class ListVH(itemView: View, private val factory: ListAdapter) : RVHolder<InstalledAppBean>(itemView) {

        private val binding = ItemPackageBinding.bind(itemView)

        override fun setContent(item: InstalledAppBean, isSelected: Boolean, payload: Any?) {
            binding.icon.setImageDrawable(item.icon)
            // Some packages report a label the host cannot resolve and it arrives
            // as the literal "@string/...". Showing that is worse than showing
            // nothing, so fall back to the last segment of the package name.
            binding.name.text = if (item.name.startsWith("@")) {
                item.packageName.substringAfterLast('.').replaceFirstChar { c -> c.uppercase() }
            } else {
                item.name
            }
            binding.packageName.text = item.packageName

            binding.installedBadge.visibility = if (item.isInstall) View.VISIBLE else View.GONE
            binding.checkbox.visibility = if (item.isInstall) View.GONE else View.VISIBLE
            binding.checkbox.isChecked = item.packageName in factory.selected
            // The badge already says the app is in this space. Dimming the whole
            // row to 55% on top of it was the one place the interface really was
            // running at reduced opacity — state is told by the badge, not by
            // fading a row nobody can then read.
            itemView.alpha = 1f
        }
    }
}
