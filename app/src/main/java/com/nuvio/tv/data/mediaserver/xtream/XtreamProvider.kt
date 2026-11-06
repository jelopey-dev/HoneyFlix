package com.nuvio.tv.data.mediaserver.xtream

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.data.mediaserver.ServerCandidate
import com.nuvio.tv.data.mediaserver.ServerCapability
import com.nuvio.tv.data.mediaserver.ServerEpisode
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerIndexEntry
import com.nuvio.tv.data.mediaserver.ServerItemDetails
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerLibrary
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerPage
import com.nuvio.tv.data.mediaserver.ServerPlayMethod
import com.nuvio.tv.data.mediaserver.ServerPlaybackRequest
import com.nuvio.tv.data.mediaserver.ServerPlaybackSession
import com.nuvio.tv.data.mediaserver.ServerPlaybackTarget
import com.nuvio.tv.data.mediaserver.ServerProvider
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.data.mediaserver.ServerSignIn
import com.nuvio.tv.data.mediaserver.ServerTitle
import com.nuvio.tv.data.mediaserver.ServerTitleQuery
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * HoneyFlix: an IPTV provider's movies & series (Xtream Codes) as a media server.
 *
 * Signing in uses the provider's address, username and password. Titles you open from the
 * normal catalogs are matched to the provider's library by TMDB id when the provider lists one,
 * otherwise by name and year, and the provider's copy is offered first (add-on sources follow
 * in your add-on order as backups). The provider's own Movies / Series also show as libraries.
 *
 * Item ids: "m.<streamId>.<ext>" movie, "s.<seriesId>" series, "e.<episodeId>.<ext>" episode.
 * The password is the stored session token; it is never logged.
 */
internal class XtreamProvider(private val http: OkHttpClient, private val cacheDir: java.io.File) : ServerProvider {
    // Big lists can take a while on a slow provider: no overall time limit, just a generous read timeout.
    private val bigHttp = http.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()
    private val backgroundScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val wanted = setOf("name", "stream_id", "series_id", "container_extension", "stream_icon", "cover",
        "tmdb", "tmdb_id", "year", "releaseDate", "release_date", "rating", "added", "last_modified", "genre")
    override val id: String = "xtream"
    override val displayName: String = "IPTV provider (Xtream Codes)"
    override val capabilities: Set<ServerCapability> = setOf(ServerCapability.EXTERNAL_ID_LOOKUP, ServerCapability.SEARCH)
    override val minimumVersion: String = "1"

    private data class Entry(
        val id: String, val name: String, val icon: String?, val tmdb: Long?, val year: Int?,
        val ext: String, val rating: Float?, val added: Long, val plot: String?, val genre: String?
    )
    private class Listing(val at: Long, val items: List<Entry>)
    private val lists = ConcurrentHashMap<String, Listing>()
    private val listLocks = ConcurrentHashMap<String, Mutex>()
    private val seriesInfo = ConcurrentHashMap<String, Pair<Long, JSONObject>>()

    // ---------- Sign in ----------

    override suspend fun signIn(address: String, username: String, password: String): ServerSignIn {
        val base = normalize(address) ?: throw ServerException(ServerFailure.NOT_FOUND, "That doesn't look like a web address")
        val user = username.trim()
        val json = getJson(base, user, password, null) as? JSONObject
            ?: throw ServerException(ServerFailure.FAILED, "That doesn't look like an Xtream Codes server")
        val info = json.optJSONObject("user_info") ?: throw ServerException(ServerFailure.FAILED, "That doesn't look like an Xtream Codes server")
        if (info.optString("auth") != "1") throw ServerException(ServerFailure.AUTH_REQUIRED, "Wrong username or password")
        val status = info.optString("status")
        if (status.isNotBlank() && !status.equals("Active", true)) throw ServerException(ServerFailure.FORBIDDEN, "Account is $status")
        val url = base.toHttpUrlOrNull()!!
        return ServerSignIn(
            address = base,
            serverName = url.host,
            serverId = "${url.host}:${url.port}",
            userId = user,
            userName = user,
            token = password
        )
    }

    override suspend fun libraries(session: ServerSession): List<ServerLibrary> = also { warmUp(session) }.let { listOf(
        ServerLibrary(id = MOVIES, name = "Movies", kind = ServerMediaKind.MOVIE),
        ServerLibrary(id = SERIES, name = "Series", kind = ServerMediaKind.SERIES)
    ) }

