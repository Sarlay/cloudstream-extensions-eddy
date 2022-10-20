package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import okhttp3.Interceptor
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.runBlocking
import me.xdrop.fuzzywuzzy.FuzzySearch


class MacIPTVProvider : MainAPI() {
    private val defaulmac_adresse =
        "mac=00:1a:79:a7:9e:ed"
    private val defaultmainUrl =
        "http://matrix-ott.tv:8080"
    var defaultname = "BoxIPTV-MatrixOTT"
    override var name = "Box Iptv"
    override val hasQuickSearch = false // recherche rapide (optionel, pas vraimet utile)
    override val hasMainPage = true // page d'accueil (optionel mais encoragé)
    override var lang = "fr" // fournisseur est en francais
    override val supportedTypes =
        setOf(TvType.Live) // live

    private var init = false
    var key: String? = ""

    companion object {
        var companionName: String? = null
        var loginMac: String? = null
        var overrideUrl: String? = null
    }

    private suspend fun getAuthHeader(): Map<String, String> {
        mainUrl = overrideUrl.toString()
        val main = mainUrl.uppercase().trim()
        val localCredentials = loginMac
        val mac = localCredentials?.uppercase()?.trim()

        if (main == "NONE" || main.isBlank() || mac == "NONE" || mac.isNullOrBlank()) {
            mainUrl = defaultmainUrl
            name = defaultname

            if (!init) {
                val url_key = "$mainUrl/portal.php?type=stb&action=handshake&JsHttpRequest=1-xml"
                val reponseGetkey = app.get(
                    url_key, headers = mapOf(
                        "Cookie" to defaulmac_adresse,
                        "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
                    )
                )
                val keyJson = reponseGetkey.parsed<Getkey>()
                key = keyJson.js?.token
            }
            init = true
            return mapOf(
                "Cookie" to defaulmac_adresse,
                "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
                "Authorization" to "Bearer $key",
            )
        }
        name = companionName ?: name
        if (!init) {
            val url_key = "$mainUrl/portal.php?type=stb&action=handshake&JsHttpRequest=1-xml"
            val reponseGetkey = app.get(
                url_key, headers = mapOf(
                    "Cookie" to "mac=$localCredentials",
                    "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
                )
            )
            val keyJson = reponseGetkey.parsed<Getkey>()
            key = keyJson.js?.token
        }
        init = true
        return mapOf(
            "Cookie" to "mac=$localCredentials",
            "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
            "Authorization" to "Bearer $key",
        )
    }


    private fun List<Channel>.sortByname(query: String?): List<Channel> {
        return if (query == null) {
            // Return list to base state if no query
            this.sortedBy { it.title }
        } else {

            this.sortedBy {
                val name = cleanTitle(it.title)
                -FuzzySearch.ratio(name.lowercase(), query.lowercase())
            }
        }
    }

    private fun List<SearchResponse>.sortBynameNumber(): List<SearchResponse> {
        val regxNbr = Regex("""(\s\d{1,}${'$'}|\s\d{1,}\s)""")
        return this.sortedBy {
            val str = it.name
            regxNbr.find(str)?.groupValues?.get(0)?.trim()?.toInt() ?: -10
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
            val new_descr = "De $t_time à $t_time_to - $name : $descr"
            if (!description.contains(new_descr)) {
                description = "$description\n $new_descr"
            }

        }
        return description
    }

    /**
     * Charge la page d'informations, il ya toutes les donées, les épisodes, le résumé etc ...
     * Il faut retourner soit: AnimeLoadResponse, MovieLoadResponse, TorrentLoadResponse, TvSeriesLoadResponse.
     */

