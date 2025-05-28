package com.mja.reyamf.manager.adapter

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.wear.widget.RoundedDrawable
import com.mja.reyamf.R
import com.mja.reyamf.common.model.AppInfo
import com.mja.reyamf.databinding.SidebarItemviewBinding
import com.mja.reyamf.manager.services.YAMFManagerProxy
import com.mja.reyamf.xposed.IAppIconCallback
import com.mja.reyamf.xposed.IAppListCallback
import com.mja.reyamf.xposed.utils.componentName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
//                YAMFManagerProxy.getAppIcon(object : IAppIconCallback.Stub() {
//                    override fun onResult(iconData: ByteArray?) {
//                        CoroutineScope(Dispatchers.Main).launch {
//                            if (iconData != null) {
//                                val bitmap = BitmapFactory.decodeByteArray(iconData, 0, iconData.size)
//                                ivAppIcon.setImageBitmap(bitmap)
//                            } else {
//                                ivAppIcon.setImageResource(R.drawable.work_icon)
//                            }
//                        }
//                    }
//                }, appInfo)

                val iconDrawable = try {
                    binding.root.context.packageManager.getActivityIcon(appInfo.activityInfo.componentName)
                } catch (e: PackageManager.NameNotFoundException) {
                    ContextCompat.getDrawable(binding.root.context, R.drawable.work_icon)
                }
                ivAppIcon.setImageDrawable(iconDrawable)

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

        fun parseComponentInfo(componentInfo: String): Pair<String, String>? {
            val regex = Regex("ComponentInfo\\{(.+?)/(.+?)\\}")
            val match = regex.find(componentInfo)
            return match?.let {
                val (packageName, className) = it.destructured
                packageName to className
            }
        }
    }
}