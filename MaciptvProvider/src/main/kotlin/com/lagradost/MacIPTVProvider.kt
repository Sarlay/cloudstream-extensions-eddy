package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import okhttp3.Interceptor
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.runBlocking
import me.xdrop.fuzzywuzzy.FuzzySearch

class MacIPTVProvider(override var lang: String) : MainAPI() {
    private val defaulmac_adresse =
        "mac=00:1A:79:aa:53:65"
    private val defaultmainUrl =
        "http://ky-iptv.com:25461/portalstb"
    var defaultname = "ky-iptv |${lang.uppercase()}|"
    var Basename = "Box Iptv"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override val supportedTypes =
        setOf(TvType.Live) // live

    private var key: String? = ""

    init {
        name = Basename + " |${lang.uppercase()}|"
    }

    private fun accountInfoNotGood(url: String, mac: String?): Boolean {
        return url.uppercase().trim() == "NONE" || url.isBlank() || mac?.uppercase()
            ?.trim() == "NONE" || mac.isNullOrBlank()
    }

    /**
    Sometimes providers ask a key (token) in the headers
     **/
    private suspend fun getkey(mac: String) {
        val adresseMac = if (!mac.contains("mac=")) {
            "mac=$mac"
        } else {
            mac
        }
        val url_key =
            "$mainUrl/portal.php?type=stb&action=handshake&JsHttpRequest=1-xml"
        val reponseGetkey = app.get(
            url_key, headers = mapOf(
                "Cookie" to adresseMac,
                "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
            )
        )
        val keyJson = reponseGetkey.parsed<Getkey>()
        key = keyJson.js?.token
    }


    private suspend fun getAuthHeader() {
        tags = tags ?: "" // tags will allow to select more contains
        if (tags?.uppercase()?.trim() == "NONE" || tags?.isBlank() == true) tags = lang
        tags = tags?.uppercase()
        mainUrl = overrideUrl.toString()
        headerMac = when {
            accountInfoNotGood(mainUrl, loginMac) -> { // do this if mainUrl or mac adresse is blank
                mainUrl = defaultmainUrl
                name = defaultname
                getkey(defaulmac_adresse)
                mutableMapOf(
                    "Cookie" to defaulmac_adresse,
                    "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
                    "Authorization" to "Bearer $key",
                )
            }
            else -> {
                name = (companionName ?: Basename) + " |${lang.uppercase()}|"
                getkey(loginMac.toString())
                mutableMapOf(
                    "Cookie" to "mac=$loginMac",
                    "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
                    "Authorization" to "Bearer $key",
                )
            }
        }
        isNotInit = false
    }


    data class Channel(
        var title: String,
        var url: String,
        val url_image: String?,
        val lang: String?,
        var id: String?,
        var tv_genre_id: String?,
        var ch_id: String?,
    )


    private fun List<Channel>.sortByname(query: String?): List<Channel> {
        return if (query == null) {
            // Return list to base state if no query
            this.sortedBy { it.title }
        } else {

            this.sortedBy {
                val name = cleanTitle(it.title).replace(rgxcodeCountry, "").trim()
                -FuzzySearch.ratio(name.lowercase(), query.lowercase())
            }
        }
    }


    private val resultsSearchNbr = 50 // show only the 50 results
    override suspend fun search(query: String): List<SearchResponse> {
        return arraymediaPlaylist.sortByname(query).take(resultsSearchNbr).map { media ->
            LiveSearchResponse(
                media.title,
                media.toJson(),
                name,
                TvType.Live,
                media.url_image,
            )
        }
    }

    data class Root_epg(
        @JsonProperty("js") var js: ArrayList<Js_epg> = arrayListOf()
    )

    data class Js_epg(
        @JsonProperty("name") var name: String? = null,
        @JsonProperty("descr") var descr: String? = null,
        @JsonProperty("t_time") var tTime: String? = null,
        @JsonProperty("t_time_to") var tTimeTo: String? = null,
    )

