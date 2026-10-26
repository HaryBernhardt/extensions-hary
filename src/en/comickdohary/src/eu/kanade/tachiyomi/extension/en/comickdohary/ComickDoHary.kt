package eu.kanade.tachiyomi.extension.en.comickdohary

import eu.kanade.tachiyomi.source.model.Filter
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
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.lang.Thread.sleep

@Source
abstract class ComickDoHary : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor { chain ->
            val request = chain.request()

            var response = chain.proceed(request)
            var retries = 0

            while (response.code == 429 && retries++ < 10) {
                response.close()
                sleep(500)

                response = chain.proceed(
                    request.newBuilder()
                        .url(request.url.newBuilder().fragment("retry").build())
                        .build(),
                )
            }
            response
        }
        rateLimit(2) {
            it.fragment != "retry" && "covers" !in it.pathSegments
        }
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/api/comics/top".toHttpUrl().newBuilder().apply {
            val days = when (page) {
                1, 4 -> 7
                2, 5 -> 30
                3, 6 -> 90
                else -> throw UnsupportedOperationException()
            }
            val type = when (page) {
                1, 2, 3 -> "follow"
                4, 5, 6 -> "most_follow_new"
                else -> throw UnsupportedOperationException()
            }
            addQueryParameter("day", days.toString())
            addQueryParameter("type", type)
        }.build()

        val data = client.get(url).parseAs<Data<List<BrowseComic>>>()

        return MangasPage(
            mangas = data.data.map(BrowseComic::toSManga),
            hasNextPage = page < 6,
        )
    }

    private var latestNextCursor: String? = null
    private var searchNextCursor: String? = null

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page == 1) latestNextCursor = null

        val url = "$baseUrl/api/chapters/latest".toHttpUrl().newBuilder().apply {
            addQueryParameter("order", "new")
            addQueryParameter("page", page.toString())
            if (page > 1) addQueryParameter("cursor", latestNextCursor)
        }.build()

        val data = client.get(url).parseAs<SearchResponse>()

        latestNextCursor = data.cursor

        return MangasPage(
            mangas = data.data.map(BrowseComic::toSManga),
            hasNextPage = data.cursor != null,
        )
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val slug = url.pathSegments.getOrNull(1) ?: return null
        return parseDetails(client.get("$baseUrl/comic/$slug"))
    }

    private val spaceSlashRegex = Regex("[ /]")

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        if (page == 1) searchNextCursor = null

        val url = "$baseUrl/api/search".toHttpUrl().newBuilder().apply {
            filters.firstInstanceOrNull<SortFilter>()?.let {
                addQueryParameter("order_by", it.selected)
                addQueryParameter("order_direction", if (it.state!!.ascending) "asc" else "desc")
            } ?: run {
                addQueryParameter("order_by", "created_at")
                addQueryParameter("order_direction", "desc")
            }
            filters.firstInstanceOrNull<GenreFilter>()?.let { genre ->
                genre.included.forEach {
                    addQueryParameter("genres", it)
                }
                genre.excluded.forEach {
                    addQueryParameter("excludes", it)
                }
            }
            filters.firstInstanceOrNull<TagFilterText>()?.let { text ->
                text.state.split(",").filter(String::isNotBlank).forEach {
                    val value = it.trim().lowercase().replace(spaceSlashRegex, "-")
                    addQueryParameter(
                        if (value.startsWith("-")) "excluded_tags" else "tags",
                        value.replaceFirst("-", ""),
                    )
                }
            }
            filters.firstInstanceOrNull<TagFilters>()?.let { tags ->
                tags.state.forEach { letter ->
                    letter.included.forEach {
                        addQueryParameter("tags", it)
                    }
                    letter.excluded.forEach {
                        addQueryParameter("excluded_tags", it)
                    }
                }
            }
            filters.firstInstanceOrNull<DemographicFilter>()?.checked?.forEach {
                addQueryParameter("demographic", it)
            }
            filters.firstInstanceOrNull<CreatedAtFilter>()?.selected?.let {
                addQueryParameter("time", it)
            }
            filters.firstInstanceOrNull<TypeFilter>()?.checked?.forEach {
                addQueryParameter("country", it)
            }
            filters.firstInstanceOrNull<MinimumChaptersFilter>()?.state?.let {
                if (it.isNotBlank()) {
                    if (it.toIntOrNull() == null) {
                        throw Exception("Invalid minimum chapters value: $it")
                    }
                    addQueryParameter("minimum", it)
                }
            }
            filters.firstInstanceOrNull<StatusFilter>()?.selected?.let {
                addQueryParameter("status", it)
            }
            filters.firstInstanceOrNull<ReleaseFrom>()?.selected?.let {
                addQueryParameter("from", it)
            }
            filters.firstInstanceOrNull<ReleaseTo>()?.selected?.let {
                addQueryParameter("to", it)
            }
            filters.firstInstanceOrNull<ContentRatingFilter>()?.selected?.let {
                addQueryParameter("content_rating", it)
            }
            addQueryParameter("showAll", "false")
            addQueryParameter("exclude_mylist", "false")
            if (query.isNotBlank()) {
                if (query.trim().length < 3) {
                    throw Exception("Query must be at least 3 characters")
                }
                addQueryParameter("q", query.trim())
            }
            addQueryParameter("type", "comic")
            if (page > 1) {
                addQueryParameter("cursor", searchNextCursor)
            }
        }.build()

        val data = client.get(url).parseAs<SearchResponse>()

        searchNextCursor = data.cursor

        return MangasPage(
            mangas = data.data.map(BrowseComic::toSManga),
            hasNextPage = data.cursor != null,
        )
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/comic/${manga.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val detailsDeferred = async {
            if (fetchDetails) {
                parseDetails(client.get(getMangaUrl(manga)))
            } else {
                manga
            }
        }
        val chaptersDeferred = async { if (fetchChapters) getChapterList(manga) else chapters }
        SMangaUpdate(detailsDeferred.await(), chaptersDeferred.await())
    }

    private fun parseDetails(response: Response): SManga {
        val data = response.asJsoup()
            .selectFirst("#comic-data")!!.data()
            .parseAs<ComicData>()

        return data.toSManga(baseUrl)
    }

    private suspend fun getChapterList(manga: SManga): List<SChapter> {
        val url = "$baseUrl/api/comics/${manga.url}/chapter-list?lang=en"

        val data = client.get(url).parseAs<ChapterList>()
        val chapters = data.data.toMutableList()

        coroutineScope {
            (2..data.pagination.lastPage).map {
                async {
                    val pageUrl = url.toHttpUrl().newBuilder()
                        .addQueryParameter("page", it.toString())
                        .build()

                    client.get(pageUrl).parseAs<ChapterList>().data
                }
            }.awaitAll().forEach(chapters::addAll)
        }

        return chapters.filter { it.lang == "en" }.map { it.toSChapter(manga.url) }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val data = client.get(getChapterUrl(chapter)).asJsoup()
            .selectFirst("#sv-data")!!.data()
            .parseAs<PageListData>()

        return data.chapter.images.mapIndexed { index, image ->
            Page(index, imageUrl = image.url)
        }
    }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData() = client.get("$baseUrl/api/metadata").parseAs<Metadata>().toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList {
        val filters = mutableListOf<Filter<*>>(
            SortFilter(),
            DemographicFilter(),
            TypeFilter(),
        )

        val metadata = data?.parseAs<Metadata>()
        metadata?.genres?.takeIf { it.isNotEmpty() }?.let { filters.add(GenreFilter(it)) }
        metadata?.tags?.takeIf { it.isNotEmpty() }?.let { filters.add(TagFilters(it)) }

        filters.addAll(
            listOf(
                Filter.Separator(),
                Filter.Header("Separate tags with commas (,)"),
                Filter.Header("Prepend with dash (-) to exclude"),
                TagFilterText(),
                Filter.Separator(),
                CreatedAtFilter(),
                MinimumChaptersFilter(),
                StatusFilter(),
                ContentRatingFilter(),
                ReleaseFrom(),
                ReleaseTo(),
            ),
        )

        return FilterList(filters)
    }
}
