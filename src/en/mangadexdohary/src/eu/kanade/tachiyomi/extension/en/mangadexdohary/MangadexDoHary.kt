package eu.kanade.tachiyomi.extension.en.mangadexdohary

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response

@Source
abstract class MangadexDoHary : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$API_URL/manga".toHttpUrl().newBuilder()
            .addQueryParameter("order[followedCount]", "desc")
            .addQueryParameter("availableTranslatedLanguage[]", "en")
            .addQueryParameter("limit", MANGA_LIMIT.toString())
            .addQueryParameter("offset", ((page - 1) * MANGA_LIMIT).toString())
            .addQueryParameter("includes[]", "cover_art")
            .addDefaultContentRatings()
            .build()

        return parseMangasPage(client.get(url))
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$API_URL/manga".toHttpUrl().newBuilder()
            .addQueryParameter("order[latestUploadedChapter]", "desc")
            .addQueryParameter("availableTranslatedLanguage[]", "en")
            .addQueryParameter("limit", MANGA_LIMIT.toString())
            .addQueryParameter("offset", ((page - 1) * MANGA_LIMIT).toString())
            .addQueryParameter("includes[]", "cover_art")
            .addDefaultContentRatings()
            .build()

        return parseMangasPage(client.get(url, cacheControl = CacheControl.FORCE_NETWORK))
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val urlBuilder = "$API_URL/manga".toHttpUrl().newBuilder()
            .addQueryParameter("availableTranslatedLanguage[]", "en")
            .addQueryParameter("limit", MANGA_LIMIT.toString())
            .addQueryParameter("offset", ((page - 1) * MANGA_LIMIT).toString())
            .addQueryParameter("includes[]", "cover_art")
            .addDefaultContentRatings()

        val cleanQuery = query.trim().replace(whitespaceRegex, " ")
        if (cleanQuery.isNotBlank()) {
            urlBuilder.addQueryParameter("title", cleanQuery)
        }

        val activeFilters = filters.ifEmpty { getFilterList(null) }
        activeFilters.filterIsInstance<UrlQueryFilter>().forEach { filter ->
            filter.addQueryParameter(urlBuilder)
        }

        return parseMangasPage(client.get(urlBuilder.build(), cacheControl = CacheControl.FORCE_NETWORK))
    }

    private fun parseMangasPage(response: Response): MangasPage {
        if (response.code == 204) {
            response.close()
            return MangasPage(emptyList(), false)
        }
        val dto = response.parseAs<PaginatedResponseDto<MangaDataDto>>()
        return MangasPage(
            mangas = dto.data.map(MangaDataDto::toBasicSManga),
            hasNextPage = dto.hasNextPage,
        )
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url.replace("/manga/", "/title/")

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val id = uuidRegex.find(url.encodedPath)?.value ?: return null
        return fetchMangaDetails(id)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val mangaId = manga.url.substringAfterLast("/")
        val detailsDeferred = async {
            if (fetchDetails) fetchMangaDetails(mangaId) else manga
        }
        val chaptersDeferred = async {
            if (fetchChapters) fetchChapterList(mangaId) else chapters
        }
        SMangaUpdate(detailsDeferred.await(), chaptersDeferred.await())
    }

    private suspend fun fetchMangaDetails(mangaId: String): SManga {
        val url = "$API_URL/manga/$mangaId".toHttpUrl().newBuilder()
            .addQueryParameter("includes[]", "cover_art")
            .addQueryParameter("includes[]", "author")
            .addQueryParameter("includes[]", "artist")
            .build()

        val dto = client.get(url, cacheControl = CacheControl.FORCE_NETWORK)
            .parseAs<SingleResponseDto<MangaDataDto>>()
        return dto.data.toSManga()
    }

    private suspend fun fetchChapterList(mangaId: String): List<SChapter> {
        val chapters = mutableListOf<ChapterDataDto>()
        var offset = 0
        var hasNextPage = true

        while (hasNextPage) {
            val url = "$API_URL/manga/$mangaId/feed".toHttpUrl().newBuilder()
                .addQueryParameter("limit", CHAPTER_LIMIT.toString())
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("translatedLanguage[]", "en")
                .addQueryParameter("includes[]", "scanlation_group")
                .addQueryParameter("includes[]", "user")
                .addQueryParameter("order[volume]", "desc")
                .addQueryParameter("order[chapter]", "desc")
                .addQueryParameter("includeFuturePublishAt", "0")
                .addQueryParameter("includeEmptyPages", "0")
                .addQueryParameter("includeExternalUrl", "0")
                .addAllContentRatings()
                .build()

            val response = client.get(url, cacheControl = CacheControl.FORCE_NETWORK)
            if (response.code == 204) {
                response.close()
                break
            }

            val pageDto = response.parseAs<PaginatedResponseDto<ChapterDataDto>>()
            chapters.addAll(pageDto.data)
            offset += pageDto.limit
            hasNextPage = pageDto.hasNextPage
        }

        return chapters
            .filter(ChapterDataDto::isValid)
            .map(ChapterDataDto::toSChapter)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url.substringAfterLast("/")
        val atHome = client.get(
            "$API_URL/at-home/server/$chapterId",
            cacheControl = CacheControl.FORCE_NETWORK,
        ).parseAs<AtHomeDto>()

        val host = atHome.baseUrl
        val hash = atHome.chapter.hash
        return atHome.chapter.data.mapIndexed { index, fileName ->
            Page(index, imageUrl = "$host/data/$hash/$fileName")
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = buildMangaDexFilterList()

    private fun HttpUrl.Builder.addDefaultContentRatings(): HttpUrl.Builder = apply {
        DEFAULT_CONTENT_RATINGS.forEach { addQueryParameter("contentRating[]", it) }
    }

    private fun HttpUrl.Builder.addAllContentRatings(): HttpUrl.Builder = apply {
        ALL_CONTENT_RATINGS.forEach { addQueryParameter("contentRating[]", it) }
    }

    private companion object {
        private const val API_URL = "https://api.mangadex.org"
        private const val MANGA_LIMIT = 20
        private const val CHAPTER_LIMIT = 500

        private val whitespaceRegex = Regex("\\s+")
        private val uuidRegex =
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

        private val DEFAULT_CONTENT_RATINGS = listOf("safe", "suggestive", "erotica")
        private val ALL_CONTENT_RATINGS = listOf("safe", "suggestive", "erotica", "pornographic")
    }
}