    override suspend fun load(url: String): LoadResponse {

        var link = ""
        var title = ""
        var posterUrl = ""
        var description = ""
        val header = getAuthHeader()
        val allresultshome: MutableList<SearchResponse> = mutableListOf()
        for (media in arraymediaPlaylist) {
            val keyId = "/-${media.id}-"
            if (url.contains(keyId) || url == media.url) {
                val epg_url =
                    "$mainUrl/portal.php?type=itv&action=get_short_epg&ch_id=${media.ch_id}&size=10&JsHttpRequest=1-xml" // descriptif
                val response = app.get(epg_url, headers = header)
                description = getEpg(response.text)
                link = media.url
                title = media.title
                val a = cleanTitle(title)
                posterUrl = media.url_image.toString()
                var b_new: String
                arraymediaPlaylist.forEach { channel ->
                    val b = cleanTitle(channel.title)
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
                                uppername.contains(findCountryId("UHD")) -> {
                                    "UHD"
                                }
                                uppername.contains(findCountryId("HD")) -> {
                                    "HD"
                                }
                                uppername.contains(findCountryId("SD")) -> {
                                    "SD"
                                }
                                uppername.contains(findCountryId("FHD")) -> {
                                    "HDR"
                                }
                                uppername.contains(findCountryId("4K")) -> {
                                    "FourK"
                                }

                                else -> {
                                    null
                                }
                            }
                        )
                        allresultshome.add(
                            LiveSearchResponse(
                                name = cleanTitleKeepNumber(channelname),
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

        if (allresultshome.size >= 2) {
            val recommendation = allresultshome.sortBynameNumber()
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
                        val header = getAuthHeader()
                        val getTokenLink = app.get(TokenLink, headers = header).text
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

        val header = getAuthHeader()
        val TokenLink =
            "$mainUrl/portal.php?type=itv&action=create_link&cmd=ffmpeg%20$data&series=&forced_storage=0&disable_ad=0&download=0&force_ch_link_check=0&JsHttpRequest=1-xml"
        val getTokenLink = app.get(TokenLink, headers = header).text
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
        val isM3u8 = false// lien.contains("extension=ts")
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

    data class Channel(
        var title: String,
        var url: String,
        val url_image: String?,
        val lang: String?,
        var id: String?,
        var tv_genre_id: String?,
        var ch_id: String?,
    )

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

    private val codeCountry = "FR"//|US|UK" // Try US UK BR
    private fun findCountryId(codeCountry: String): Regex {
        return """(?:^|\W+|\s)+($codeCountry)(?:\s|\W+|${'$'}|\|)+""".toRegex()
    }

    private fun cleanTitle(title: String): String {
        return title.uppercase().replace("""(\s\d{1,}${'$'}|\s\d{1,}\s)""".toRegex(), "")
            .replace("""FHD""", "")
            .replace("""VIP""", "")
            .replace("""UHD""", "").replace(rgxcodeCountry, "").replace("""HEVC""", "")
            .replace("""HDR""", "").replace("""SD""", "").replace("""4K""", "")
            .replace("""HD""", "").replace(rgxcodeCountry, "").trim()
    }

    private fun isSelectedCountry(sequence: String, listCountry: List<String>): Boolean {
        var exist = false
        listCountry.forEach { it ->
            if (sequence.uppercase().contains(it)) {
                exist = true
            }
        }
        return exist
    }

    private fun getFlag(sequence: String): String {
        val FR = findCountryId("FR|FRANCE|FRENCH")
        val US = findCountryId("US|USA")
        val AR = findCountryId("AR|ARAB|ARABIC")
        val UK = findCountryId("UK")
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

    val listCountryOrCat = arrayListOf("FRENCH", "FRANCE", "SPORT")
    val rgxcodeCountry = findCountryId(codeCountry)
    val arrayHomepage = arrayListOf<HomePageList>()
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {


        if (!init) {
            val header = getAuthHeader()

            ///////////// GET EXPIRATION
            val url_info =
                "$mainUrl/portal.php?type=account_info&action=get_main_info&JsHttpRequest=1-xml"
            val reponseGetInfo = app.get(url_info, headers = header)
            val infoExpirationJson = reponseGetInfo.parsed<GetExpiration>()
            val expiration = infoExpirationJson.js?.phone
            ////////////////////////// GET ALL GENRES
            val url =
                "$mainUrl/portal.php?type=itv&action=get_genres&JsHttpRequest=1-xml"
            val responseGetgenre = app.get(url, headers = header)
            val responseGetGenretoJSON = responseGetgenre.parsed<JsonGetGenre>()
            ////////////////////////// GET ALL CHANNELS
            val urlGetallchannels =
                "$mainUrl/portal.php?type=itv&action=get_all_channels&JsHttpRequest=1-xml"
            val responseAllchannels = app.get(urlGetallchannels, headers = header)

            val responseAllchannelstoJSON = responseAllchannels.parsed<Root>()
            var firstCat = true
            responseGetGenretoJSON.js.forEach { js ->
                val idGenre = js.id
                val categoryTitle = js.title.toString()

                if (idGenre!!.contains("""\d""".toRegex()) && (categoryTitle.uppercase()
                        .contains(rgxcodeCountry) || isSelectedCountry(
                        categoryTitle,
                        listCountryOrCat
                    ))
                ) {
                    responseAllchannelstoJSON.js!!.data.forEach { data ->
                        val genre = data.tvGenreId

                        if (genre != null) {
                            if (genre == idGenre) {
                                val name = data.name.toString()
                                val tv_genre_id = data.tvGenreId
                                val idCH = data.id
                                val link = "http://localhost/ch/$idCH" + "_"
                                val logo = data.logo?.replace("""\""", "")
                                val ch_id = data.cmds[0].chId
                                arraymediaPlaylist.add(
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

                val groupMedia = ArrayList<String>()
                var b_new: String
                var newgroupMedia: Boolean
                val home = arraymediaPlaylist.mapNotNull { media ->
                    val b = cleanTitle(media.title)//
                    b_new = b.take(6)
                    newgroupMedia = true
                    for (nameMedia in groupMedia) {
                        if (nameMedia.contains(b_new)) {
                            newgroupMedia = false
                            break
                        }
                    }
                    groupMedia.contains(b_new)
                    if (page == 1 && (media.tv_genre_id == idGenre) && newgroupMedia
                    ) {
                        groupMedia.add(b_new)
                        val groupName = cleanTitle(media.title)

                        LiveSearchResponse(
                            groupName,
                            "$mainUrl/-${media.id}-",
                            name,
                            TvType.Live,
                            media.url_image,
                        )
                    } else {
                        null
                    }
                }
                val flag: String
                if ((categoryTitle.uppercase()
                        .contains(rgxcodeCountry) || isSelectedCountry(
                        categoryTitle,
                        listCountryOrCat
                    ))
                ) {


                    flag = getFlag(categoryTitle)
                    val nameGenre = if (firstCat) {
                        firstCat = false
                        "$flag ${cleanTitle(categoryTitle)} \uD83D\uDCFA $name \uD83D\uDCFA (⏳ $expiration)"
                    } else {
                        "$flag ${cleanTitle(categoryTitle)}"
                    }
                    arrayHomepage.add(HomePageList(nameGenre, home, isHorizontalImages = true))
                }

            }
        }
        return HomePageResponse(
            arrayHomepage
        )
    }

    private fun cleanTitleKeepNumber(title: String): String {
        return title.uppercase().replace("""FHD""", "")
            .replace("""VIP""", "")
            .replace("""UHD""", "").replace("""HEVC""", "")
            .replace("""HDR""", "").replace("""SD""", "").replace("""4K""", "")
            .replace("""HD""", "").replace(findCountryId("FR|AF"), "").trim()
    }
}