    // ---------- Browsing ----------

    override suspend fun libraryPage(session: ServerSession, library: ServerLibrary, start: Int, limit: Int): ServerPage<ServerTitle> {
        val all = list(session, library.kind).sortedByDescending { it.added }
        return ServerPage(all.drop(start).take(limit).map { title(session, library.kind, it) }, all.size)
    }

    override suspend fun search(session: ServerSession, kind: ServerMediaKind, libraries: List<ServerLibrary>, query: String, limit: Int): List<ServerTitle> {
        val q = norm(query).ifBlank { return emptyList() }
        val kinds = if (kind == ServerMediaKind.MIXED) listOf(ServerMediaKind.MOVIE, ServerMediaKind.SERIES) else listOf(kind)
        return kinds.filter { k -> libraries.any { it.holds(k) } }.flatMap { k ->
            list(session, k).filter { norm(it.name).contains(q) }.take(limit).map { title(session, k, it) }
        }.take(limit)
    }

    private fun title(session: ServerSession, kind: ServerMediaKind, e: Entry) = ServerTitle(
        MetaPreview(
            id = ServerItemRef(session.connection.id, e.id).encode(),
            type = if (kind == ServerMediaKind.SERIES) ContentType.SERIES else ContentType.MOVIE,
            name = cleanName(e.name),
            poster = e.icon,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = e.plot,
            releaseInfo = e.year?.toString(),
            imdbRating = e.rating,
            genres = e.genre?.split(',', '/')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        ),
        TrackingExternalIds(tmdb = e.tmdb)
    )

    override suspend fun details(session: ServerSession, itemId: String): ServerItemDetails {
        val ref = ServerItemRef(session.connection.id, itemId).encode()
        return when {
            itemId.startsWith("m.") -> {
                val streamId = itemId.split('.')[1]
                val json = getJson(session, "get_vod_info", "vod_id" to streamId) as? JSONObject ?: throw ServerException(ServerFailure.NOT_FOUND)
                val info = json.optJSONObject("info") ?: JSONObject()
                val entry = list(session, ServerMediaKind.MOVIE).firstOrNull { it.id == itemId }
                val tmdb = info.optString("tmdb_id").toLongOrNull() ?: entry?.tmdb
                ServerItemDetails(
                    meta = Meta(
                        id = ref, type = ContentType.MOVIE, name = cleanName(info.optString("name").ifBlank { entry?.name.orEmpty() }),
                        poster = info.optString("movie_image").ifBlank { null } ?: entry?.icon, posterShape = PosterShape.POSTER,
                        background = info.optJSONArray("backdrop_path")?.optString(0)?.ifBlank { null }, logo = null,
                        description = info.optString("plot").ifBlank { info.optString("description") }.ifBlank { null },
                        releaseInfo = yearOf(info.optString("releasedate")) ?.toString() ?: entry?.year?.toString(),
                        imdbRating = info.optString("rating").toFloatOrNull() ?: entry?.rating,
                        genres = info.optString("genre").split(',', '/').map { it.trim() }.filter { it.isNotEmpty() },
                        runtime = info.optString("duration").ifBlank { null },
                        director = info.optString("director").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        cast = info.optString("cast").ifBlank { info.optString("actors") }.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        videos = emptyList(), country = info.optString("country").ifBlank { null }, awards = null, language = null,
                        links = emptyList(), released = info.optString("releasedate").ifBlank { null }
                    ),
                    externalIds = TrackingExternalIds(tmdb = tmdb)
                )
            }
            itemId.startsWith("s.") -> {
                val sid = itemId.removePrefix("s.")
                val json = seriesJson(session, sid)
                val info = json.optJSONObject("info") ?: JSONObject()
                val entry = list(session, ServerMediaKind.SERIES).firstOrNull { it.id == itemId }
                val videos = episodes(json).map { (season, ep) ->
                    Video(
                        id = ServerItemRef(session.connection.id, "e.${ep.optString("id")}.${ep.optString("container_extension").ifBlank { "mp4" }}").encode(),
                        title = ep.optString("title").ifBlank { "Episode ${ep.optInt("episode_num")}" },
                        released = ep.optJSONObject("info")?.optString("air_date")?.ifBlank { null },
                        thumbnail = ep.optJSONObject("info")?.optString("movie_image")?.ifBlank { null },
                        season = season, episode = ep.optInt("episode_num"),
                        overview = ep.optJSONObject("info")?.optString("plot")?.ifBlank { null }
                    )
                }
                ServerItemDetails(
                    meta = Meta(
                        id = ref, type = ContentType.SERIES, name = cleanName(info.optString("name").ifBlank { entry?.name.orEmpty() }),
                        poster = info.optString("cover").ifBlank { null } ?: entry?.icon, posterShape = PosterShape.POSTER,
                        background = info.optJSONArray("backdrop_path")?.optString(0)?.ifBlank { null }, logo = null,
                        description = info.optString("plot").ifBlank { null },
                        releaseInfo = yearOf(info.optString("releaseDate").ifBlank { info.optString("release_date") })?.toString() ?: entry?.year?.toString(),
                        imdbRating = info.optString("rating").toFloatOrNull(),
                        genres = info.optString("genre").split(',', '/').map { it.trim() }.filter { it.isNotEmpty() },
                        runtime = null,
                        director = info.optString("director").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        cast = info.optString("cast").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        videos = videos, country = null, awards = null, language = null, links = emptyList()
                    ),
                    externalIds = TrackingExternalIds(tmdb = info.optString("tmdb").toLongOrNull() ?: entry?.tmdb)
                )
            }
            else -> throw ServerException(ServerFailure.NOT_FOUND)
        }
    }

