package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import okhttp3.Interceptor
import com.lagradost.cloudstream3.utils.*
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
    override var name = "Box Iptv"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override val supportedTypes =
        setOf(TvType.Live) // live

    private var firstInitDone = false
    private var key: String? = ""


    private fun detectNewAccount(): Boolean {
        return oldAthMac != loginMac || oldAthUrl != overrideUrl
    }

    private fun accountInfoNotGood(url: String, mac: String?): Boolean {
        return url.uppercase().trim() == "NONE" || url.isBlank() || mac?.uppercase()
            ?.trim() == "NONE" || mac.isNullOrBlank()
    }

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


    init {
        name = (companionName ?: name) + " |${lang.uppercase()}|"
    }

    private suspend fun getAuthHeader() {
        if (tags.uppercase().trim() == "NONE" || tags.isBlank()) tags = lang
        tags = tags.uppercase()
        oldAthMac = loginMac
        oldAthUrl = overrideUrl
        mainUrl = overrideUrl.toString()
        headerMac = when (true) {
            accountInfoNotGood(mainUrl, loginMac) -> {
                mainUrl = defaultmainUrl
                name = defaultname
                if (!firstInitDone) {
                    getkey(defaulmac_adresse)
                    firstInitDone = true
                }

                mutableMapOf(
                    "Cookie" to defaulmac_adresse,
                    "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
                    "Authorization" to "Bearer $key",
                )
            }
            else -> {
                name = (companionName ?: name) + " |${lang.uppercase()}|"
                if (!firstInitDone) {
                    getkey(loginMac.toString())
                    firstInitDone = true
                }

                mutableMapOf(
                    "Cookie" to "mac=$loginMac",
                    "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
                    "Authorization" to "Bearer $key",
                )
            }
        }

    }


    data class Channel(
        var title: String,
        var url: String,
        val url_image: String?,
        val lang: String?,
        var id: String?,
        var tv_genre_id: String?,
        var ch_id: String?,
    ) {
        fun toStringCode(): String {
            return "${this.title}*${this.url}*${this.url_image}*${this.lang}*${this.id}*${this.tv_genre_id}*${this.ch_id}"
        }
    }


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


    /**
    Cherche le site pour un titre spécifique

    La recherche retourne une SearchResponse, qui peut être des classes suivants: AnimeSearchResponse, MovieSearchResponse, TorrentSearchResponse, TvSeriesSearchResponse
    Chaque classes nécessite des données différentes, mais a en commun le nom, le poster et l'url
     **/
    private val resultsSearchNbr = 50
    override suspend fun search(query: String): List<SearchResponse> {
        val searchResutls = ArrayList<SearchResponse>()
        arraymediaPlaylist.sortByname(query).take(resultsSearchNbr).forEach { media ->
            searchResutls.add(
                LiveSearchResponse(
                    media.title,
                    media.toStringCode(),
                    name,
                    TvType.Live,
                    media.url_image,
                )
            )
        }
        return searchResutls
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

    private fun getEpg(response: String): String {
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

    fun String.getDataChannelFromString(): Channel {
        val res = this.split("*")

        return Channel(res[0], res[1], res[2], res[3], res[4], res[5], res[6])
    }


    override suspend fun load(url: String): LoadResponse {
        val allresultshome: MutableList<SearchResponse> = mutableListOf()
        val media = url.replace(mainUrl, "").getDataChannelFromString()
        val epg_url =
            "$mainUrl/portal.php?type=itv&action=get_short_epg&ch_id=${media.ch_id}&size=10&JsHttpRequest=1-xml" // plot
        val response = app.get(epg_url, headers = headerMac)
        val description = getEpg(response.text)
        val link = media.url
        val title = media.title
        val a = cleanTitle(title).replace(rgxcodeCountry, "").trim()
        val posterUrl = media.url_image.toString()
        var b_new: String
        arraymediaPlaylist.forEach { channel ->
            val b = cleanTitle(channel.title).replace(rgxcodeCountry, "").trim()
            b_new = b.take(6)
            if (channel.id != media.id && a.take(6)
                    .contains(b_new) && media.tv_genre_id == channel.tv_genre_id
            ) {

                val streamurl = channel.toStringCode()
                val channelname = channel.title
                val posterurl = channel.url_image.toString()
                val uppername = channelname.uppercase()
                val quality = getQualityFromString(
                    when (!channelname.isNullOrBlank()) {
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
                allresultshome.add(
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
                )


            }

        }




        if (allresultshome.size >= 1) {
            val recommendation = allresultshome
            return LiveStreamLoadResponse(
                name = title,
                url = link,
                apiName = this.name,
                dataUrl = link,
                posterUrl = posterUrl,
                plot = description,
                recommendations = recommendation
            )
        } else {
            return LiveStreamLoadResponse(
                name = title,
                url = link,
                apiName = this.name,
                dataUrl = link,
                posterUrl = posterUrl,
                plot = description,
            )
        }
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


        var lien = link
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

    val rgxcodeCountry = findKeyWord(codeCountry)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (!firstInitDone || arraymediaPlaylist.isEmpty() || detectNewAccount()) {
            if (detectNewAccount()) firstInitDone = false
            getAuthHeader()

            var reponseGetInfo: NiceResponse? = null
            var responseGetgenre: NiceResponse? = null
            var responseAllchannels: NiceResponse? = null
            listOf(
                "$mainUrl/portal.php?type=account_info&action=get_main_info&JsHttpRequest=1-xml",
                "$mainUrl/portal.php?type=itv&action=get_genres&JsHttpRequest=1-xml",
                "$mainUrl/portal.php?type=itv&action=get_all_channels&JsHttpRequest=1-xml",
            ).apmap { url ->
                when (true) {

                    url.contains("action=get_main_info") -> {
                        reponseGetInfo = app.get(url, headers = headerMac)
                    }
                    url.contains("action=get_genre") -> {
                        responseGetgenre = app.get(url, headers = headerMac)
                    }
                    url.contains("action=get_all_channels") -> {
                        responseAllchannels = app.get(url, headers = headerMac)
                    }
                    else -> {
                        ErrorLoadingException("url not good")
                    }
                }
            }

            ///////////// GET EXPIRATION
            val infoExpirationJson = reponseGetInfo!!.parsed<GetExpiration>()
            expiration = infoExpirationJson.js?.phone.toString()
            ////////////////////////// GET ALL GENRES
            responseGetGenretoJSON = responseGetgenre!!.parsed<JsonGetGenre>().js
            ////////////////////////// GET ALL CHANNELS
            val responseAllchannelstoJSON = responseAllchannels!!.parsed<Root>() //parsedSafe
            val AllchannelstoJSON = responseAllchannelstoJSON.js!!.data.sortByTitleNumber()

            return HomePageResponse(
                HomeResponse(
                    AllchannelstoJSON,
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
        val channels: ArrayList<Data> = ArrayList(),
    ) {
        fun String.isContainsTargetCountry(provider: MacIPTVProvider): Boolean {
            val getLang = provider.lang.uppercase()
            var resp = false
            when (true) {
                getLang == "FR" -> {
                    arrayListOf("FRENCH", "FRANCE").forEach {
                        if (this.uppercase().contains(findKeyWord(it))) {
                            resp = true
                        }
                    }
                }
                getLang == "EN" -> {
                    arrayListOf("ENGLISH", "USA").forEach {
                        if (this.uppercase().contains(findKeyWord(it))) {
                            resp = true
                        }
                    }
                }
                getLang == "AR" -> {
                    arrayListOf("ARABIC", "ARAB", "ARABIA").forEach {
                        if (this.uppercase().contains(findKeyWord(it))) {
                            resp = true
                        }
                    }
                }
                else -> resp = false
            }
            return resp
        }


        fun getHomePageListsInit(provider: MacIPTVProvider): List<HomePageList> {
            val arrayHomepage = mutableListOf<HomePageList>()
            val rgxcodeCountry = provider.rgxcodeCountry
            var firstCat = true
            if (responseGetGenretoJSON.isNotEmpty() && channels.isNotEmpty()) {
                responseGetGenretoJSON.forEach { js ->
                    val idGenre = js.id
                    val categoryTitle = js.title.toString()
                    val arraychannel = ArrayList<Channel>()
                    if (idGenre!!.contains("""\d""".toRegex()) && (categoryTitle.uppercase()
                            .contains(rgxcodeCountry) ||
                                categoryTitle.isContainsTargetCountry(provider)
                                ) || categoryTitle.uppercase()
                            .contains(findKeyWord(tags))
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
                    }
                    /***************************************** */
                    val flag: String
                    if (categoryTitle.uppercase()
                            .contains(rgxcodeCountry) || categoryTitle.isContainsTargetCountry(
                            provider
                        ) || categoryTitle.uppercase()
                            .contains(findKeyWord(tags))
                    ) {
                        flag = getFlag(categoryTitle)
                        val nameGenre = if (firstCat) {
                            firstCat = false
                            "$flag ${
                                cleanTitle(categoryTitle).replace(rgxcodeCountry, "").trim()
                            } \uD83D\uDCFA ${provider.name} \uD83D\uDCFA (⏳ $expiration)"
                        } else {
                            "$flag ${cleanTitle(categoryTitle).replace(rgxcodeCountry, "").trim()}"
                        }
                        arrayHomepage.add(
                            arraychannel.toHomePageList(nameGenre, provider, idGenre)
                        )
                    }
                }
            }

            return arrayHomepage
        }

        fun getHomePageListsFromArrayChannel(provider: MacIPTVProvider): List<HomePageList> {
            val arrayHomepage = mutableListOf<HomePageList>()
            val rgxcodeCountry = provider.rgxcodeCountry
            var firstCat = true
            val mychannels = ArrayList(provider.arraymediaPlaylist)
            if (responseGetGenretoJSON.isNotEmpty() && provider.arraymediaPlaylist.isNotEmpty()) {
                responseGetGenretoJSON.forEach { js ->
                    val idGenre = js.id.toString()
                    val categoryTitle = js.title.toString()

                    /***************************************** */
                    val flag: String
                    if (categoryTitle.uppercase()
                            .contains(rgxcodeCountry) || categoryTitle.isContainsTargetCountry(
                            provider
                        )
                    ) {
                        flag = getFlag(categoryTitle)
                        val nameGenre = if (firstCat) {
                            firstCat = false
                            "$flag ${
                                cleanTitle(categoryTitle).replace(rgxcodeCountry, "").trim()
                            } \uD83D\uDCFA ${provider.name} \uD83D\uDCFA (⏳ $expiration)"
                        } else {
                            "$flag ${cleanTitle(categoryTitle).replace(rgxcodeCountry, "").trim()}"
                        }
                        arrayHomepage.add(
                            mychannels.toHomePageList(nameGenre, provider, idGenre)
                        )
                    }
                }
            }

            return arrayHomepage
        }

    }

    companion object {
        var companionName: String? = null
        var loginMac: String? = null
        var overrideUrl: String? = null
        var tags: String = ""
        private var oldAthMac: String? = null
        private var oldAthUrl: String? = null
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
            val flag: String
            flag = when (true) {
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
            return flag
        }

        fun cleanTitle(title: String): String {
            return cleanTitleButKeepNumber(title).replace(
                """(\s\d{1,}${'$'}|\s\d{1,}\s)""".toRegex(),
                " "
            )
        }

        fun ArrayList<Channel>.toSearchResponseHomePage(
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
                            "${provider.mainUrl}${media.toStringCode()}",
                            provider.name,
                            TvType.Live,
                            media.url_image,
                        )
                    )


                }
            }

            return home
        }

        fun ArrayList<Channel>.toHomePageList(
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