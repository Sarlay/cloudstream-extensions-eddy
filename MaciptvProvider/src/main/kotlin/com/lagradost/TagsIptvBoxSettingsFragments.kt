package com.lagradost

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.lagradost.cloudstream3.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.ui.settings.SettingsAccount.Companion.showLoginInfo
import com.lagradost.cloudstream3.ui.settings.SettingsAccount.Companion.addAccount


class TagsIptvBoxSettingsFragments(private val plugin: Plugin, val tagsmaciptvAPI: TagsMacIptvAPI) :
    BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val id = plugin.resources!!.getIdentifier(
            "iptvbox_settings",
            "layout",
            "BuildConfig.LIBRARY_PACKAGE_NAME"
        )
        val layout = plugin.resources!!.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    private fun <T : View> View.findView(name: String): T {
        val id = plugin.resources!!.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return this.findViewById(id)
    }

    private fun getDrawable(name: String): Drawable? {
        val id =
            plugin.resources!!.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return ResourcesCompat.getDrawable(plugin.resources!!, id, null)
    }

    private fun getString(name: String): String? {
        val id =
            plugin.resources!!.getIdentifier(name, "string", BuildConfig.LIBRARY_PACKAGE_NAME)
        return plugin.resources!!.getString(id)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val infoView = view.findView<LinearLayout>("tags_info")
        val infoTextView = view.findView<TextView>("tagsinfo_main_text")
        val infoSubTextView = view.findView<TextView>("tags_sub_text")

        infoTextView.text = getString("tags_info_title") ?: "MacIPTV"
        infoSubTextView.text = getString("tags_info_summary") ?: ""


        val loginView = view.findView<LinearLayout>("tags_login")
        val loginTextView = view.findView<TextView>("tagsmain_text")

        // object : View.OnClickListener is required to make it compile because otherwise it used invoke-customs
        infoView.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                println("It's OK")
            }
        })


        loginTextView.text = view.context.resources.getString(R.string.login_format).format(
            tagsmaciptvAPI.name,
            view.context.resources.getString(R.string.account)
        )

        loginView.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                val info = tagsmaciptvAPI.loginInfo()
                if (info != null) {
                    showLoginInfo(activity, tagsmaciptvAPI, info)
                } else {
                    addAccount(activity, tagsmaciptvAPI)
                }
            }
        })
    }
}