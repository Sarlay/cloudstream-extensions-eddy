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
    private var defaulmac_adresse = "mac=00:1A:79:aa:53:65"
    private val defaultmainUrl = "http://ky-iptv.com:25461/portalstb"
    var defaultname = "ky-iptv |${lang.uppercase()}|"
    var Basename = "Box Iptv"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override val supportedTypes =
        setOf(TvType.Live) // live

    private var key: String? = "" // key used in the header
    private val arraymediaPlaylist =
        ArrayList<Channel>() // store all channels for the search function
    var allCategory =
        arrayListOf<String>() // even if we don't display all countries or categories, we need to know those avalaible
    var isNotInit = true

    init {
        name = Basename + " |${lang.uppercase()}|"
    }

    /**
    check if the data are incorrect
     **/
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
            "$mainUrl/portal.php?type=stb&action=handshake&token=&JsHttpRequest=1-xml"
        val reponseGetkey = app.get(
            url_key, headers = mapOf(
                "Cookie" to adresseMac,
                "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
                "X-User-Agent" to "Model: MAG250; Link: WiFi",

                )
        )
        key = tryParseJson<Getkey>(
            Regex("""\{\"js\"(.*[\r\n]*)+\}""").find(reponseGetkey.text)?.groupValues?.get(0)
        )?.js?.token ?: ""
    }

    /**
    From user account, get the tags (to select categories and countries), url , mac address ... if there are avalaible
     **/
    private suspend fun getAuthHeader() {
        tags = tags ?: "" // tags will allow to select more contains
        if (tags?.uppercase()?.trim() == "NONE" || tags?.isBlank() == true) tags = lang
        tags = tags?.uppercase()
        mainUrl = overrideUrl.toString()
        mainUrl = when { // the "c" is not needed and some provider doesn't work with it
            mainUrl.endsWith("/c/") -> mainUrl.dropLast(3)
            mainUrl.endsWith("/c") -> mainUrl.dropLast(2)
            mainUrl.endsWith("/") -> mainUrl.dropLast(1)
            else -> mainUrl
        }
        headerMac = when {
            accountInfoNotGood(mainUrl, loginMac) -> { // do this if mainUrl or mac adresse is blank
                mainUrl = defaultmainUrl
                name = defaultname
                getkey(defaulmac_adresse)
                if (key?.isNotBlank() == true) {
                    mutableMapOf(
                        "Cookie" to defaulmac_adresse,
                        "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
                        "Authorization" to "Bearer $key",
                    )
                } else {
                    mutableMapOf(
                        "Cookie" to defaulmac_adresse,
                        "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
                    )
                }
            }
            else -> {
                name = (companionName ?: Basename) + " |${lang.uppercase()}|"
                getkey(loginMac.toString())
                if (key?.isNotBlank() == true) {
                    defaulmac_adresse = "mac=$loginMac"
                    mutableMapOf(
                        "Cookie" to "mac=$loginMac",
                        "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
                        "Authorization" to "Bearer $key",
                    )
                } else {
                    defaulmac_adresse = "mac=$loginMac"
                    mutableMapOf(
                        "Cookie" to "mac=$loginMac",
                        "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
                    )
                }
            }
        }

        app.get(
            "$mainUrl/portal.php?type=stb&action=get_modules",
            headers = headerMac
        ) // some providers need this request to work
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
                val name = cleanTitle(it.title).replace(findKeyWord(lang), "").trim()
                -FuzzySearch.ratio(name.lowercase(), query.lowercase())
            }
        }
    }


    override suspend fun search(query: String): List<SearchResponse> {
        return arraymediaPlaylist.sortByname(query).take(50)
            .map { media ->   // display only the 50 results
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
        when {
            url.contains("I_Need_Help0") -> { // how to create an iptv account
                val media =
                    parseJson<Channel>(url.replace(mainUrl, "").replace("I_Need_Help0&", ""))
                return LiveStreamLoadResponse(
                    name = "GO TO CREATE YOUR IPTV ACCOUNT",
                    url = media.toJson(),
                    apiName = this.name,
                    dataUrl = media.url,
                    posterUrl = "https://www.toutestpossible.be/wp-content/uploads/2017/05/comment-faire-des-choix-eclaires-en-10-etapes-01-300x167.jpg",
                    plot = "Find a site where there are IPTV stb/stalker accounts (url + mac address) and create your account",
                )
            }
            url.contains("I_Need_Help1") -> { // go to see all the avalaible tags
                val media =
                    parseJson<Channel>(url.replace(mainUrl, "").replace("I_Need_Help1&", ""))
                return LiveStreamLoadResponse(
                    name = "GO TO CREATE YOUR \uD83C\uDDF9\u200B\u200B\u200B\u200B\u200B\uD83C\uDDE6\u200B\u200B\u200B\u200B\u200B\uD83C\uDDEC\u200B\u200B\u200B\u200B\u200B ACCOUNT e.g. italia|sport|crime|uk ",
                    url = media.toJson(),
                    apiName = this.name,
                    dataUrl = media.url,
                    posterUrl = "https://www.toutestpossible.be/wp-content/uploads/2017/05/comment-faire-des-choix-eclaires-en-10-etapes-01-300x167.jpg",
                    plot = "ALL TAGS \uD83D\uDD0E ${allCategory.sortedBy { it }}",
                )
            }
            url.contains("There_is_an_error") -> { // case where the provider don't work
                return LiveStreamLoadResponse(
                    name = "\uD83C\uDDF5\u200B\u200B\u200B\u200B\u200B\uD83C\uDDF7\u200B\u200B\u200B\u200B\u200B\uD83C\uDDF4\u200B\u200B\u200B\u200B\u200B\uD83C\uDDE7\u200B\u200B\u200B\u200B\u200B\uD83C\uDDF1\u200B\u200B\u200B\u200B\u200B\uD83C\uDDEA\u200B\u200B\u200B\u200B\u200B\uD83C\uDDF2\u200B\u200B\u200B\u200B\u200B",
                    url = url,
                    apiName = this.name,
                    dataUrl = url,
                    posterUrl = "https://www.toutestpossible.be/wp-content/uploads/2017/05/comment-faire-des-choix-eclaires-en-10-etapes-01-300x167.jpg",
                    plot = "There is an issue with this account. Please check your credentials or change your DNS or use a VPN. Otherwise try with another account",
                    comingSoon = true
                )
            }
            else -> {
                val media = parseJson<Channel>(url.replace(mainUrl, ""))
                val epg_url =
                    "$mainUrl/portal.php?type=itv&action=get_short_epg&ch_id=${media.ch_id}&size=10&JsHttpRequest=1-xml" // the plot when it's avalaible
                val response = app.get(epg_url, headers = headerMac)
                val description = getEpg(response.text)
                val link = media.url
                val title = media.title
                val a = cleanTitle(title).replace(findKeyWord(lang), "").trim()
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
                        val b = cleanTitle(channel.title).replace(findKeyWord(lang), "").trim()
                        b_new = b.take(6)
                        if (channel.id != media.id && a.take(6)
                                .contains(b_new) && media.tv_genre_id == channel.tv_genre_id
                        ) {
                            val streamurl = channel.toJson()
                            val channelname = channel.title
                            val posterurl = channel.url_image.toString()
                            val uppername = channelname.uppercase()
                            val quality = getQualityFromString(
                                when (channelname.isNotBlank()) {
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
                                    findKeyWord(lang),
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

        }

    }


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
                    val chID =
                        Regex("""\/(\d*)\?""").find(request.url.toString())!!.groupValues[1] + "_"
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.contains("githubusercontent") && data.contains(".mp4")) {
            callback.invoke(
                ExtractorLink(
                    name,
                    "TUTO",
                    data,
                    "",
                    Qualities.Unknown.value,
                )
            )
            return true
        }
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


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (isNotInit || arraymediaPlaylist.isEmpty()) {
            if (isNotInit) getAuthHeader()
            val rgxFindJson =
                Regex("""\{\"js\"(.*[\r\n]*)+\}""") // select only the json part, some account give a bad json response
            var responseAllchannels: NiceResponse? = null
            listOf(
                "$mainUrl/portal.php?type=account_info&action=get_main_info&JsHttpRequest=1-xml&$defaulmac_adresse", // GET EXPIRATION
                "$mainUrl/portal.php?type=itv&action=get_genres", // GET ALL GENRES (Countries or categories)
                "$mainUrl/portal.php?type=itv&action=get_all_channels", // GET ALL LIVE CAHNNELS
            ).apmap { url ->
                when {

                    url.contains("action=get_main_info") -> {
                        val textJson = app.get(
                            url,
                            headers = headerMac
                        ).text
                        expiration = tryParseJson<GetExpiration>(
                            rgxFindJson.find(
                                textJson
                            )?.groupValues?.get(0)
                        )?.js?.phone.toString()
                    }
                    url.contains("action=get_genre") -> {
                        responseGetGenretoJSON =
                            tryParseJson<JsonGetGenre>(
                                rgxFindJson.find(
                                    app.get(
                                        url,
                                        headers = headerMac
                                    ).text
                                )?.groupValues?.get(0)
                            )?.js
                                ?: arrayListOf(Js_category("0", ".*"))
                    }
                    url.contains("action=get_all_channels") -> {
                        responseAllchannels = app.get(url, headers = headerMac)
                    }
                    else -> {
                        ErrorLoadingException("url not good")
                    }
                }
            }
            val allDataJson =
                tryParseJson<Root>(
                    rgxFindJson.find(responseAllchannels?.text.toString())?.groupValues?.get(
                        0
                    )
                )?.js?.data?.sortByTitleNumber() //use of tryPaseJson to be able to select the json part because sometime the json response is not good
            if (!allDataJson.isNullOrEmpty()) {
                return HomePageResponse(
                    HomeResponse(
                        allDataJson,
                    ).getHomePageListsInit(this), false
                )
            } else {
                return HomePageResponse(
                    listOf(
                        HomePageList(
                            "\uD83C\uDDF5\u200B\u200B\u200B\u200B\u200B\uD83C\uDDF7\u200B\u200B\u200B\u200B\u200B\uD83C\uDDF4\u200B\u200B\u200B\u200B\u200B\uD83C\uDDE7\u200B\u200B\u200B\u200B\u200B\uD83C\uDDF1\u200B\u200B\u200B\u200B\u200B\uD83C\uDDEA\u200B\u200B\u200B\u200B\u200B\uD83C\uDDF2\u200B\u200B\u200B\u200B\u200B",
                            listOf(
                                LiveSearchResponse(
                                    "Click to see the tips",
                                    "${mainUrl}There_is_an_error", // trick to load the help page
                                    name,
                                    TvType.Live,
                                    "https://bodhisattva4you.files.wordpress.com/2014/11/esprit-probleme-00.jpg",
                                ),
                                LiveSearchResponse(
                                    "Click to see how to create an account",
                                    "${mainUrl}${
                                        Channel(
                                            "\uD83D\uDC64 \uD83C\uDD78\uD83C\uDD7F\uD83C\uDD83\uD83C\uDD85 Account",
                                            "I_Need_Help0&https://user-images.githubusercontent.com/47984460/204648696-1f7d18b8-6bf7-4392-9269-32c7b0e97403.mp4", // trick to load the help page
                                            "https://userguiding.com/wp-content/uploads/2021/06/best-help-center-software.jpg",
                                            "",
                                            "000976000",
                                            "",
                                            "000976000"
                                        ).toJson()
                                    }", // trick to load the help page
                                    name,
                                    TvType.Live,
                                    "https://userguiding.com/wp-content/uploads/2021/06/best-help-center-software.jpg",
                                )
                            ),
                            isHorizontalImages = true
                        )
                    ), false
                )
            }

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
            val rgxcodeCountry = findKeyWord(provider.lang)
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

                val arraychannel =
                    mutableListOf<Channel>() // toMutable because HomePageList need to remove element from the list
                if (idGenre.contains("""\d+""".toRegex()) && (categoryTitle.uppercase()
                        .contains(rgxcodeCountry) ||
                            categoryTitle.isContainsTargetCountry(provider)
                            ) || categoryTitle.uppercase()
                        .contains(findKeyWord(tags.toString()))
                ) {
                    val itr = channels.iterator()
                    while (itr.hasNext()) { // Remove elements from a list while iterating
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
                    if (arraychannel.isNotEmpty()) {
                        arraychannel.toHomePageList(nameGenre, provider, idGenre)
                    } else {
                        null
                    }


                } else {
                    null
                }
            }

        }

        fun getHomePageListsFromArrayChannel(provider: MacIPTVProvider): List<HomePageList> {
            val rgxcodeCountry = findKeyWord(provider.lang)
            var firstCat = true
            val arrayChannel =
                provider.arraymediaPlaylist.toMutableList() // toMutable because HomePageList need to remove element from the list
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

        /** Since I don't select all the content because it is too big, I want to at least display the countries and categories available.
         * So the user will know what is available and can select his own country or categories via the account creation tag */
        fun getHelpHomePage(provider: MacIPTVProvider): List<HomePageList> {
            val helpCat =
                "ℹ️\uD83C\uDDED\u200B\u200B\u200B\u200B\u200B\uD83C\uDDEA\u200B\u200B\u200B\u200B\u200B\uD83C\uDDF1\u200B\u200B\u200B\u200B\u200B\uD83C\uDDF5\u200B\u200B\u200B\u200B\u200B"
            val idHelp = "0097600"
            return arrayListOf(
                arrayListOf(
                    Channel(
                        "\uD83D\uDC64 \uD83C\uDD78\uD83C\uDD7F\uD83C\uDD83\uD83C\uDD85 Account",
                        "I_Need_Help0&https://user-images.githubusercontent.com/47984460/204648696-1f7d18b8-6bf7-4392-9269-32c7b0e97403.mp4", // trick to load the help page
                        "https://userguiding.com/wp-content/uploads/2021/06/best-help-center-software.jpg",
                        "",
                        "000976000",
                        idHelp,
                        "000976000"
                    ),
                    Channel(
                        "\uD83D\uDD0E Your TAG Account",
                        "I_Need_Help1&https://user-images.githubusercontent.com/47984460/204643246-405e7a7b-544e-4389-a78e-395c3876e06d.mp4", // trick to load the help page
                        "https://userguiding.com/wp-content/uploads/2021/06/best-help-center-software.jpg",
                        "",
                        "000976100",
                        idHelp,
                        "000976100"
                    )
                ).toHomePageList(
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
            val sequence = when (upperSTR) {
                "EN" -> {
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

        fun MutableList<Channel>.toSearchResponseHomePage( // require mutableList to be able to remove elements from the list while iterating
            provider: MacIPTVProvider, GenreId: String
        ): List<SearchResponse> {
            val groupChannel = ArrayList<String>()
            var b_new: String
            var newgroupChannel: Boolean
            val rgxcodeCountry = findKeyWord(provider.lang)
            val home = ArrayList<SearchResponse>()
            val itr = this.iterator()
            while (itr.hasNext()) { //permit to remove elements from the list while iterating
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
                    itr.remove() //remove elements
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