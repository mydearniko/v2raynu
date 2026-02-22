package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.databinding.ItemRecyclerFooterBinding
import com.v2ray.ang.databinding.ItemRecyclerMainBinding
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.dto.ServersCache
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import java.util.Collections

class MainRecyclerAdapter(
    private val mainViewModel: MainViewModel,
    private val adapterListener: MainAdapterListener?
) : RecyclerView.Adapter<MainRecyclerAdapter.BaseViewHolder>(), ItemTouchHelperAdapter {
    companion object {
        private const val VIEW_TYPE_ITEM = 1
        private const val VIEW_TYPE_FOOTER = 2
        private val CONTROL_OR_WHITESPACE_REGEX = Regex("[\\u0000-\\u001F\\u007F\\s]")
        private val CONTROL_REGEX = Regex("[\\u0000-\\u001F\\u007F]+")
    }

    private val doubleColumnDisplay = MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)
    private var data: MutableList<ServersCache> = mutableListOf()

    @SuppressLint("NotifyDataSetChanged")
    fun setData(newData: MutableList<ServersCache>?, position: Int = -1) {
        data = newData?.toMutableList() ?: mutableListOf()

        if (position >= 0 && position in data.indices) {
            notifyItemChanged(position)
        } else {
            notifyDataSetChanged()
        }
    }

    override fun getItemCount() = data.size + 1

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        if (holder is MainViewHolder) {
            val context = holder.itemMainBinding.root.context
            val guid = data[position].guid
            val profile = data[position].profile

            holder.itemView.setBackgroundColor(Color.TRANSPARENT)

            //Name address
            holder.itemMainBinding.tvName.text = profile.remarks
            val (endpoint, sni) = getAddress(profile)
            holder.itemMainBinding.tvStatistics.text = endpoint
            if (sni != null) {
                holder.itemMainBinding.tvSni.visibility = View.VISIBLE
                holder.itemMainBinding.tvSni.text = sni
            } else {
                holder.itemMainBinding.tvSni.visibility = View.GONE
                holder.itemMainBinding.tvSni.text = ""
            }
            holder.itemMainBinding.tvType.text = profile.configType.name

            //TestResult
            val aff = MmkvManager.decodeServerAffiliationInfo(guid)
            val samples = aff?.getSortedTestDelaySamples().orEmpty()
            if (samples.isNotEmpty()) {
                holder.itemMainBinding.tvTestResult.setTextColor(ContextCompat.getColor(context, R.color.colorPing))
                holder.itemMainBinding.tvTestResult.text = buildColoredDelaySamples(
                    samples = samples,
                    successColor = ContextCompat.getColor(context, R.color.colorPing),
                    failureColor = ContextCompat.getColor(context, R.color.colorPingRed)
                )
            } else {
                holder.itemMainBinding.tvTestResult.text = aff?.getTestDelayString().orEmpty()
                if ((aff?.testDelayMillis ?: 0L) < 0L) {
                    holder.itemMainBinding.tvTestResult.setTextColor(ContextCompat.getColor(context, R.color.colorPingRed))
                } else {
                    holder.itemMainBinding.tvTestResult.setTextColor(ContextCompat.getColor(context, R.color.colorPing))
                }
            }

            //layoutIndicator
            if (guid == MmkvManager.getSelectServer()) {
                holder.itemMainBinding.layoutIndicator.setBackgroundResource(R.color.colorIndicator)
            } else {
                holder.itemMainBinding.layoutIndicator.setBackgroundResource(0)
            }

            //subscription remarks
            val subRemarks = getSubscriptionRemarks(profile)
            holder.itemMainBinding.tvSubscription.text = subRemarks
            holder.itemMainBinding.layoutSubscription.visibility = if (subRemarks.isEmpty()) View.GONE else View.VISIBLE

            //layout
            if (doubleColumnDisplay) {
                holder.itemMainBinding.layoutShare.visibility = View.GONE
                holder.itemMainBinding.layoutEdit.visibility = View.GONE
                holder.itemMainBinding.layoutRemove.visibility = View.GONE
                holder.itemMainBinding.layoutMore.visibility = View.VISIBLE

                holder.itemMainBinding.layoutMore.setOnClickListener {
                    adapterListener?.onShare(guid, profile, position, true)
                }
            } else {
                holder.itemMainBinding.layoutShare.visibility = View.VISIBLE
                holder.itemMainBinding.layoutEdit.visibility = View.VISIBLE
                holder.itemMainBinding.layoutRemove.visibility = View.VISIBLE
                holder.itemMainBinding.layoutMore.visibility = View.GONE

                holder.itemMainBinding.layoutShare.setOnClickListener {
                    adapterListener?.onShare(guid, profile, position, false)
                }

                holder.itemMainBinding.layoutEdit.setOnClickListener {
                    adapterListener?.onEdit(guid, position, profile)
                }
                holder.itemMainBinding.layoutRemove.setOnClickListener {
                    adapterListener?.onRemove(guid, position)
                }
            }

            holder.itemMainBinding.infoContainer.setOnClickListener {
                adapterListener?.onSelectServer(guid)
            }
        }
 
    }

    /**
     * Gets the server address information
     * Shows endpoint and optional SNI-like host information.
     * @param profile The server configuration
     * @return endpoint and optional second-line value
     */
    private fun getAddress(profile: ProfileItem): Pair<String, String?> {
        val endpoint = buildFullEndpoint(profile)
        val sni = pickSecondaryHost(profile)
        return endpoint to sni
    }

    private fun buildFullEndpoint(profile: ProfileItem): String {
        val server = normalizeEndpointPart(profile.server)
        val port = normalizeEndpointPart(profile.serverPort)

        if (server == null && port == null) {
            return normalizeSingleLine(profile.description) ?: ""
        }

        val host = server?.let { Utils.getIpv6Address(it) }.orEmpty()
        return if (port != null) "$host:$port" else host
    }

    private fun pickSecondaryHost(profile: ProfileItem): String? {
        val sni = extractPrimaryValue(profile.sni)
        if (sni != null) return sni

        val host = extractPrimaryValue(profile.host)
        if (host != null) return host

        return extractPrimaryValue(profile.authority)
    }

    private fun normalizeEndpointPart(value: String?): String? {
        return value
            ?.replace(CONTROL_OR_WHITESPACE_REGEX, "")
            ?.nullIfBlank()
    }

    private fun normalizeSingleLine(value: String?): String? {
        return value
            ?.replace(CONTROL_REGEX, " ")
            ?.trim()
            ?.nullIfBlank()
    }

    private fun extractPrimaryValue(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return value
            .replace('\r', ',')
            .replace('\n', ',')
            .split(',')
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.nullIfBlank()
    }

    /**
     * Gets the subscription remarks information
     * @param profile The server configuration
     * @return Subscription remarks string, or empty string if none
     */
    private fun getSubscriptionRemarks(profile: ProfileItem): String {
        val subRemarks =
            if (mainViewModel.subscriptionId.isEmpty())
                MmkvManager.decodeSubscription(profile.subscriptionId)?.remarks?.firstOrNull()
            else
                null
        return subRemarks?.toString() ?: ""
    }

    private fun buildColoredDelaySamples(
        samples: List<Long>,
        successColor: Int,
        failureColor: Int
    ): Spannable {
        val builder = SpannableStringBuilder()
        samples.forEachIndexed { index, sample ->
            if (index > 0) {
                builder.append(" / ")
            }
            val start = builder.length
            builder.append("${sample}ms")
            val end = builder.length
            builder.setSpan(
                ForegroundColorSpan(if (sample >= 0L) successColor else failureColor),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return builder
    }

    fun removeServerSub(guid: String, position: Int) {
        val idx = data.indexOfFirst { it.guid == guid }
        if (idx >= 0) {
            data.removeAt(idx)
            notifyItemRemoved(idx)
            notifyItemRangeChanged(idx, data.size - idx)
        }
    }

    fun setSelectServer(fromPosition: Int, toPosition: Int) {
        notifyItemChanged(fromPosition)
        notifyItemChanged(toPosition)
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
        return if (position == data.size) {
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
        BaseViewHolder(itemFooterBinding.root)

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        mainViewModel.swapServer(fromPosition, toPosition)
        if (fromPosition < data.size && toPosition < data.size) {
            Collections.swap(data, fromPosition, toPosition)
        }
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun onItemMoveCompleted() {
        // do nothing
    }

    override fun onItemDismiss(position: Int) {
    }
}
