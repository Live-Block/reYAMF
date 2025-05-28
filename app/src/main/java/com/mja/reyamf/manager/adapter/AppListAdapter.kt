package com.mja.reyamf.manager.adapter

import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.wear.widget.RoundedDrawable
import com.mja.reyamf.R
import com.mja.reyamf.common.model.AppInfo
import com.mja.reyamf.databinding.ItemAppBinding
import com.mja.reyamf.manager.services.YAMFManagerProxy
import com.mja.reyamf.xposed.IAppIconCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AppListAdapter (
    private val onClick: (AppInfo) -> Unit,
    private val appList: ArrayList<AppInfo>,
    private val onLongClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    fun setData(items: List<AppInfo>?) {
        appList.apply {
            clear()
            items?.let { addAll(it) }
        }
    }
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }
    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(appList[position])

    override fun getItemCount(): Int = appList.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView){
        private val binding = ItemAppBinding.bind(itemView)
        fun bind(appInfo: AppInfo){
            binding.apply {
                val label = getIconLabel(appInfo.activityInfo)

                YAMFManagerProxy.getAppIcon(object : IAppIconCallback.Stub() {
                    override fun onResult(iconData: ByteArray?) {
                        CoroutineScope(Dispatchers.Main).launch {
                            if (iconData != null) {
                                val bitmap = BitmapFactory.decodeByteArray(iconData, 0, iconData.size)
                                ivIcon.setImageBitmap(bitmap)
                            } else {
                                ivIcon.setImageResource(R.drawable.work_icon)
                            }
                        }
                    }
                }, appInfo)

                tvLabel.text = label

                ll.setOnClickListener {
                    onClick(appInfo)
                }

                ll.setOnLongClickListener {
                    onLongClick(appInfo)
                    true
                }
            }
        }

        fun getIconLabel(info: ActivityInfo): CharSequence {
            val pm = binding.root.context.packageManager
            return info.loadLabel(pm)
        }
    }

}