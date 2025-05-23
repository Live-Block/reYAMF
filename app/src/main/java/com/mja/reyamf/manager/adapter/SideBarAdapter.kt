package com.mja.reyamf.manager.adapter

import android.content.pm.ActivityInfo
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.wear.widget.RoundedDrawable
import com.mja.reyamf.R
import com.mja.reyamf.common.model.AppInfo
import com.mja.reyamf.databinding.SidebarItemviewBinding

class SideBarAdapter (
    private val onClick: (AppInfo) -> Unit,
    private val sideBarApp: ArrayList<AppInfo>,
    private val onLongClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<SideBarAdapter.ViewHolder>() {

    fun setData(items: List<AppInfo>?) {
        sideBarApp.apply {
            clear()
            items?.let { addAll(it) }
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.sidebar_itemview, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(sideBarApp[position])

    override fun getItemCount(): Int = sideBarApp.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView){
        private val binding = SidebarItemviewBinding.bind(itemView)
        fun bind(appInfo: AppInfo){
            binding.apply {
                ivAppIcon.setImageDrawable(getIconLabel(appInfo.activityInfo).first)
                ivAppIcon.setOnClickListener {
                    onClick(appInfo)
                }
                ivAppIcon.setOnLongClickListener {
                    onLongClick(appInfo)
                    true
                }

                if (appInfo.userId == 0) {
                    mcvWorkIconBg.visibility = View.INVISIBLE
                } else {
                    mcvWorkIconBg.visibility = View.VISIBLE
                }
            }
        }

        fun getIconLabel(info: ActivityInfo): Pair<Drawable, CharSequence> {
            val pm = binding.root.context.packageManager
            return Pair(
                RoundedDrawable().apply {
                    isClipEnabled = true
                    radius = 100
                    drawable = info.loadIcon(pm)
                },
                info.loadLabel(pm)
            )
        }
    }
}