    // ---------- Matching catalog titles to the provider ----------

    override suspend fun externalIdIndex(session: ServerSession, library: ServerLibrary, start: Int, limit: Int): ServerPage<ServerIndexEntry> {
        val all = list(session, library.kind).filter { it.tmdb != null }
        return ServerPage(all.drop(start).take(limit).map { ServerIndexEntry(it.id, TrackingExternalIds(tmdb = it.tmdb), it.year) }, all.size)
    }

    // ---------- Diagnostics (temporary) ----------
    // When a title isn't matched, a non-playable "Your provider · not found" row explains why, so a
    // photo of the source list tells us what went wrong on a provider we can't test against.
    private val notes = ConcurrentHashMap<String, String>()
    private val noteSeq = java.util.concurrent.atomic.AtomicInteger()
    private fun note(msg: String, query: ServerTitleQuery): ServerIndexEntry {
        val id = "x.${noteSeq.incrementAndGet()}"
        notes[id] = msg
        return ServerIndexEntry(id, query.ids, query.year)
    }

    override suspend fun lookupTitle(session: ServerSession, library: ServerLibrary, query: ServerTitleQuery): List<ServerIndexEntry> {
        val what = if (library.kind == ServerMediaKind.SERIES) "series" else "movies"
        val looked = "“${query.name ?: "?"}” ${query.year ?: ""} · TMDB ${query.ids.tmdb ?: "none"}"
        return try {
            val items = list(session, library.kind)
            val found = findTitle(session, library, query, items)
            if (found.isNotEmpty()) found
            else {
                val withIds = items.count { it.tmdb != null }
                val near = query.name?.let(::norm)?.split(' ')?.firstOrNull { it.length > 3 }?.let { w ->
                    items.asSequence().filter { norm(it.name).contains(w) }.take(3).joinToString(" | ") { it.name }
                }.orEmpty()
                listOf(note("Not found in ${items.size} $what ($withIds with TMDB ids). Looked for $looked." +
                    (if (near.isNotEmpty()) " Similar: $near" else ""), query))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            listOf(note("Couldn't read the provider's $what: ${e.message ?: e.javaClass.simpleName}. Looked for $looked.", query))
        }
    }

    private suspend fun findTitle(session: ServerSession, library: ServerLibrary, query: ServerTitleQuery, items: List<Entry>): List<ServerIndexEntry> {
        // 1. The provider lists a TMDB id for the title.
        query.ids.tmdb?.let { tmdb ->
            val byId = items.filter { it.tmdb == tmdb }
            if (byId.isNotEmpty()) return byId.map { ServerIndexEntry(it.id, TrackingExternalIds(tmdb = tmdb), it.year) }
        }
        // 2. Same name (ignoring "EN - ", "(2010)", "4K" and the like) and a close year.
        val names = listOfNotNull(query.name, query.originalName).map(::norm).filter { it.isNotEmpty() }.toSet()
        if (names.isEmpty()) return emptyList()
        val byName = items.filter { norm(it.name) in names && yearsClose(query.year, it.year) }
            .filter { it.tmdb == null || query.ids.tmdb == null || it.tmdb == query.ids.tmdb }
            .take(6)
        if (byName.isEmpty()) return emptyList()
        // Movies: the provider's details may carry a TMDB id or a year that rules a match out.
        val checked = if (library.kind == ServerMediaKind.MOVIE) coroutineScope {
            byName.map { e -> async { e to runCatching { movieInfo(session, e) }.getOrNull() } }.awaitAll()
                .filter { (_, info) ->
                    info == null || ((info.first == null || query.ids.tmdb == null || info.first == query.ids.tmdb) && yearsClose(query.year, info.second))
                }.map { it.first }
        } else byName
        // Matched by name: give it the catalog's ids so the match is used.
        return checked.map { ServerIndexEntry(it.id, query.ids, it.year ?: query.year) }
    }

    private suspend fun movieInfo(session: ServerSession, e: Entry): Pair<Long?, Int?> {
        val json = getJson(session, "get_vod_info", "vod_id" to e.id.split('.')[1]) as? JSONObject ?: return null to null
        val info = json.optJSONObject("info") ?: return null to null
        return info.optString("tmdb_id").toLongOrNull() to yearOf(info.optString("releasedate"))
    }

    override suspend fun findEpisode(session: ServerSession, seriesItemId: String, season: Int, episode: Int): ServerEpisode? {
        if (seriesItemId.startsWith("x.")) return ServerEpisode(seriesItemId, null)
        val json = try { seriesJson(session, seriesItemId.removePrefix("s.")) } catch (e: ServerException) {
            val id = "x.${noteSeq.incrementAndGet()}"; notes[id] = "Series found, but its episode list didn't load: ${e.message ?: e.failure}"
            return ServerEpisode(id, null)
        }
        val eps = episodes(json)
        val ep = eps.firstOrNull { (s, e) -> s == season && e.optInt("episode_num") == episode }?.second ?: run {
            val id = "x.${noteSeq.incrementAndGet()}"
            notes[id] = "Series found (${eps.size} episodes), but not S${season}E$episode. Seasons: ${eps.map { it.first }.distinct().joinToString(",")}"
            return ServerEpisode(id, null)
        }
        return ServerEpisode(
            itemId = "e.${ep.optString("id")}.${ep.optString("container_extension").ifBlank { "mp4" }}",
            premiereDate = ep.optJSONObject("info")?.optString("air_date")?.ifBlank { null }
        )
    }

    // ---------- Playing ----------

    override suspend fun candidates(session: ServerSession, itemId: String): List<ServerCandidate> {
        if (itemId.startsWith("x.")) return listOf(ServerCandidate(
            target = ServerPlaybackTarget(ServerItemRef(session.connection.id, itemId), itemId),
            title = "Your provider · not found", description = notes[itemId] ?: "No match", filename = null, sizeBytes = null))
        val parts = itemId.split('.')
        if (parts.size < 3 || parts[0] !in setOf("m", "e")) return emptyList()
        val name = if (parts[0] == "m") list(session, ServerMediaKind.MOVIE).firstOrNull { it.id == itemId }?.name?.let(::cleanName) else null
        val q = quality(name ?: "")
        return listOf(
            ServerCandidate(
                target = ServerPlaybackTarget(ServerItemRef(session.connection.id, itemId), itemId),
                title = listOfNotNull("Your provider", q).joinToString(" · "),
                description = listOfNotNull(name, parts[2].uppercase()).joinToString(" • "),
                filename = null,
                sizeBytes = null
            )
        )
    }

    override suspend fun preparePlayback(session: ServerSession, request: ServerPlaybackRequest): ServerPlaybackSession {
        val itemId = request.target.item.itemId
        val parts = itemId.split('.')
        if (parts.size < 3) throw ServerException(ServerFailure.NOT_FOUND)
        val path = if (parts[0] == "m") "movie" else "series"
        val base = session.connection.address.trimEnd('/')
        val url = "$base/$path/${enc(session.connection.userName)}/${enc(session.token)}/${parts[1]}.${parts[2]}"
        return ServerPlaybackSession(
            target = request.target,
            mediaSourceId = itemId,
            url = url,
            headers = emptyMap(),
            subtitles = emptyList(),
            playSessionId = null,
            playMethod = ServerPlayMethod.DIRECT_PLAY
        )
    }

    // ---------- Provider API ----------

    /**
     * The provider's whole movie or series list. These can be tens of megabytes, so the reply is read
     * as a stream (only the fields we use are kept), and saved on the box so later launches have it
     * at once. A saved copy older than [LIST_TTL_MS] is still used while a fresh one loads.
     */
    private suspend fun list(session: ServerSession, kind: ServerMediaKind): List<Entry> {
        val key = "${session.connection.id}:${kind.name}"
        lists[key]?.let { cached ->
            if (System.currentTimeMillis() - cached.at > LIST_TTL_MS) refreshInBackground(session, kind, key)
            return cached.items
        }
        return listLocks.getOrPut(key) { Mutex() }.withLock {
            lists[key]?.let { return@withLock it.items }
            readDisk(key)?.let { saved ->
                lists[key] = saved
                if (System.currentTimeMillis() - saved.at > LIST_TTL_MS) refreshInBackground(session, kind, key)
                return@withLock saved.items
            }
            download(session, kind, key)
        }
    }

    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    private fun refreshInBackground(session: ServerSession, kind: ServerMediaKind, key: String) {
        if (!refreshing.add(key)) return
        backgroundScope.launch {
            try { listLocks.getOrPut(key) { Mutex() }.withLock { download(session, kind, key) } } catch (_: Exception) {} finally { refreshing.remove(key) }
        }
    }

    /** Starts loading both lists (after sign-in / when HoneyFlix opens), so the first movie doesn't wait. */
    private fun warmUp(session: ServerSession) {
        for (k in listOf(ServerMediaKind.MOVIE, ServerMediaKind.SERIES)) {
            val key = "${session.connection.id}:${k.name}"
            if (lists.containsKey(key)) continue
            backgroundScope.launch { runCatching { list(session, k) } }
        }
    }

    private suspend fun download(session: ServerSession, kind: ServerMediaKind, key: String): List<Entry> = withContext(Dispatchers.IO) {
        val series = kind == ServerMediaKind.SERIES
        val url = ("${session.connection.address}/player_api.php").toHttpUrlOrNull()?.newBuilder()?.apply {
            addQueryParameter("username", session.connection.userName); addQueryParameter("password", session.token)
            addQueryParameter("action", if (series) "get_series" else "get_vod_streams")
        }?.build() ?: throw ServerException(ServerFailure.NOT_FOUND)
        val req = Request.Builder().url(url).header("User-Agent", "HoneyFlix/1.0").build()
        val items = try {
            bigHttp.newCall(req).execute().use { r ->
                if (r.code == 401 || r.code == 403) throw ServerException(ServerFailure.AUTH_REQUIRED)
                if (!r.isSuccessful) throw ServerException(ServerFailure.UNREACHABLE, "The provider returned error ${r.code}")
                val body = r.body ?: throw ServerException(ServerFailure.UNREACHABLE)
                parseList(android.util.JsonReader(body.charStream()), series)
            }
        } catch (e: ServerException) { throw e } catch (e: Exception) {
            throw ServerException(ServerFailure.UNREACHABLE, "Couldn't load the provider's ${if (series) "series" else "movies"}")
        }
        val listing = Listing(System.currentTimeMillis(), items)
        lists[key] = listing
        runCatching { writeDisk(key, listing) }
        items
    }

    private fun parseList(reader: android.util.JsonReader, series: Boolean): List<Entry> {
        val out = ArrayList<Entry>(8192)
        reader.isLenient = true
        if (reader.peek() != android.util.JsonToken.BEGIN_ARRAY) return out
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != android.util.JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue }
            val f = HashMap<String, String>(16)
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name in wanted) {
                    when (reader.peek()) {
                        android.util.JsonToken.STRING -> f[name] = reader.nextString()
                        android.util.JsonToken.NUMBER -> f[name] = reader.nextString()
                        android.util.JsonToken.BOOLEAN -> f[name] = reader.nextBoolean().toString()
                        else -> reader.skipValue()
                    }
                } else reader.skipValue()
            }
            reader.endObject()
            val name = f["name"]?.trim().orEmpty()
            if (name.isEmpty()) continue
            val tmdb = f["tmdb"]?.toLongOrNull() ?: f["tmdb_id"]?.toLongOrNull()
            out += if (series) Entry(
                id = "s.${f["series_id"].orEmpty()}", name = name, icon = f["cover"]?.ifBlank { null }, tmdb = tmdb,
                year = yearOf(f["releaseDate"] ?: f["release_date"]) ?: yearOf(f["year"]) ?: yearInName(name),
                ext = "", rating = f["rating"]?.toFloatOrNull()?.takeIf { it > 0 },
                added = f["last_modified"]?.toLongOrNull() ?: 0, plot = null, genre = f["genre"]?.ifBlank { null }
            ) else Entry(
                id = "m.${f["stream_id"].orEmpty()}.${f["container_extension"]?.ifBlank { null } ?: "mp4"}", name = name,
                icon = f["stream_icon"]?.ifBlank { null }, tmdb = tmdb,
                year = yearOf(f["year"]) ?: yearInName(name),
                ext = f["container_extension"].orEmpty(), rating = f["rating"]?.toFloatOrNull()?.takeIf { it > 0 },
                added = f["added"]?.toLongOrNull() ?: 0, plot = null, genre = null
            )
        }
        reader.endArray()
        return out
    }

    // Saved copy: one line per title, tab-separated (no passwords or links are stored).
    private fun diskFile(key: String) = java.io.File(cacheDir, "xtream_" + key.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".tsv")
    private fun writeDisk(key: String, l: Listing) {
        cacheDir.mkdirs()
        val tmp = java.io.File(cacheDir, diskFile(key).name + ".tmp")
        tmp.bufferedWriter().use { w ->
            w.write("v1\t${l.at}\n")
            for (e in l.items) {
                w.write(listOf(e.id, e.name, e.icon.orEmpty(), e.tmdb?.toString().orEmpty(), e.year?.toString().orEmpty(),
                    e.ext, e.rating?.toString().orEmpty(), e.added.toString(), e.genre.orEmpty())
                    .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') })
                w.write("\n")
            }
        }
        tmp.renameTo(diskFile(key))
    }
    private fun readDisk(key: String): Listing? = runCatching {
        val f = diskFile(key); if (!f.exists()) return null
        f.bufferedReader().use { r ->
            val head = r.readLine()?.split('\t') ?: return null
            if (head.firstOrNull() != "v1") return null
            val at = head.getOrNull(1)?.toLongOrNull() ?: 0L
            val items = ArrayList<Entry>(8192)
            r.forEachLine { line ->
                val c = line.split('\t'); if (c.size < 9) return@forEachLine
                items += Entry(c[0], c[1], c[2].ifEmpty { null }, c[3].toLongOrNull(), c[4].toIntOrNull(), c[5],
                    c[6].toFloatOrNull(), c[7].toLongOrNull() ?: 0, null, c[8].ifEmpty { null })
            }
            Listing(at, items)
        }
    }.getOrNull()

    private suspend fun seriesJson(session: ServerSession, seriesId: String): JSONObject {
        val key = "${session.connection.id}:$seriesId"
        seriesInfo[key]?.takeIf { System.currentTimeMillis() - it.first < LIST_TTL_MS }?.let { return it.second }
        val json = getJson(session, "get_series_info", "series_id" to seriesId) as? JSONObject ?: throw ServerException(ServerFailure.NOT_FOUND)
        seriesInfo[key] = System.currentTimeMillis() to json
        return json
    }

    /** (season, episode json) pairs; providers send episodes as a map of season → list (or a list of lists). */
    private fun episodes(json: JSONObject): List<Pair<Int, JSONObject>> {
        val out = ArrayList<Pair<Int, JSONObject>>()
        when (val eps = json.opt("episodes")) {
            is JSONObject -> eps.keys().forEach { k ->
                val arr = eps.optJSONArray(k) ?: return@forEach
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { e -> out += (e.optString("season").toIntOrNull() ?: k.toIntOrNull() ?: 0) to e }
            }
            is JSONArray -> for (i in 0 until eps.length()) {
                val arr = eps.optJSONArray(i) ?: continue
                for (j in 0 until arr.length()) arr.optJSONObject(j)?.let { e -> out += (e.optString("season").toIntOrNull() ?: (i + 1)) to e }
            }
        }
        return out.sortedWith(compareBy({ it.first }, { it.second.optInt("episode_num") }))
    }

    private suspend fun getJson(session: ServerSession, action: String, vararg extra: Pair<String, String>): Any? =
        getJson(session.connection.address, session.connection.userName, session.token, action, *extra)

    private suspend fun getJson(base: String, user: String, pass: String, action: String?, vararg extra: Pair<String, String>): Any? =
        withContext(Dispatchers.IO) {
            val url: HttpUrl = ("$base/player_api.php").toHttpUrlOrNull()?.newBuilder()?.apply {
                addQueryParameter("username", user); addQueryParameter("password", pass)
                action?.let { addQueryParameter("action", it) }
                extra.forEach { (k, v) -> addQueryParameter(k, v) }
            }?.build() ?: throw ServerException(ServerFailure.NOT_FOUND)
            val req = Request.Builder().url(url).header("User-Agent", "HoneyFlix/1.0").build()
            val body = try {
                http.newCall(req).execute().use { r ->
                    if (r.code == 401 || r.code == 403) throw ServerException(ServerFailure.AUTH_REQUIRED)
                    if (!r.isSuccessful) throw ServerException(ServerFailure.UNREACHABLE, "The provider returned error ${r.code}")
                    r.body?.string().orEmpty()
                }
            } catch (e: ServerException) { throw e } catch (e: Exception) {
                throw ServerException(ServerFailure.UNREACHABLE, "Couldn't reach the provider")
            }
            val t = body.trim()
            when {
                t.startsWith("[") -> JSONArray(t)
                t.startsWith("{") -> JSONObject(t)
                else -> null
            }
        }

    private companion object {
        const val MOVIES = "movies"
        const val SERIES = "series"
        const val LIST_TTL_MS = 6 * 3_600_000L

        fun normalize(input: String): String? {
            var v = input.trim().trimEnd('/')
            if (v.isEmpty()) return null
            if (!v.contains("://")) v = "http://$v"
            v = v.substringBefore("/player_api.php").substringBefore("/get.php").trimEnd('/')
            val url = v.toHttpUrlOrNull() ?: return null
            return url.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')
        }

        fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        // "EN - ", "|EN| ", "[EN] ", "4K-EN | " style prefixes (capitals only, so "Saw: X" is left alone).
        private val prefix = Regex("^\\s*(\\|[A-Z0-9]{2,4}\\|\\s*|\\[[A-Z0-9]{2,4}]\\s*|[A-Z0-9]{2,4}\\s*[-|]\\s+)+")
        private val tags = Regex("\\b(4k|uhd|fhd|hd|sd|1080p|720p|2160p|hevc|x265|h265|multi|vostfr|dual|sub|dub)\\b", RegexOption.IGNORE_CASE)
        private val yearParen = Regex("[(\\[]\\s*(19|20)\\d{2}\\s*[)\\]]")

        /** Display name: drop "EN - " style prefixes. */
        fun cleanName(name: String): String = name.replace(prefix, "").trim().ifBlank { name }

        /** Comparable form of a title. */
        fun norm(name: String): String {
            val base = cleanName(name).replace(yearParen, " ").replace(tags, " ")
            return Normalizer.normalize(base, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
                .lowercase().replace("&", " and ").replace(Regex("[^a-z0-9]+"), " ")
                .replace(Regex("^(the|a|an) "), "").trim()
        }

        fun yearOf(s: String?): Int? = s?.let { Regex("\\b(19|20)\\d{2}\\b").find(it)?.value?.toIntOrNull() }
        fun yearInName(name: String): Int? = yearParen.find(name)?.value?.let { yearOf(it) }
        fun quality(name: String): String? = when {
            Regex("\\b(4k|uhd|2160p)\\b", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "4K"
            Regex("\\b(fhd|1080p)\\b", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "1080p"
            Regex("\\b720p\\b", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "720p"
            else -> null
        }
        fun yearsClose(a: Int?, b: Int?): Boolean = a == null || b == null || abs(a - b) <= 1
    }
}