    private fun getEpg(response: String): String { // get the EPG to have get the schedual
        val reponseJSON_0 = tryParseJson<Root_epg>(response)
        var description = ""
        reponseJSON_0?.js?.forEach { epg_i ->
            val name = epg_i.name
            val descr = epg_i.descr
            val t_time = epg_i.tTime
            val t_time_to = epg_i.tTimeTo
            val new_descr = "||($t_time -> $t_time_to)  $name : $descr"
            if (!description.contains(new_descr)) {
                description = "$description\n $new_descr"
            }

        }
        return description
    }


    override suspend fun load(url: String): LoadResponse {
        if (url.contains("I_Need_Help")) {
            return LiveStreamLoadResponse(
                name = "GO TO CREATE YOUR \uD83C\uDDF9\u200B\u200B\u200B\u200B\u200B\uD83C\uDDE6\u200B\u200B\u200B\u200B\u200B\uD83C\uDDEC\u200B\u200B\u200B\u200B\u200B ACCOUNT => italia|sport|crime|uk ",
                url = url,
                apiName = this.name,
                dataUrl = url,
                posterUrl = "https://www.toutestpossible.be/wp-content/uploads/2017/05/comment-faire-des-choix-eclaires-en-10-etapes-01-300x167.jpg",
                plot = "ALL TAGS \uD83D\uDD0E ${allCategory.sortedBy { it }}",
                comingSoon = true
            )
        }
        val media = parseJson<Channel>(url.replace(mainUrl, ""))
        val epg_url =
            "$mainUrl/portal.php?type=itv&action=get_short_epg&ch_id=${media.ch_id}&size=10&JsHttpRequest=1-xml" // plot
        val response = app.get(epg_url, headers = headerMac)
        val description = getEpg(response.text)
        val link = media.url
        val title = media.title
        val a = cleanTitle(title).replace(rgxcodeCountry, "").trim()
        val posterUrl = media.url_image.toString()
        var b_new: String

        return LiveStreamLoadResponse(
            name = title,
            url = media.toJson(),
            apiName = this.name,
            dataUrl = link,
            posterUrl = posterUrl,
            plot = description,
            recommendations = arraymediaPlaylist.mapNotNull { channel ->
                val b = cleanTitle(channel.title).replace(rgxcodeCountry, "").trim()
                b_new = b.take(6)
                if (channel.id != media.id && a.take(6)
                        .contains(b_new) && media.tv_genre_id == channel.tv_genre_id
                ) {
                    val streamurl = channel.toJson()
                    val channelname = channel.title
                    val posterurl = channel.url_image.toString()
                    val uppername = channelname.uppercase()
                    val quality = getQualityFromString(
                        when (!channelname.isBlank()) {
                            uppername.contains(findKeyWord("UHD")) -> {
                                "UHD"
                            }
                            uppername.contains(findKeyWord("HD")) -> {
                                "HD"
                            }
                            uppername.contains(findKeyWord("SD")) -> {
                                "SD"
                            }
                            uppername.contains(findKeyWord("FHD")) -> {
                                "HD"
                            }
                            uppername.contains(findKeyWord("4K")) -> {
                                "FourK"
                            }

                            else -> {
                                null
                            }
                        }
                    )
                    LiveSearchResponse(
                        name = cleanTitleButKeepNumber(channelname).replace(
                            rgxcodeCountry,
                            ""
                        ).trim(),
                        url = streamurl,
                        name,
                        TvType.Live,
                        posterUrl = posterurl,
                        quality = quality,
                    )
                } else {
                    null
                }
            }
        )

    }

    /** get the channel id */
    val regexCode_ch = Regex("""\/(\d*)\?""")

