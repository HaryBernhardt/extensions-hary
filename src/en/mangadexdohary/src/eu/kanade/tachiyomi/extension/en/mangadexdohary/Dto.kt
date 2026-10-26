package eu.kanade.tachiyomi.extension.en.mangadexdohary

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.stringOrNull
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

@Serializable
class PaginatedResponseDto<T>(
    val data: List<T>,
    val limit: Int,
    val offset: Int,
    val total: Int,
) {
    val hasNextPage: Boolean
        get() = offset + limit < total
}

@Serializable
class SingleResponseDto<T>(
    val data: T,
)

@Serializable
class MangaDataDto(
    val id: String,
    private val attributes: MangaAttributesDto,
    private val relationships: List<RelationshipDto> = emptyList(),
) {
    fun toBasicSManga() = SManga.create().apply {
        url = "/manga/$id"
        title = attributes.resolveTitle()
        thumbnail_url = resolveCoverUrl()
    }

    fun toSManga() = toBasicSManga().apply {
        description = attributes.description.localizedString("en")
        author = relationships
            .filter { it.type == "author" }
            .mapNotNull { it.attributes?.name }
            .distinct()
            .joinToString()
        artist = relationships
            .filter { it.type == "artist" }
            .mapNotNull { it.attributes?.name }
            .distinct()
            .joinToString()
        status = when (attributes.status) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        genre = buildList {
            attributes.publicationDemographic?.replaceFirstChar { it.uppercase() }?.let(::add)
            attributes.tags
                .mapNotNull { it.attributes.name.localizedString("en") }
                .forEach(::add)
        }.joinToString()
    }

    private fun resolveCoverUrl(): String? {
        val fileName = relationships
            .firstOrNull { it.type == "cover_art" }
            ?.attributes
            ?.fileName
            ?: return null
        return "https://uploads.mangadex.org/covers/$id/$fileName"
    }
}

@Serializable
class MangaAttributesDto(
    val title: JsonElement,
    val altTitles: List<JsonObject> = emptyList(),
    val description: JsonElement? = null,
    val publicationDemographic: String? = null,
    val status: String? = null,
    val tags: List<TagDto> = emptyList(),
) {
    fun resolveTitle(): String {
        title.localizedString("en")?.let { return it }
        altTitles.firstNotNullOfOrNull { it["en"]?.stringOrNull }?.let { return it }
        return (title as? JsonObject)?.values?.firstNotNullOfOrNull { it.stringOrNull }
            ?: altTitles.firstNotNullOfOrNull { obj -> obj.values.firstNotNullOfOrNull { it.stringOrNull } }
            ?: ""
    }
}

@Serializable
class TagDto(
    val id: String,
    val attributes: TagAttributesDto,
)

@Serializable
class TagAttributesDto(
    val name: JsonElement,
)

@Serializable
class RelationshipDto(
    val id: String,
    val type: String,
    val attributes: RelationshipAttributesDto? = null,
)

@Serializable
class RelationshipAttributesDto(
    val name: String? = null,
    val fileName: String? = null,
    val username: String? = null,
)

@Serializable
class ChapterDataDto(
    private val id: String,
    private val attributes: ChapterAttributesDto,
    private val relationships: List<RelationshipDto> = emptyList(),
) {
    val isValid: Boolean
        get() = attributes.externalUrl == null && !attributes.isUnavailable && attributes.pages > 0

    fun toSChapter() = SChapter.create().apply {
        url = "/chapter/$id"
        name = buildList {
            attributes.volume?.takeIf { it.isNotBlank() }?.let { add("Vol.$it") }
            attributes.chapter?.takeIf { it.isNotBlank() }?.let { add("Ch.$it") }
            attributes.title?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) add("-")
                add(it)
            }
            if (isEmpty()) add("Oneshot")
        }.joinToString(" ")
        attributes.chapter?.toFloatOrNull()?.let { chapter_number = it }
        date_upload = Instant.tryParse(attributes.publishAt)
        scanlator = relationships
            .filter { it.type == "scanlation_group" }
            .mapNotNull { it.attributes?.name }
            .joinToString(" & ")
            .ifEmpty {
                relationships
                    .filter { it.type == "user" }
                    .mapNotNull { it.attributes?.username }
                    .joinToString(" & ")
            }
            .ifEmpty { "No Group" }
    }
}

@Serializable
class ChapterAttributesDto(
    val title: String? = null,
    val volume: String? = null,
    val chapter: String? = null,
    val pages: Int = 0,
    val publishAt: String? = null,
    val externalUrl: String? = null,
    val isUnavailable: Boolean = false,
)

@Serializable
class AtHomeDto(
    val baseUrl: String,
    val chapter: AtHomeChapterDto,
) {
    @Serializable
    class AtHomeChapterDto(
        val hash: String,
        val data: List<String>,
    )
}

private fun JsonElement?.localizedString(lang: String): String? {
    val obj = this as? JsonObject ?: return null
    return obj[lang]?.stringOrNull ?: obj.values.firstNotNullOfOrNull { it.stringOrNull }
}
