package com.qqswitcher.demo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 账号列表适配器
 */
class AccountAdapter(
    private val accounts: MutableList<MainActivity.QQAccount>,
    private val onClick: (MainActivity.QQAccount) -> Unit
) : RecyclerView.Adapter<AccountAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val account = accounts[position]
        holder.text1.text = account.nick ?: account.uin
        holder.text2.text = "QQ: ${account.uin}"
        holder.itemView.setOnClickListener { onClick(account) }
    }

    override fun getItemCount() = accounts.size

    /**
     * 添加测试账号（用于演示）
     */
    fun addTestAccount(account: MainActivity.QQAccount) {
        accounts.add(account)
        notifyItemInserted(accounts.size - 1)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text1: TextView = itemView.findViewById(android.R.id.text1)
        val text2: TextView = itemView.findViewById(android.R.id.text2)
    }
}
