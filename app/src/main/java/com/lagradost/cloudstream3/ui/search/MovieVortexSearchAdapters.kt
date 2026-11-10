package com.lagradost.cloudstream3.ui.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.databinding.MovievortexRecentSearchCardBinding
import com.lagradost.cloudstream3.databinding.MovievortexStarCastCardBinding
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage

data class RecentMediaItem(
    val title: String,
    val type: String,
    val posterUrl: String,
    val url: String = "",
    val apiName: String = "",
    val searchResponse: SearchResponse? = null
)

data class StarCastItem(
    val name: String,
    val profileUrl: String,
    val role: String = ""
)

class RecentSearchMediaAdapter(
    private var items: List<RecentMediaItem> = emptyList(),
    private val onClick: (RecentMediaItem) -> Unit
) : RecyclerView.Adapter<RecentSearchMediaAdapter.ViewHolder>() {

    class ViewHolder(val binding: MovievortexRecentSearchCardBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = MovievortexRecentSearchCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.apply {
            cardTitle.text = item.title
            cardSubtitle.text = item.type
            if (item.posterUrl.isNotBlank()) {
                cardPoster.loadImage(item.posterUrl)
            }
            root.setOnClickListener {
                onClick(item)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newItems: List<RecentMediaItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}

class StarCastAdapter(
    private var items: List<StarCastItem> = emptyList(),
    private val onClick: (StarCastItem) -> Unit
) : RecyclerView.Adapter<StarCastAdapter.ViewHolder>() {

    class ViewHolder(val binding: MovievortexStarCastCardBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = MovievortexStarCastCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.apply {
            actorName.text = item.name
            if (item.role.isNotBlank()) {
                actorRole.isVisible = true
                actorRole.text = item.role
            } else {
                actorRole.isVisible = false
            }
            if (item.profileUrl.isNotBlank()) {
                actorImage.loadImage(item.profileUrl)
            }
            root.setOnClickListener {
                onClick(item)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newItems: List<StarCastItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
