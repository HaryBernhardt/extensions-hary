package eu.kanade.tachiyomi.extension.en.comickdohary

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer
import org.jsoup.Jsoup
import kotlin.time.Instant

@Serializable
class Data<T>(
    val data: T,
)

@Serializable
class SearchResponse(
    val data: List<BrowseComic>,
    @SerialName("next_cursor")
    val cursor: String? = null,
)

@Serializable
class BrowseComic(
    @SerialName("default_thumbnail")
    private val thumbnail: String? = null,
    private val slug: String,
    private val title: String,
) {
    fun toSManga() = SManga.create().apply {
        url = slug
        title = this@BrowseComic.title
        thumbnail_url = thumbnail
    }
}

@Serializable
class Metadata(
    val genres: List<Name>,
    val tags: List<Name>,
) {
    @Serializable
    class Name(
        val name: String,
        val slug: String,
    )
}

private val whitespaceRegex = Regex("\\s+")
private val periodBreakRegex = Regex("(?<=[^.]{12})(?<!\\bMr|\\bMs|\\bMrs|\\bDr|\\bProf|\\bSr|\\bJr|\\bVol|\\bCh)\\.\\s+")
private val colonBreakRegex = Regex("(?<=[^:]{12})(?<!\\b[a-zA-Z]{1,10}):\\s+")

@Serializable
class ComicData(
    private val title: String,
    private val slug: String,
    @SerialName("default_thumbnail")
    private val thumbnail: String? = null,
    private val status: Int? = null,
    @SerialName("translation_completed")
    private val translationCompleted: Boolean = false,
    private val artists: List<Name> = emptyList(),
    private val authors: List<Name> = emptyList(),
    private val desc: String? = null,
    @SerialName("content_rating")
    private val contentRating: String? = null,
    private val country: String? = null,
    @SerialName("md_comic_md_genres")
    private val genres: List<Genres> = emptyList(),
    @SerialName("md_titles")
    @Serializable(with = TitleTransform::class)
    private val titles: List<Title> = emptyList(),
) {
    @Serializable
    class Name(
        val name: String,
    )

    @Serializable
    class Title(
        val title: String,
    )

    @Serializable
    class Genres(
        @SerialName("md_genres")
        val genres: Name,
    )

    fun toSManga(baseUrl: String) = SManga.create().apply {
        title = this@ComicData.title
        url = slug
        thumbnail_url = thumbnail
        status = when (this@ComicData.status) {
            1 -> SManga.ONGOING
            2 -> if (translationCompleted) SManga.COMPLETED else SManga.PUBLISHING_FINISHED
            3 -> SManga.CANCELLED
            4 -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        author = authors.joinToString { it.name }
        artist = artists.joinToString { it.name }
        description = buildString {
            if (!desc.isNullOrBlank()) {
                val parsedDesc = Jsoup.parseBodyFragment(desc, baseUrl).wholeText()
                    .replace(whitespaceRegex, " ")
                    .replace(periodBreakRegex, ".\n\n")
                    .replace(colonBreakRegex, ":\n\n")
                    .trim()
                append(parsedDesc)
            }

            if (titles.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative Titles:\n")
                titles.forEach {
                    append("- ", it.title.trim(), "\n")
                }
            }
        }.trim()
        genre = buildList {
            when (country) {
                "jp" -> add("Manga")
                "cn" -> add("Manhua")
                "ko" -> add("Manhwa")
            }
            when (contentRating) {
                "suggestive" -> add("Content Rating: Suggestive")
                "erotica" -> add("Content Rating: Erotica")
            }
            addAll(genres.map { it.genres.name })
        }.joinToString()
    }
}

object TitleTransform : JsonTransformingSerializer<List<ComicData.Title>>(
    ListSerializer(ComicData.Title.serializer()),
) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonObject) return element
        return JsonArray(element.values.toList())
    }
}

@Serializable
class ChapterList(
    val data: List<Chapter>,
    val pagination: Pagination,
) {
    @Serializable
    class Chapter(
        private val hid: String,
        private val chap: String? = null,
        private val vol: String? = null,
        val lang: String,
        private val title: String? = null,
        @SerialName("created_at")
        private val createdAt: String? = null,
        @SerialName("group_name")
        private val groups: List<String> = emptyList(),
    ) {
        fun toSChapter(mangaSlug: String) = SChapter.create().apply {
            val chapStr = chap.orEmpty()
            url = "/comic/$mangaSlug/$hid-chapter-$chapStr-$lang"
            name = buildString {
                if (!vol.isNullOrBlank()) {
                    append("Vol. ", vol, " ")
                }
                if (chapStr.isNotBlank()) {
                    append("Ch. ", chapStr)
                } else {
                    append("Chapter")
                }
                if (!title.isNullOrBlank()) {
                    append(": ", title)
                }
            }
            chap?.toFloatOrNull()?.let { chapter_number = it }
            date_upload = Instant.tryParse(createdAt)
            scanlator = groups.joinToString()
        }
    }

    @Serializable
    class Pagination(
        @SerialName("last_page")
        val lastPage: Int,
    )
}

@Serializable
class PageListData(
    val chapter: ChapterData,
) {
    @Serializable
    class ChapterData(
        val images: List<Image>,
    ) {
        @Serializable
        class Image(
            val url: String,
        )
    }
}
