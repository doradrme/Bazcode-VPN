package com.v2ray.ang.ui

import android.content.Intent
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemQrcodeBinding
import com.v2ray.ang.databinding.ItemRecyclerFooterBinding
import com.v2ray.ang.databinding.ItemRecyclerMainBinding
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.extension.toast
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.service.V2RayServiceManager
import com.v2ray.ang.util.AngConfigManager
import com.v2ray.ang.util.MmkvManager
import com.v2ray.ang.util.Utils
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import java.util.concurrent.TimeUnit

class MainRecyclerAdapter(val activity: MainActivity) : RecyclerView.Adapter<MainRecyclerAdapter.BaseViewHolder>()
        , ItemTouchHelperAdapter {
    companion object {
        private const val VIEW_TYPE_ITEM = 1
        private const val VIEW_TYPE_FOOTER = 2
    }

    private var mActivity: MainActivity = activity
    private val mainStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_MAIN, MMKV.MULTI_PROCESS_MODE) }
    private val subStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_SUB, MMKV.MULTI_PROCESS_MODE) }
    private val share_method: Array<out String> by lazy {
        mActivity.resources.getStringArray(R.array.share_method)
    }
    var isRunning = false

    override fun getItemCount() = mActivity.mainViewModel.serverList.size + 1

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        if (holder is MainViewHolder) {
            val guid = mActivity.mainViewModel.serverList.getOrNull(position) ?: return
            val config = mActivity.mainViewModel.serversCache.getOrElse(guid) { MmkvManager.decodeServerConfig(guid) } ?: return
            val outbound = config.getProxyOutbound()
            val aff = MmkvManager.decodeServerAffiliationInfo(guid)

            val flag = getCountryFlag(config.remarks)
            holder.itemMainBinding.tvName.text = if (flag.isNotEmpty()) "[$flag] ${config.remarks}" else config.remarks
            holder.itemMainBinding.btnRadio.isChecked = guid == mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER)
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            holder.itemMainBinding.tvTestResult.text = aff?.getTestDelayString() ?: ""
            if (aff?.testDelayMillis?:0L < 0L) {
                holder.itemMainBinding.tvTestResult.setTextColor(ContextCompat.getColor(mActivity, android.R.color.holo_red_dark))
            } else {
                holder.itemMainBinding.tvTestResult.setTextColor(ContextCompat.getColor(mActivity, R.color.colorPing))
            }
            holder.itemMainBinding.tvSubscription.text = ""
            val json = subStorage?.decodeString(config.subscriptionId)
            if (!json.isNullOrBlank()) {
                val sub = Gson().fromJson(json, SubscriptionItem::class.java)
                holder.itemMainBinding.tvSubscription.text = sub.remarks
            }

            var shareOptions = share_method.asList()
            when (config.configType) {
                EConfigType.CUSTOM -> {
                    holder.itemMainBinding.tvType.text = mActivity.getString(R.string.server_customize_config)
                    shareOptions = shareOptions.takeLast(1)
                }
                EConfigType.VLESS -> {
                    holder.itemMainBinding.tvType.text = config.configType.name
                }
                else -> {
                    holder.itemMainBinding.tvType.text = config.configType.name.lowercase()
                }
            }
            holder.itemMainBinding.tvStatistics.text = "${outbound?.getServerAddress()} : ${outbound?.getServerPort()}"

            holder.itemMainBinding.tvTestResult.setOnClickListener {
                holder.itemMainBinding.tvTestResult.text = "..."
                mActivity.mainViewModel.testServerTcping(guid)
            }

            holder.itemMainBinding.layoutShare.setOnClickListener {
                AlertDialog.Builder(mActivity).setItems(shareOptions.toTypedArray()) { _, i ->
                    try {
                        when (i) {
                            0 -> {
                                if (config.configType == EConfigType.CUSTOM) {
                                    shareFullContent(guid)
                                } else {
                                    val ivBinding = ItemQrcodeBinding.inflate(LayoutInflater.from(mActivity))
                                    ivBinding.ivQcode.setImageBitmap(AngConfigManager.share2QRCode(guid))
                                    AlertDialog.Builder(mActivity).setView(ivBinding.root).show()
                                }
                            }
                            1 -> {
                                if (AngConfigManager.share2Clipboard(mActivity, guid) == 0) {
                                    mActivity.toast(R.string.toast_success)
                                } else {
                                    mActivity.toast(R.string.toast_failure)
                                }
                            }
                            2 -> shareFullContent(guid)
                            else -> mActivity.toast("else")
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }.show()
            }

            holder.itemMainBinding.layoutEdit.setOnClickListener {
                val intent = Intent().putExtra("guid", guid)
                        .putExtra("isRunning", isRunning)
                if (config.configType == EConfigType.CUSTOM) {
                    mActivity.startActivity(intent.setClass(mActivity, ServerCustomConfigActivity::class.java))
                } else {
                    mActivity.startActivity(intent.setClass(mActivity, ServerActivity::class.java))
                }
            }

            holder.itemMainBinding.layoutPing?.setOnClickListener {
                holder.itemMainBinding.tvTestResult.text = "..."
                mActivity.mainViewModel.testServerTcping(guid)
            }
            holder.itemMainBinding.layoutRemove.setOnClickListener {
                if (guid != mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER)) {
                    mActivity.mainViewModel.removeServer(guid)
                    notifyItemRemoved(position)
                    notifyItemRangeChanged(position, mActivity.mainViewModel.serverList.size)
                }
            }

            holder.itemMainBinding.infoContainer.setOnClickListener {
                val selected = mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER)
                if (guid != selected) {
                    mainStorage?.encode(MmkvManager.KEY_SELECTED_SERVER, guid)
                    notifyItemChanged(mActivity.mainViewModel.serverList.indexOf(selected))
                    notifyItemChanged(mActivity.mainViewModel.serverList.indexOf(guid))
                    if (isRunning) {
                        mActivity.showCircle()
                        Utils.stopVService(mActivity)
                        Observable.timer(500, TimeUnit.MILLISECONDS)
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe {
                                    V2RayServiceManager.startV2Ray(mActivity)
                                    mActivity.hideCircle()
                                }
                    }
                }
            }
        }
        if (holder is FooterViewHolder) {
            //if (activity?.defaultDPreference?.getPrefBoolean(AppConfig.PREF_INAPP_BUY_IS_PREMIUM, false)) {
            if (true) {
                holder.itemFooterBinding.layoutEdit.visibility = View.INVISIBLE
            } else {
                holder.itemFooterBinding.layoutEdit.setOnClickListener {
                    Utils.openUri(mActivity, "${Utils.decode(AppConfig.promotionUrl)}?t=${System.currentTimeMillis()}")
                }
            }
        }
    }

    private fun shareFullContent(guid: String) {
        if (AngConfigManager.shareFullContent2Clipboard(mActivity, guid) == 0) {
            mActivity.toast(R.string.toast_success)
        } else {
            mActivity.toast(R.string.toast_failure)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        return when (viewType) {
            VIEW_TYPE_ITEM ->
                MainViewHolder(ItemRecyclerMainBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            else ->
                FooterViewHolder(ItemRecyclerFooterBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == mActivity.mainViewModel.serverList.size) {
            VIEW_TYPE_FOOTER
        } else {
            VIEW_TYPE_ITEM
        }
    }

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun onItemSelected() {
            itemView.setBackgroundColor(Color.LTGRAY)
        }

        fun onItemClear() {
            itemView.setBackgroundColor(0)
        }
    }

    class MainViewHolder(val itemMainBinding: ItemRecyclerMainBinding) :
            BaseViewHolder(itemMainBinding.root), ItemTouchHelperViewHolder

    class FooterViewHolder(val itemFooterBinding: ItemRecyclerFooterBinding) :
            BaseViewHolder(itemFooterBinding.root), ItemTouchHelperViewHolder

    override fun onItemDismiss(position: Int) {
        val guid = mActivity.mainViewModel.serverList.getOrNull(position) ?: return
        if (guid != mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER)) {
//            mActivity.alert(R.string.del_config_comfirm) {
//                positiveButton(android.R.string.ok) {
            mActivity.mainViewModel.removeServer(guid)
            notifyItemRemoved(position)
//                }
//                show()
//            }
        }
    }

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        mActivity.mainViewModel.swapServer(fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
        // position is changed, since position is used by click callbacks, need to update range
        if (toPosition > fromPosition)
            notifyItemRangeChanged(fromPosition, toPosition - fromPosition + 1)
        else
            notifyItemRangeChanged(toPosition, fromPosition - toPosition + 1)
        return true
    }

    override fun onItemMoveCompleted() {
        // do nothing
    }

    private fun getCountryFlag(remarks: String): String {
        val name = remarks.lowercase()
        val flags = mapOf(
            "us" to "US", "usa" to "US", "america" to "US",
            "de" to "DE", "germany" to "DE", "deutsch" to "DE",
            "fr" to "FR", "france" to "FR",
            "gb" to "GB", "uk" to "GB", "united kingdom" to "GB", "britain" to "GB",
            "nl" to "NL", "netherlands" to "NL", "holland" to "NL",
            "jp" to "JP", "japan" to "JP",
            "kr" to "KR", "korea" to "KR",
            "sg" to "SG", "singapore" to "SG",
            "hk" to "HK", "hong kong" to "HK",
            "tw" to "TW", "taiwan" to "TW",
            "in" to "IN", "india" to "IN",
            "ru" to "RU", "russia" to "RU",
            "ca" to "CA", "canada" to "CA",
            "au" to "AU", "australia" to "AU",
            "br" to "BR", "brazil" to "BR",
            "ir" to "IR", "iran" to "IR",
            "tr" to "TR", "turkey" to "TR", "turkiye" to "TR",
            "ae" to "AE", "uae" to "AE", "dubai" to "AE",
            "se" to "SE", "sweden" to "SE",
            "ch" to "CH", "switzerland" to "CH",
            "it" to "IT", "italy" to "IT",
            "es" to "ES", "spain" to "ES",
            "pl" to "PL", "poland" to "PL",
            "fi" to "FI", "finland" to "FI",
            "no" to "NO", "norway" to "NO",
            "dk" to "DK", "denmark" to "DK",
            "at" to "AT", "austria" to "AT",
            "be" to "BE", "belgium" to "BE",
            "ie" to "IE", "ireland" to "IE",
            "il" to "IL", "israel" to "IL",
            "sa" to "SA", "saudi" to "SA",
            "eg" to "EG", "egypt" to "EG",
            "za" to "ZA", "south africa" to "ZA",
            "ar" to "AR", "argentina" to "AR",
            "mx" to "MX", "mexico" to "MX",
            "id" to "ID", "indonesia" to "ID",
            "my" to "MY", "malaysia" to "MY",
            "th" to "TH", "thailand" to "TH",
            "vn" to "VN", "vietnam" to "VN",
            "ph" to "PH", "philippines" to "PH",
            "pk" to "PK", "pakistan" to "PK",
            "bd" to "BD", "bangladesh" to "BD",
            "ua" to "UA", "ukraine" to "UA",
            "ro" to "RO", "romania" to "RO",
            "cz" to "CZ", "czech" to "CZ",
            "hu" to "HU", "hungary" to "HU",
            "pt" to "PT", "portugal" to "PT",
            "gr" to "GR", "greece" to "GR",
            "cl" to "CL", "chile" to "CL",
            "co" to "CO", "colombia" to "CO",
            "pe" to "PE", "peru" to "PE"
        )
        for ((key, code) in flags) {
            if (name.contains(key)) return code
        }
        return ""
    }
}
