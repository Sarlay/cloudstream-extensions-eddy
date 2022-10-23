package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import okhttp3.Interceptor
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.runBlocking
import me.xdrop.fuzzywuzzy.FuzzySearch
import com.lagradost.MacIPTVProvider.Companion.toHomePageList


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

fun cleanTitle(title: String): String {
    return cleanTitleButKeepNumber(title).replace(
        """(\s\d{1,}${'$'}|\s\d{1,}\s)""".toRegex(),
        " "
    )
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

data class Channel(
    var title: String,
    var url: String,
    val url_image: String?,
    val lang: String?,
    var id: String?,
    var tv_genre_id: String?,
    var ch_id: String?,
) {
    fun toSearchResponseHomePage(
        provider: MacIPTVProvider,
    ): SearchResponse {
        val media = this
        val groupName = cleanTitle(media.title).replace(provider.rgxcodeCountry, "").trim()
        return LiveSearchResponse(
            groupName,
            "$provider.mainUrl/-${media.id}-",
            provider.name,
            TvType.Live,
            media.url_image,
        )
    }
}


class MacIPTVProvider(override var lang: String) : MainAPI() {
    private val defaulmac_adresse =
        "mac=00:1A:79:aa:53:65"
    private val defaultmainUrl =
        "http://ky-iptv.com:25461/portalstb"
    var defaultname = "ky-iptv |${lang.uppercase()}|"
    override var name = "Box Iptv |${lang.uppercase()}|"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override val supportedTypes =
        setOf(TvType.Live) // live

    private var firstInitDone = false
    private var key: String? = ""

    private fun accountInfoNotGood(url: String, mac: String?): Boolean {
        return url.uppercase().trim() == "NONE" || url.isBlank() || mac?.uppercase()
            ?.trim() == "NONE" || mac.isNullOrBlank()
    }

    private suspend fun getAuthHeader(): Map<String, String> {

        mainUrl = overrideUrl.toString()
        name = (companionName ?: name) + " |${lang.uppercase()}|"
        val localCredentials = loginMac
        when (true) {
            accountInfoNotGood(mainUrl, localCredentials) -> {
                mainUrl = defaultmainUrl
                name = defaultname
                if (!firstInitDone) {
                    val url_key =
                        "$mainUrl/portal.php?type=stb&action=handshake&JsHttpRequest=1-xml"
                    val reponseGetkey = app.get(
                        url_key, headers = mapOf(
                            "Cookie" to defaulmac_adresse,
                            "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
                        )
                    )
                    val keyJson = reponseGetkey.parsed<Getkey>()
                    key = keyJson.js?.token
                }
                firstInitDone = true
                return mapOf(
                    "Cookie" to defaulmac_adresse,
                    "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
                    "Authorization" to "Bearer $key",
                )
            }
            else -> {
                if (!firstInitDone) {
                    val url_key =
                        "$mainUrl/portal.php?type=stb&action=handshake&JsHttpRequest=1-xml"
                    val reponseGetkey = app.get(
                        url_key, headers = mapOf(
                            "Cookie" to "mac=$localCredentials",
                            "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
                        )
                    )
                    val keyJson = reponseGetkey.parsed<Getkey>()
                    key = keyJson.js?.token
                }
                firstInitDone = true
                return mapOf(
                    "Cookie" to "mac=$localCredentials",
                    "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
                    "Authorization" to "Bearer $key",
                )
            }
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
                    media.url,
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

        /*   @JsonProperty("id") var id: String? = null,
           @JsonProperty("ch_id") var chId: String? = null,
           @JsonProperty("correct") var correct: String? = null,
           @JsonProperty("time") var time: String? = null,
           @JsonProperty("time_to") var timeTo: String? = null,
           @JsonProperty("duration") var duration: Int? = null,*/
        @JsonProperty("name") var name: String? = null,
        @JsonProperty("descr") var descr: String? = null,
/*        @JsonProperty("real_id") var realId: String? = null,
        @JsonProperty("category") var category: String? = null,
        @JsonProperty("director") var director: String? = null,
        @JsonProperty("actor") var actor: String? = null,
        @JsonProperty("start_timestamp") var startTimestamp: Int? = null,
        @JsonProperty("stop_timestamp") var stopTimestamp: Int? = null,*/
        @JsonProperty("t_time") var tTime: String? = null,
        @JsonProperty("t_time_to") var tTimeTo: String? = null,
        /*     @JsonProperty("mark_memo") var markMemo: Int? = null,
             @JsonProperty("mark_archive") var markArchive: Int? = null*/

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


    override suspend fun load(url: String): LoadResponse {

        var link = url
        var title = "Your channel"
        var posterUrl = ""
        var description = "The program for this channel was not found"
        val allresultshome: MutableList<SearchResponse> = mutableListOf()
        val headerIPTV = getAuthHeader()
        for (media in arraymediaPlaylist) {
            val keyId = "/-${media.id}-"
            if (url.contains(keyId) || url == media.url) {
                val epg_url =
                    "$mainUrl/portal.php?type=itv&action=get_short_epg&ch_id=${media.ch_id}&size=10&JsHttpRequest=1-xml" // plot
                val response = app.get(epg_url, headers = headerIPTV)
                description = getEpg(response.text)
                link = media.url
                title = media.title
                val a = cleanTitle(title).replace(rgxcodeCountry, "").trim()
                posterUrl = media.url_image.toString()
                var b_new: String
                arraymediaPlaylist.forEach { channel ->
                    val b = cleanTitle(channel.title).replace(rgxcodeCountry, "").trim()
                    b_new = b.take(6)
                    if (channel.id != media.id && a.take(6)
                            .contains(b_new) && media.tv_genre_id == channel.tv_genre_id
                    ) {
                        val streamurl = channel.url
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

                break
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
                        val headerIPTV = getAuthHeader()
                        val getTokenLink = app.get(TokenLink, headers = headerIPTV).text
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
        val headerIPTV = getAuthHeader()
        val TokenLink =
            "$mainUrl/portal.php?type=itv&action=create_link&cmd=ffmpeg%20$data&series=&forced_storage=0&disable_ad=0&download=0&force_ch_link_check=0&JsHttpRequest=1-xml"
        val getTokenLink = app.get(TokenLink, headers = headerIPTV).text
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

        // @JsonProperty("id") var id: String? = null,
        @JsonProperty("ch_id") var chId: String? = null,
        /*      @JsonProperty("priority") var priority: String? = null,
              @JsonProperty("url") var url: String? = null,
              @JsonProperty("status") var status: String? = null,
              @JsonProperty("use_http_tmp_link") var useHttpTmpLink: String? = null,
              @JsonProperty("wowza_tmp_link") var wowzaTmpLink: String? = null,
              @JsonProperty("user_agent_filter") var userAgentFilter: String? = null,
              @JsonProperty("use_load_balancing") var useLoadBalancing: String? = null,
              @JsonProperty("changed") var changed: String? = null,
              @JsonProperty("enable_monitoring") var enableMonitoring: String? = null,
              @JsonProperty("enable_balancer_monitoring") var enableBalancerMonitoring: String? = null,
              @JsonProperty("nginx_secure_link") var nginxSecureLink: String? = null,
              @JsonProperty("flussonic_tmp_link") var flussonicTmpLink: String? = null*/

    )

    data class Data(

        @JsonProperty("id") var id: String? = null,
        @JsonProperty("name") var name: String? = null,
        @JsonProperty("number") var number: String? = null,
/*        @JsonProperty("censored") var censored: Int? = null,
        @JsonProperty("cmd") var cmd: String? = null,
        @JsonProperty("cost") var cost: String? = null,
        @JsonProperty("count") var count: String? = null,
        @JsonProperty("status") var status: Int? = null,
        @JsonProperty("hd") var hd: String? = null,*/
        @JsonProperty("tv_genre_id") var tvGenreId: String? = null,
/*        @JsonProperty("base_ch") var baseCh: String? = null,
        @JsonProperty("xmltv_id") var xmltvId: String? = null,
        @JsonProperty("service_id") var serviceId: String? = null,
        @JsonProperty("bonus_ch") var bonusCh: String? = null,
        @JsonProperty("volume_correction") var volumeCorrection: String? = null,
        @JsonProperty("mc_cmd") var mcCmd: String? = null,
        @JsonProperty("enable_tv_archive") var enableTvArchive: Int? = null,
        @JsonProperty("wowza_tmp_link") var wowzaTmpLink: String? = null,
        @JsonProperty("wowza_dvr") var wowzaDvr: String? = null,
        @JsonProperty("use_http_tmp_link") var useHttpTmpLink: String? = null,
        @JsonProperty("monitoring_status") var monitoringStatus: String? = null,
        @JsonProperty("enable_monitoring") var enableMonitoring: String? = null,
        @JsonProperty("enable_wowza_load_balancing") var enableWowzaLoadBalancing: String? = null,
        @JsonProperty("cmd_1") var cmd1: String? = null,
        @JsonProperty("cmd_2") var cmd2: String? = null,
        @JsonProperty("cmd_3") var cmd3: String? = null,*/
        @JsonProperty("logo") var logo: String? = null,
        /*     @JsonProperty("correct_time") var correctTime: String? = null,
             @JsonProperty("nimble_dvr") var nimbleDvr: String? = null,
             @JsonProperty("allow_pvr") var allowPvr: Int? = null,
             @JsonProperty("allow_local_pvr") var allowLocalPvr: Int? = null,
             @JsonProperty("allow_remote_pvr") var allowRemotePvr: Int? = null,
             @JsonProperty("modified") var modified: String? = null,
             @JsonProperty("allow_local_timeshift") var allowLocalTimeshift: String? = null,
             @JsonProperty("nginx_secure_link") var nginxSecureLink: String? = null,
             @JsonProperty("tv_archive_duration") var tvArchiveDuration: Int? = null,
             @JsonProperty("locked") var locked: Int? = null,
             @JsonProperty("lock") var lock: Int? = null,
             @JsonProperty("fav") var fav: Int? = null,
             @JsonProperty("archive") var archive: Int? = null,
             @JsonProperty("genres_str") var genresStr: String? = null,
             @JsonProperty("cur_playing") var curPlaying: String? = null,
             @JsonProperty("epg") var epg: ArrayList<String> = arrayListOf(),
             @JsonProperty("open") var open: Int? = null,*/
        @JsonProperty("cmds") var cmds: ArrayList<Cmds> = arrayListOf(),
        /* @JsonProperty("use_load_balancing") var useLoadBalancing: Int? = null,
         @JsonProperty("pvr") var pvr: Int? = null*/

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
        /*  @JsonProperty("selected_item") var selectedItem: Int? = null,
          @JsonProperty("cur_page") var curPage: Int? = null,*/
        @JsonProperty("data") var data: ArrayList<Data> = arrayListOf()

    )

    data class JsonGetGenre(

        @JsonProperty("js") var js: ArrayList<Js_category> = arrayListOf()

    )

    data class Js_category(

        @JsonProperty("id") var id: String? = null,
        @JsonProperty("title") var title: String? = null,
        /*  @JsonProperty("alias") var alias: String? = null,
          @JsonProperty("active_sub") var activeSub: Boolean? = null,
          @JsonProperty("censored") var censored: Int? = null*/

    )

    data class Root(

        @JsonProperty("js") var js: Js? = Js()

    )

    private var codeCountry = lang


    val rgxcodeCountry = findKeyWord(codeCountry)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        var arrayHomepage = mutableListOf<HomePageList>()
        if (!firstInitDone) {
            val headerIPTV = getAuthHeader()
            val url_info =
                "$mainUrl/portal.php?type=account_info&action=get_main_info&JsHttpRequest=1-xml"
            val urlGetGenre =
                "$mainUrl/portal.php?type=itv&action=get_genres&JsHttpRequest=1-xml"
            val urlGetallchannels =
                "$mainUrl/portal.php?type=itv&action=get_all_channels&JsHttpRequest=1-xml"

            var reponseGetInfo: NiceResponse? = null
            var responseGetgenre: NiceResponse? = null
            var responseAllchannels: NiceResponse? = null
            listOf(
                url_info,
                urlGetGenre,
                urlGetallchannels
            ).apmap { url ->
                val response = app.get(url, headers = headerIPTV)
                when (true) {
                    url.contains("action=get_main_info") -> {
                        reponseGetInfo = response
                    }
                    url.contains("action=get_genre") -> {
                        responseGetgenre = response
                    }
                    url.contains("action=get_all_channels") -> {
                        responseAllchannels = response
                    }
                    else -> {
                        ""
                    }
                }
            }
            ///////////// GET EXPIRATION
            val infoExpirationJson = reponseGetInfo!!.parsed<GetExpiration>()
            val expiration = infoExpirationJson.js?.phone.toString()
            ////////////////////////// GET ALL GENRES
            val responseGetGenretoJSON = responseGetgenre!!.parsed<JsonGetGenre>()
            ////////////////////////// GET ALL CHANNELS
            val responseAllchannelstoJSON = responseAllchannels!!.parsed<Root>()
            val AllchannelstoJSON = responseAllchannelstoJSON.js!!.data.sortByTitleNumber()
            arrayHomepage = HomeResponse(
                responseGetGenretoJSON,
                AllchannelstoJSON,
                expiration,
            ).getHomePageLists(this)
        }

        return HomePageResponse(
            arrayHomepage
        )
    }


    fun ArrayList<Data>.sortByTitleNumber(): ArrayList<Data> {
        val regxNbr = Regex("""(\s\d{1,}${'$'}|\s\d{1,}\s)""")
        return ArrayList(this.sortedBy {
            val str = it.name.toString()
            regxNbr.find(str)?.groupValues?.get(0)?.trim()?.toInt() ?: 1000
        })
    }


    private data class HomeResponse(
        val genres: JsonGetGenre,
        val channels: ArrayList<Data>,
        val expiration: String,
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


        fun getHomePageLists(provider: MacIPTVProvider): ArrayList<HomePageList> {
            val arrayHomepage = ArrayList<HomePageList>()
            val rgxcodeCountry = provider.rgxcodeCountry
            var firstCat = true
            genres.js.forEach { js ->
                val idGenre = js.id
                val categoryTitle = js.title.toString()
                val arraymedia = ArrayList<Channel>()
                if (idGenre!!.contains("""\d""".toRegex()) && (categoryTitle.uppercase()
                        .contains(rgxcodeCountry) ||
                            categoryTitle.isContainsTargetCountry(provider)
                            )
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
                                arraymedia.add(
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
                        arraymedia.toHomePageList(nameGenre, provider, idGenre)
                    )
                    if (provider.groupMedia.isNotEmpty()) {
                        provider.groupMedia.clear()
                    }
                }

            }
            return arrayHomepage
        }
    }

    var groupMedia = mutableListOf<String>()

    companion object {
        var companionName: String? = null
        var loginMac: String? = null
        var overrideUrl: String? = null
        fun List<Channel>.toHomePageList(name: String, provider: MacIPTVProvider, GenreId: String) =
            HomePageList(
                name, this.mapNotNull {

                    var b_new: String
                    var newgroupMedia: Boolean
                    val rgxcodeCountry = provider.rgxcodeCountry
                    val media = it
                    val b = cleanTitle(media.title).replace(rgxcodeCountry, "").trim()
                    b_new = b.take(6)
                    newgroupMedia = true
                    for (nameMedia in provider.groupMedia) {
                        if (nameMedia.contains(b_new) && media.tv_genre_id == GenreId) {
                            newgroupMedia = false
                            break
                        }
                    }
                    if (newgroupMedia && media.tv_genre_id == GenreId) { //
                        provider.groupMedia.add(b_new)
                        it.toSearchResponseHomePage(provider)
                    } else {
                        null
                    }
                },
                isHorizontalImages = true
            )

    }
}