    /**
     * Use new token.
     * */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        // Needs to be object instead of lambda to make it compile correctly
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
                val request = chain.request()
                if (request.url.toString().contains("token")

                ) {
                    val chID = regexCode_ch.find(request.url.toString())!!.groupValues[1] + "_"
                    val TokenLink =
                        "$mainUrl/portal.php?type=itv&action=create_link&cmd=ffmpeg%20http://localhost/ch/$chID&series=&forced_storage=0&disable_ad=0&download=0&force_ch_link_check=0&JsHttpRequest=1-xml"

                    var link: String
                    var lien: String
                    runBlocking {
                        val getTokenLink = app.get(TokenLink, headers = headerMac).text
                        val regexGetLink = Regex("""(http.*)\"\},""")
                        link = regexGetLink.find(getTokenLink)?.groupValues?.get(1).toString()
                            .replace(
                                """\""",
                                ""
                            )
                        lien = link
                        if (link.contains("extension")) {
                            val headerlocation = app.get(
                                link,
                                allowRedirects = false
                            ).headers
                            val redirectlink = headerlocation.get("location")
                                .toString()
                            if (redirectlink != "null") {
                                lien = redirectlink
                            }
                        }
                    }

                    val newRequest = chain.request()
                        .newBuilder().url(lien).build()
                    return chain.proceed(newRequest)
                } else {
                    return chain.proceed(chain.request())
                }
            }
        }
    }

    /** récupere les liens .mp4 ou m3u8 directement à partir du paramètre data généré avec la fonction load()**/
    override suspend fun loadLinks(
        data: String, // fournit par load()
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val TokenLink =
            "$mainUrl/portal.php?type=itv&action=create_link&cmd=ffmpeg%20$data&series=&forced_storage=0&disable_ad=0&download=0&force_ch_link_check=0&JsHttpRequest=1-xml"
        val getTokenLink = app.get(TokenLink, headers = headerMac).text
        val regexGetLink = Regex("""(http.*)\"\},""")
        val link =
            regexGetLink.find(getTokenLink)?.groupValues?.get(1).toString().replace("""\""", "")


        val head = mapOf(
            "Accept" to "*/*",
            "Accept-Language" to "en_US",
            "User-Agent" to "VLC/3.0.18 LibVLC/3.0.18",
            "Range" to "bytes=0-"
        )


        val lien = when {
            link.contains("extension") -> {
                val headerlocation = app.get(
                    link,
                    allowRedirects = false
                ).headers
                val redirectlink = headerlocation.get("location")
                    .toString()
                if (redirectlink != "null") {
                    redirectlink
                } else {
                    link
                }
            }
            else -> link
        }
        val isM3u8 = false
        callback.invoke(
            ExtractorLink(
                name,
                name,
                lien,
                mainUrl,
                Qualities.Unknown.value,
                isM3u8 = isM3u8,
                headers = head
            )
        )
        return true
    }


    private val arraymediaPlaylist = ArrayList<Channel>()


    data class Cmds(
        @JsonProperty("ch_id") var chId: String? = null,
    )

    data class Data(
        @JsonProperty("id") var id: String? = null,
        @JsonProperty("name") var name: String? = null,
        @JsonProperty("number") var number: String? = null,
        @JsonProperty("tv_genre_id") var tvGenreId: String? = null,
        @JsonProperty("logo") var logo: String? = null,
        @JsonProperty("cmds") var cmds: ArrayList<Cmds> = arrayListOf(),
    )

    data class JsKey(
        @JsonProperty("token") var token: String? = null
    )

    data class Getkey(
        @JsonProperty("js") var js: JsKey? = JsKey()
    )

    data class JsInfo(
        @JsonProperty("mac") var mac: String? = null,
        @JsonProperty("phone") var phone: String? = null
    )

    data class GetExpiration(
        @JsonProperty("js") var js: JsInfo? = JsInfo()
    )

    data class Js(
        @JsonProperty("total_items") var totalItems: Int? = null,
        @JsonProperty("max_page_items") var maxPageItems: Int? = null,
        @JsonProperty("data") var data: ArrayList<Data> = arrayListOf()
    )

    data class JsonGetGenre(
        @JsonProperty("js") var js: ArrayList<Js_category> = arrayListOf()
    )

    data class Js_category(
        @JsonProperty("id") var id: String? = null,
        @JsonProperty("title") var title: String? = null,
    )

    data class Root(
        @JsonProperty("js") var js: Js? = Js()
    )

    private var codeCountry = lang
    var allCategory = mutableListOf<String>()
    val rgxcodeCountry = findKeyWord(codeCountry)
    var isNotInit = true
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (isNotInit || arraymediaPlaylist.isEmpty()) {
            if (isNotInit) getAuthHeader()

            var responseAllchannels: NiceResponse? = null
            listOf(
                "$mainUrl/portal.php?type=account_info&action=get_main_info&JsHttpRequest=1-xml",
                "$mainUrl/portal.php?type=itv&action=get_genres&JsHttpRequest=1-xml",
                "$mainUrl/portal.php?type=itv&action=get_all_channels&JsHttpRequest=1-xml",
            ).apmap { url ->
                when {

                    url.contains("action=get_main_info") -> {
                        expiration = app.get(url, headers = headerMac)
                            .parsed<GetExpiration>().js?.phone.toString() // GET EXPIRATION
                    }
                    url.contains("action=get_genre") -> {
                        responseGetGenretoJSON = app.get(url, headers = headerMac)
                            .parsed<JsonGetGenre>().js // GET ALL GENRES
                    }
                    url.contains("action=get_all_channels") -> {
                        responseAllchannels = app.get(url, headers = headerMac)
                    }
                    else -> {
                        ErrorLoadingException("url not good")
                    }
                }
            }

            return HomePageResponse(
                HomeResponse(
                    responseAllchannels!!.parsed<Root>().js!!.data.sortByTitleNumber(),// GET ALL CHANNELS and Sort byTitle
                ).getHomePageListsInit(this), false
            )
        } else {
            return HomePageResponse(
                HomeResponse().getHomePageListsFromArrayChannel(this), false
            )
        }
    }


    fun ArrayList<Data>.sortByTitleNumber(): ArrayList<Data> {
        val regxNbr = Regex("""(\s\d{1,}${'$'}|\s\d{1,}\s)""")
        return ArrayList(this.sortedBy {
            val str = it.name.toString()
            regxNbr.find(str)?.groupValues?.get(0)?.trim()?.toInt() ?: 1000
        })
    }


    private data class HomeResponse(
        val channels: MutableList<Data> = ArrayList(),
    ) {
        fun String.isContainsTargetCountry(provider: MacIPTVProvider): Boolean {
            val getLang = provider.lang.uppercase()
            val langs = hashMapOf(
                "FR" to arrayListOf("FRENCH", "FRANCE"),
                "EN" to arrayListOf("ENGLISH", "USA"),
                "AR" to arrayListOf("ARABIC", "ARAB", "ARABIA")
            )
            return langs[getLang]?.any { this.uppercase().contains(findKeyWord(it)) } ?: false
        }


        fun getHomePageListsInit(provider: MacIPTVProvider): List<HomePageList> {
            val rgxcodeCountry = provider.rgxcodeCountry
            var firstCat = true
            provider.allCategory.clear()
            
            return getHelpHomePage(provider) + responseGetGenretoJSON.mapNotNull { js ->
                val idGenre = js.id.toString()
                val categoryTitle = js.title.toString()
                cleanTitle(
                    categoryTitle.replace("&", "").replace(",", "").replace(":", "")
                        .replace("#", "").replace("|", "").replace("*", "").replace("/", "")
                        .replace("\\", "").replace("[", "").replace("]", "")
                        .replace("(", "").replace(")", "")
                ).split("""\s+""".toRegex()).forEach { titleC ->
                    if (!provider.allCategory.any { it.contains(titleC, true) }) {
                        provider.allCategory.add("|$titleC|")
                    }
                }

                val arraychannel = mutableListOf<Channel>()
                if (channels.isNotEmpty() && idGenre.contains("""\d""".toRegex()) && (categoryTitle.uppercase()
                        .contains(rgxcodeCountry) ||
                            categoryTitle.isContainsTargetCountry(provider)
                            ) || categoryTitle.uppercase()
                        .contains(findKeyWord(tags.toString()))
                ) {
                    val itr = channels.iterator()
                    while (itr.hasNext()) {
                        val data = itr.next()
                        val genre = data.tvGenreId
                        if (genre != null) {
                            if (genre == idGenre) {
                                itr.remove()
                                val name = data.name.toString()
                                val tv_genre_id = data.tvGenreId
                                val idCH = data.id
                                val link = "http://localhost/ch/$idCH" + "_"
                                val logo = data.logo?.replace("""\""", "")
                                val ch_id = data.cmds[0].chId
                                arraychannel.add(
                                    Channel(
                                        name,
                                        link,
                                        logo,
                                        "",
                                        idCH,
                                        tv_genre_id,
                                        ch_id
                                    )
                                )
                                provider.arraymediaPlaylist.add(
                                    Channel(
                                        name,
                                        link,
                                        logo,
                                        "",
                                        idCH,
                                        tv_genre_id,
                                        ch_id
                                    )
                                )
                            }
                        }
                    }

                    val flag = getFlag(categoryTitle)
                    val nameGenre = if (firstCat) {
                        firstCat = false
                        "$flag ${
                            cleanTitle(categoryTitle).replace(rgxcodeCountry, "").trim()
                        } \uD83D\uDCFA ${provider.name} \uD83D\uDCFA (⏳ $expiration)"
                    } else {
                        "$flag ${cleanTitle(categoryTitle).replace(rgxcodeCountry, "").trim()}"
                    }

                    arraychannel.toHomePageList(nameGenre, provider, idGenre)

                } else {
                    null
                }
            }

        }

        fun getHomePageListsFromArrayChannel(provider: MacIPTVProvider): List<HomePageList> {
            val rgxcodeCountry = provider.rgxcodeCountry
            var firstCat = true
            val arrayChannel = provider.arraymediaPlaylist.toMutableList()
            return getHelpHomePage(provider) + responseGetGenretoJSON.mapNotNull { js ->
                val idGenre = js.id.toString()
                val categoryTitle = js.title.toString()
                val flag: String
                if (categoryTitle.uppercase()
                        .contains(rgxcodeCountry) || categoryTitle.isContainsTargetCountry(
                        provider
                    ) || categoryTitle.uppercase()
                        .contains(findKeyWord(tags.toString()))
                ) {
                    flag = getFlag(categoryTitle)
                    val nameGenre = if (firstCat) {
                        firstCat = false
                        "$flag ${
                            cleanTitle(categoryTitle).replace(rgxcodeCountry, "").trim()
                        } \uD83D\uDCFA ${provider.name} \uD83D\uDCFA (⏳ $expiration)"
                    } else {
                        "$flag ${
                            cleanTitle(categoryTitle).replace(rgxcodeCountry, "").trim()
                        }"
                    }
                    arrayChannel.toHomePageList(
                        nameGenre,
                        provider,
                        idGenre
                    )
                } else {
                    null
                }
            }
        }

        fun getHelpHomePage(provider: MacIPTVProvider): List<HomePageList> {
            val arraychannel = mutableListOf<Channel>()
            val helpCat =
                "\uD83C\uDDF9\u200B\u200B\u200B\u200B\u200B\uD83C\uDDE6\u200B\u200B\u200B\u200B\u200B\uD83C\uDDEC\u200B\u200B\u200B\u200B\u200B Account"
            arraychannel.add(
                Channel(
                    "\uD83D\uDD0E TAG",
                    "${provider.mainUrl}I_Need_Help",
                    "https://userguiding.com/wp-content/uploads/2021/06/best-help-center-software.jpg",
                    "",
                    "000976000",
                    "0097600",
                    ""
                )
            )
            return mutableListOf(
                arraychannel.toHomePageList(
                    helpCat,
                    provider,
                    "0097600"
                )
            )
        }

    }

    companion object {
        var companionName: String? = null
        var loginMac: String? = null
        var overrideUrl: String? = null
        var tags: String? = null
        private var headerMac = mutableMapOf<String, String>()
        var expiration: String? = null
        var responseGetGenretoJSON = ArrayList<Js_category>() // all genres from the provider
        fun findKeyWord(str: String): Regex {
            val upperSTR = str.uppercase()
            val sequence = when (true) {
                upperSTR == "EN" -> {
                    "US|UK"
                }
                else -> upperSTR
            }
            return """(?:^|\W+|\s)+($sequence)(?:\s|\W+|${'$'}|\|)+""".toRegex()
        }

        fun cleanTitleButKeepNumber(title: String): String {
            return title.uppercase().replace("""FHD""", "")
                .replace(findKeyWord("VIP"), "")
                .replace("""UHD""", "").replace("""HEVC""", "")
                .replace("""HDR""", "").replace("""SD""", "").replace("""4K""", "")
                .replace("""HD""", "")
        }

        fun getFlag(sequence: String): String {
            val FR = findKeyWord("FR|FRANCE|FRENCH")
            val US = findKeyWord("US|USA")
            val AR = findKeyWord("AR|ARAB|ARABIC|ARABIA")
            val UK = findKeyWord("UK")
            return when {
                sequence.uppercase()
                    .contains(FR) -> "\uD83C\uDDE8\uD83C\uDDF5"
                sequence.uppercase()
                    .contains(US) -> "\uD83C\uDDFA\uD83C\uDDF8"
                sequence.uppercase()
                    .contains(UK) -> "\uD83C\uDDEC\uD83C\uDDE7"
                sequence.uppercase()
                    .contains(AR) -> " نظرة"
                else -> ""
            }

        }

        fun cleanTitle(title: String): String {
            return cleanTitleButKeepNumber(title).replace(
                """(\s\d{1,}${'$'}|\s\d{1,}\s)""".toRegex(),
                " "
            )
        }

        fun MutableList<Channel>.toSearchResponseHomePage(
            provider: MacIPTVProvider, GenreId: String
        ): List<SearchResponse> {
            val groupChannel = ArrayList<String>()
            var b_new: String
            var newgroupChannel: Boolean
            val rgxcodeCountry = provider.rgxcodeCountry
            val home = ArrayList<SearchResponse>()
            val itr = this.iterator()
            while (itr.hasNext()) {
                val media = itr.next()
                val b = cleanTitle(media.title).replace(rgxcodeCountry, "").trim()
                b_new = b.take(6)
                newgroupChannel = true
                for (nameChannel in groupChannel) {
                    if (nameChannel.contains(b_new) && media.tv_genre_id == GenreId) {
                        newgroupChannel = false
                        break
                    }
                }
                if (newgroupChannel && media.tv_genre_id == GenreId) {
                    groupChannel.add(b_new)
                    val groupName = cleanTitle(media.title).replace(rgxcodeCountry, "").trim()
                    itr.remove()
                    home.add(
                        LiveSearchResponse(
                            groupName,
                            "${provider.mainUrl}${media.toJson()}",
                            provider.name,
                            TvType.Live,
                            media.url_image,
                        )
                    )


                }
            }

            return home
        }

        fun MutableList<Channel>.toHomePageList(
            name: String,
            provider: MacIPTVProvider,
            GenreId: String
        ) =
            HomePageList(
                name, this.toSearchResponseHomePage(provider, GenreId),
                isHorizontalImages = true
            )

    }
}

