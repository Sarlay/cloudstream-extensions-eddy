package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.animeproviders.NekosamaProvider
import com.lagradost.cloudstream3.utils.*

import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element

import me.xdrop.fuzzywuzzy.FuzzySearch
import java.util.*
import kotlin.collections.ArrayList

class PickTV : MainAPI() {
    override var mainUrl = "http"
    override var name = "MyPickTV"
    override val hasQuickSearch = false // recherche rapide (optionel, pas vraimet utile)
    override val hasMainPage = true // page d'accueil (optionel mais encoragé)
    override var lang = "fr" // fournisseur est en francais
    override val supportedTypes =
        setOf(TvType.Live) // live
    val takeN = 10


    /**
    Cherche le site pour un titre spécifique

    La recherche retourne une SearchResponse, qui peut être des classes suivants: AnimeSearchResponse, MovieSearchResponse, TorrentSearchResponse, TvSeriesSearchResponse
    Chaque classes nécessite des données différentes, mais a en commun le nom, le poster et l'url
     **/

    private fun List<mediaData>.sortByname(query: String?): List<mediaData> {
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
                    "${getFlag(media.lang.toString())} ${media.title}",
                    media.url_load.toString(),
                    media.title,
                    TvType.Live,
                    media.url_image,
                )
            )
        }

        return searchResutls

    }

    /**
     * charge la page d'informations, il ya toutes les donées, les épisodes, le résumé etc ...
     * Il faut retourner soit: AnimeLoadResponse, MovieLoadResponse, TorrentLoadResponse, TvSeriesLoadResponse.
     */
    val allresultshome: MutableList<SearchResponse> = mutableListOf()
    override suspend fun load(url: String): LoadResponse {
        var link = ""
        var title = ""
        var posterUrl = ""

        var flag = ""
        allresultshome.clear()
        for (media in arraymediaPlaylist) {
            if (url == media.url) {
                link = media.url
                title = media.title
                flag = getFlag(media.lang.toString())
                val a = cleanTitle(title)
                posterUrl = media.url_image.toString()
                var b_new: String
                arraymediaPlaylist.forEach { channel ->
                    val b = cleanTitle(channel.title)
                    b_new = b.take(takeN)
                    if (a.take(takeN)
                            .contains(b_new) && media.genre == channel.genre && media.url != channel.url
                    ) {
                        val streamurl = channel.url
                        val channelname = channel.title
                        val posterurl = channel.url_image.toString()
                        val nameURLserver = "\uD83D\uDCF6" + streamurl.replace("http://", "")
                            .replace("https://", "").take(8)

                        allresultshome.add(
                            LiveSearchResponse(
                                name = "${getFlag(channel.lang.toString())} $channelname $nameURLserver",
                                url = streamurl,
                                name,
                                TvType.Live,
                                posterUrl = posterurl,
                            )
                        )

                    }

                }
                break
            }
        }
        val nameURLserver =
            "\uD83D\uDCF6" + link.replace("http://", "").replace("https://", "").take(8)

        if (allresultshome.size >= 2) {
            val recommendation = allresultshome
            return LiveStreamLoadResponse(
                name = "$title $flag $nameURLserver",
                url = link,
                apiName = this.name,
                dataUrl = link,
                posterUrl = posterUrl,
                recommendations = recommendation
            )
        } else {
            return LiveStreamLoadResponse(
                name = "$title $flag $nameURLserver",
                url = link,
                apiName = this.name,
                dataUrl = link,
                posterUrl = posterUrl,
            )
        }
    }


    /** récupere les liens .mp4 ou m3u8 directement à partir du paramètre data généré avec la fonction load()**/
    override suspend fun loadLinks(
        data: String, // fournit par load()
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var isM3u = false
        var link: String = data
        when (true) {
            data.contains("dreamsat.ddns") -> {
                val headers1 = mapOf(
                    "User-Agent" to "REDLINECLIENT_DREAMSAT_650HD_PRO_RICHTV_V02",
                    "Accept-Encoding" to "identity",
                    "Connection" to "Keep-Alive",
                )
                val headerlocation = app.get(
                    data, headers = headers1,
                    allowRedirects = false
                ).headers
                val redirectlink = headerlocation.get("location")
                    .toString()

                if (redirectlink != "null") {
                    link = redirectlink
                }
            }
            else -> {
                link = data
            }
        }
        val live = link.replace("http://", "").replace("https://", "").take(8) + " \uD83D\uDD34"
        callback.invoke(
            ExtractorLink(
                name,
                live,
                link,
                "",
                Qualities.Unknown.value,
                isM3u8 = isM3u,
            )
        )
        return true
    }


    private val arraymediaPlaylist = ArrayList<mediaData>()

    data class mediaData(
        @JsonProperty("title") var title: String,
        @JsonProperty("url") val url: String,
        @JsonProperty("genre") val genre: String?,
        @JsonProperty("url_image") val url_image: String?,
        @JsonProperty("lang") val lang: String?,
        @JsonProperty("url_load") var url_load: String?,
    )

    private fun findCountryId(codeCountry: String): Regex {
        return """(?:^|\W+|\s)+($codeCountry)(?:\s|\W+|${'$'}|\|)+""".toRegex()
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

    private fun getGenreIcone(sequence: String): String {
        val SPORT = findCountryId("SPORT")
        val INFO = findCountryId("INFO")
        val GENERAL = findCountryId("GENERAL")
        val MUSIQUE = findCountryId("MUSIQUE")
        val CINEMA = findCountryId("CINEMA")
        val SERIES = findCountryId("SERIES")
        val DIVERTISSEMENT = findCountryId("DIVERTISSEMENT")
        val JEUNESSE = findCountryId("JEUNESSE")
        val DOCUMENTAIRE = findCountryId("DOCUMENTAIRE")
        val genreIcon: String
        genreIcon = when (true) {
            sequence.uppercase()
                .contains(SPORT) -> "⚽ \uD83E\uDD4A \uD83C\uDFC0 \uD83C\uDFBE"
            sequence.uppercase()
                .contains(INFO) -> "\uD83E\uDD25 \uD83D\uDCF0 ⚠ Peu importe la source toujours vérifier les infos"
            sequence.uppercase()
                .contains(CINEMA) -> "\uD83C\uDFA5"
            sequence.uppercase()
                .contains(MUSIQUE) -> "\uD83C\uDFB6"
            sequence.uppercase()
                .contains(SERIES) -> "\uD83D\uDCFA"
            sequence.uppercase()
                .contains(DIVERTISSEMENT) -> "✨"
            sequence.uppercase()
                .contains(JEUNESSE) -> "\uD83D\uDC67\uD83D\uDC75\uD83D\uDE1B"
            sequence.uppercase().contains(DOCUMENTAIRE) -> "\uD83E\uDD96"
            sequence.uppercase().contains(GENERAL) -> "\uD83E\uDDD0 \uD83D\uDCFA"
            else -> ""
        }
        return genreIcon
    }

    val arrayHomepage = arrayListOf<HomePageList>()
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page == 1) {
            val url =
                "https://raw.githubusercontent.com/Eddy976/cloudstream-extensions-eddy/ressources/trickylist.json"
            var targetMedia: mediaData
            val reponse = app.get(url).text
            val arraymediaPlaylist = tryParseJson<ArrayList<mediaData>>(reponse)!!
            val genreMedia = ArrayList<String>()
            var newGenre: String
            var category: String
            var newgenreMedia: Boolean
            ///////////////////////
            arraymediaPlaylist!!.forEach { media ->
                this.arraymediaPlaylist.add(media)
                newGenre = cleanTitle(media.genre.toString())//

                newgenreMedia = true
                for (nameGenre in genreMedia) {
                    if (nameGenre.contains(newGenre)) {
                        newgenreMedia = false
                        break
                    }
                }
                if (newgenreMedia
                ) {
                    genreMedia.add(newGenre)
                    category = newGenre
                    val groupMedia = ArrayList<String>()
                    var b_new: String
                    var newgroupMedia: Boolean
                    var mediaGenre: String
                    val home = arraymediaPlaylist!!.mapNotNull { media ->

                        val b = cleanTitle(media.title)//
                        b_new = b.take(takeN)
                        newgroupMedia = true
                        for (nameMedia in groupMedia) {
                            if (nameMedia.contains(b_new)) {
                                newgroupMedia = false
                                break
                            }
                        }
                        groupMedia.contains(b_new)
                        mediaGenre = cleanTitle(media.genre.toString())
                        if (newgroupMedia && (mediaGenre == category)
                        ) {
                            targetMedia = media
                            groupMedia.add(b_new)
                            val groupName = "${cleanTitle(targetMedia.title)}"

                            LiveSearchResponse(
                                groupName,
                                targetMedia.url,
                                targetMedia.title,
                                TvType.Live,
                                targetMedia.url_image,
                            )
                        } else {
                            null
                        }

                    }
                    arrayHomepage.add(
                        HomePageList(
                            "$category ${getGenreIcone(category)}",
                            home,
                            isHorizontalImages = true
                        )
                    )
                }
            }
        }
        return HomePageResponse(
            arrayHomepage
        )
    }

    private fun cleanTitle(title: String): String {
        return title.uppercase().replace("""\s\d{1,10}""".toRegex(), "").replace("""FHD""", "")
            .replace("""VIP""", "")
            .replace("""UHD""", "").replace("""HEVC""", "")
            .replace("""HDR""", "").replace("""SD""", "").replace("""4K""", "")
            .replace("""HD""", "").replace(findCountryId("FR|AF"), "").trim()
    }
}

