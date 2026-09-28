# CODEX.md — BingeCloud symbol → file map

<!-- ============================================================ -->
<!--  HOW TO USE                                                   -->
<!--  1. Ctrl+F / browser find for a symbol name                   -->
<!--  2. Look at the "## file" or "path:" line directly above     -->
<!--  3. That's the file to edit                                   -->
<!-- ============================================================ -->
<!--  HOW TO UPDATE                                                -->
<!--  - Add symbol:  add a new bullet under the right "## file"   -->
<!--  - Rename:      edit the bullet                               -->
<!--  - Move:        cut bullet, paste under new "## file"        -->
<!--  - Delete:      remove the bullet                             -->
<!--  Format: one bullet per symbol, short purpose after —        -->
<!-- ============================================================ -->
<!--  STATUS MARKERS                                               -->
<!--  [DISABLED] = file/symbol not used, kept for revival         -->
<!--  [DEAD]     = unused, no revival planned                     -->
<!--  [BUG]      = known issue, needs fix                          -->
<!-- ============================================================ -->


## build.gradle.kts
path: build.gradle.kts
- buildscript — AGP 8.13.0, Kotlin 2.4.10, recloudstream gradle plugin
- allprojects — google, mavenCentral, jitpack

## settings.gradle.kts
path: settings.gradle.kts
- pluginManagement — plugin id → module mapping
- rootProject.name = "FLUMMOX-Repo"
- includes :BingeCloud and :Otakutsu

## gradle.properties
path: gradle.properties
- bingecloud_version — SINGLE SOURCE OF TRUTH for plugin version
- otakutsu_version — plugin version for Otakutsu

## patch_plugins.py
path: patch_plugins.py
- reads NEW_VERSION, OUTPUT_BRANCH, GITHUB_REPOSITORY env
- rewrites builds/plugins.json URLs + versions
- [v2] also reads src/gradle.properties → {internalName.lower()}_version per plugin


## BingeCloud/build.gradle.kts
path: BingeCloud/build.gradle.kts
- android — namespace com.flummox.bingecloud, compileSdk 35, minSdk 21
- defaultConfig — TMDB_API_KEY, TVDB_API_KEY, PLUGIN_VERSION from env
- cloudstream — description, authors, tvTypes, version from bingecloud_version
- dependencies — cloudstream3 pre-release, NiceHttp, jsoup, okhttp, jackson, coroutines


## package: com.flummox.bingecloud
## path: BingeCloud/src/main/java/com/flummox/bingecloud/


## AiometaApi.kt
- AIOMETA_BASE — Aiometa metadata base URL
- AioCast, AioVideo, AioAppExtras, AioMeta, AioMetaResponse, AioCatalogResponse — data models (AioMeta has originalLanguage field)
- aioFetchMeta — fetch single meta by type/id
- aioFetchCatalog — fetch catalog page
- aioSearch — search Aiometa
- TmdbDiscoverItem, TmdbDiscoverResponse — TMDB models
- INDIAN_ONLY_PROVIDERS — set of provider IDs (122, 220, 237, 232)
- tmdbDiscover — TMDB discover with path cache
- tmdbDiscoverFetch — raw fetch helper
- tmdbDiscoverMerged — movie+tv merged for provider
- tmdbTrendingDirect — TMDB /trending/day
- tmdbDetailMeta — full TMDB detail (fallback for Aiometa)
- tmdbDiscoverByLanguage — TMDB by original_language
- tmdbHindiSeriesClean — Hindi premium series pool
- fetchHindiPool — internal pool fetch
- TmdbDiscoverItem.toAioMeta — mapper


## AniListApi.kt
- ENDPOINT — https://graphql.anilist.co
- RX_PART_N, RX_SEASON_N — regexes for Part/Season parsing
- Title, Entry, Relation, PartInfo — data models
- searchAnime — GraphQL search, 10 hits, 24h cache
- getEntry — single entry by AniList ID
- resolveChain — walk prequel/sequel to full ordered chain
- parsePartInfo — extract Part N + Season N from title
- getPrequelOffset — sum same-franchise prequel episode counts
- normalizedBase — strip Part/Season for title comparison
- parseEntry, entryToJson, parseSearchResponse — helpers


## AniZoneApi.kt
- BASE — https://anizone.to
- RX_NON_ALNUM, RX_WS, RX_JSON_PARSE_TPL, RX_PLAYER, RX_CUT — regexes
- Hit, Episode, StreamResult — data models
- searchKey, epsKey, SEARCH_TTL, EPS_TTL — cache keys/TTLs
- unescapeJs, extractJsonParse, normalize — parsing helpers
- buildVariants — generate shortened query variants
- search — AniZone search (cached 30min)
- searchWithVariants [DEAD] — replaced by inline variant loop in resolve
- pickBest — pick best AniZone hit
- getEpisodes — episode list (cached 60min)
- getStream — resolve m3u8 from vidstackPlayer JSON
- pickBestAniList — pick best AniList entry by title/year
- resolve — main entry, uses AniList titles for anime
- mirror — ScrapedMirror builder


## AnikotoExtractors.kt
- MEGAPLAY_ENC_IV, MEGAPLAY_ENC_KEY, MEGAPLAY_TOKEN_SECRET — crypto constants
- ANIKOTO_PROXY_MAP — vault → uwu domain map
- anikotoProxyPlayerHost — URL rewrite
- anikotoGetHashM3u8 — fragment base64 decoder
- anikotoServerTypeLabel — sub/dub/hsub label
- b64UrlNoPad — base64url encoder
- anikotoSignMegaPlayUrl — HMAC token signer
- anikotoDecryptMegaPlaySources — AES decrypt
- anikotoExtractMegaPlayUrl — main player extractor
- AnikotoMegaPlay, AnikotoVidtube, AnikotoVidwish — ExtractorApi classes


## BCLog.kt
- init — file + buffer setup
- d, v, e, section — log levels (d=normal, v=verbose, e=error)
- allSanitized, count, clear — debug UI support
- sanitize — regex redaction (JWT, bearer, cookie, CF)
- setVerbose, isVerbose — toggle


## BingeCloudPlugin.kt
- BingeCloudPlugin — @CloudstreamPlugin entry point
- load — boot: RepoAnalytics.ping, BCLog.init, Prewarm.fire
- registerMainAPI(BingeCloudProvider)
- registerExtractorAPI: VCloud, GDirect, Filepress


## BingeCloudProvider.kt
- SEP, ROW_TAG, PREFETCH_DEBOUNCE_MS, HOME_GRACE_MS — constants
- PREFETCH_SCOPE, activePrefetchJob, lastHomeRenderMs — prefetch state
- StreamQuery.cacheKey — extension
- ADULT_TERMS, HOME_BLOCKED_GENRES — filter lists
- isAdultContent, isJunk — filters
- BingeCloudProvider — MainAPI class
  - getMainPage — row dispatch
  - resolveRow — row type → source
  - routeWestern, routeIndian, routeBanglaTVDB — routing
  - getHindiMergedPool, validateHindiSeries — Hindi pool
  - routeLanguageTVDB, routeLanguage — language rows
  - search, searchViaTmdb — search
  - mergeSearchResults, searchDedupeKey, searchGroupKey — merge + group + Part/season dedupe
  - isExtraTitle, seasonNumberOf — extra/season tier helpers
  - searchViaAiometa [DEAD — unused]
  - AioMeta.toSearchResponse, TmdbSearchItem.toSearchResponse — mappers
  - load — detail fetch (Aiometa → TMDB fallback)
  - loadLinks — mirror sort + emit
  - hostOf, qualityRank, audioPriority, computeStatusTag — helpers
- encodeQuery, decodeQuery — JSON serialize StreamQuery
- TmdbSearchResponse, TmdbSearchItem — data models
- RX_COUR — filter AniList "Cour N" entries at search boundary


## Cache.kt
- BCCache — LRU cachemodels
- response, 16 mirrors)
  - get, put, getMirrors, putMirrors, clear


## CfSolverDialog.kt
- CfResult — data model
- CfSolverDialog.resolve — main entry, WebView-based solver
- solve — dialog + WebView + JS bridge
- JS_INSTALL_OBSERVER, JS_DETECT_CF — injected scripts
- CfBridge — JS → Kotlin HTML push


## CloudflareHelper.kt
- BingeCloudCtx — context holder
- isCfChallenge — HTML marker check
- currentActivity — reflective Activity finder
- cloudflareGet — 3-stage GET (stored cookie, plain, solver)
- cloudflareGetDoc — Jsoup-wrapped variant


## Extractor.kt
- base64Decode, getBaseUrl, getIndexQuality, getLatestBaseUrl, resolveFinalUrl — utils
- TRAILING_QUALITY_REGEX, cleanServerName, shortenServer, isDirectFile, linkTypeFor — name helpers
- DEAD_HOSTS — skip list (gdflix.dev, hubcloud.cx)
- VCloud — ExtractorApi (sourceTag, mirrorLabel, qualityLabel, emojiPrefix)
- GDirect — Google Drive extractor
- Filepress — filepress extractor


## HostHealth.kt
- init — load from disk, start flush timer
- bonus — score contribution per host
- recordSuccess, recordFailure — update entries
- flushIfDirty — periodic disk write
- loadFromDisk — startup load


## JustWatchApi.kt
- JW_ENDPOINT — https://apis.justwatch.com/graphql
- JW_QUERY — GraphQL query
- jwDiscoverByProvider — filter by provider slug
- jwDiscoverByLanguage [DEAD] — JW doesn't support
- jwFetch — internal GraphQL POST + parse


## LinkScore.kt
- prelimScore — 0-100 score per mirror
- emoji — 🟢🟡🔴 bands
- hostOf — URL → host


## LogScrollbar.kt
- LogScrollbar — custom draggable scrollbar View for debug log box


## MlsbdApi.kt [DISABLED]
- MLSBD_BASE — https://mlsbd.co
- mlsbdFetch, mlsbdFetchVia, mlsbdSearch, mlsbdFindPage
- mlsbdResolveSavelinks, mlsbdExtractFromMulticloud, mlsbdExtractPlayerStream
- mlsbdDecodeBongHd — x_data decoder
- mlsbdResolveToMirrors, mlsbdExtractRaw
- REVIVAL NOTES in file header


## MlsbdDns.kt [DISABLED]
- MlsbdDns — custom OkHttp Dns resolver with DoH fallback


## MovieBoxApi.kt
- MB_SECRET_B64, MB_SECRET_ALT_B64, MB_VERSION_CODE, MB_VERSION_NAME, MB_PACKAGE, MB_INSTALL_STORE, MB_UA — DO NOT TOUCH
- MB_HOSTS, MB_BOOTSTRAP_HOST, MB_BOOTSTRAP_PATH
- deviceId, clientInfo — request fingerprint
- md5Hex, b64DecodeBytes, b64Encode — encoding helpers
- generateXClientToken, buildCanonicalString, generateXTrSignature, buildHeaders — signing
- parseJwtExp, restoreMbSession, bootstrapToken, ensureSession
- mbGet — GET with retry + cache
- MBSubject, MBStream — data models
- extractPolicyResource — decodes signCookie (old + new format)
- mbSearch, parseSearchResults — search
- mbDetail, mbLanguages — detail + audio tracks
- mbPlay — play-info, returns MBStream list


## Settings.kt
- RowSpec — data class for home row
- K_* — all pref keys (rows, sources, tokens, quality, prefilter, prefetch, verbose)
- K_ROW_STREAM_JIOCINEMA, K_ROW_STREAM_ZEE5 [DEAD] — declared but no RowSpec uses them
- ALL_ROWS — full list of RowSpec
- DEFAULT_ON_ROWS — set of keys enabled by default
- getRowOrder, setRowOrder, getRowSpecByKey, isRowEnabled, resetHomeToDefaults
- showResetConfirmDialog, showPasteTokenDialog — UI
- getConcurrency, isPrefilterEnabled, isPrefetchEnabled, getQualityPref, isVerboseLog
- getCfDomains, getCookieForDomain, saveCookieForDomain, clearCookieForDomain
- getMbToken, getMbTokenExp, saveMbToken
- getFebBoxToken, saveFebBoxToken, clearFebBoxToken
- getTvdbToken, getTvdbTokenExp, saveTvdbToken
- isSrcVm, isSrcMd, isSrcHdh, isSrcFebBox, isSrcMovieBox, isSrcAnikoto, isSrcShowBox, isSrcMlsbd, isSrcAniZone
- Colors — UI palette constants (BG, CARD, ROW, ACCENT, TEXT, etc.)
- dp, bg, skyGradient, cardBg, withRipple, accentPill, saveButtonBg, cancelButtonBg, arrowButtonBg — UI helpers
- ShootingStarsView, NightCloudsView — animated decorations
- Card — builder
- buildCard, toggleRow, stepperRow, actionRow, domainRow, labelBlock, makeArrowBtn, rowReorderItem
- showSettingsDialog — main dialog
- openCfWebView, openFebBoxLogin — WebView dialogs


## ShowBoxApi.kt
- SB_IV, SB_KEY, SB_APP_KEY, SB_APP_ID, SB_APP_VERSION, SB_VERSION_CODE, SB_API_PRIMARY, SB_API_FALLBACK, SB_FEBBOX — ShowBox constants
- SB_CLIENT_CERT, SB_CLIENT_KEY — mTLS client cert
- sbFebBoxHeaders, sbMd5Hex, sbEncrypt, sbRandomToken, sbExpiry — helpers
- sbHttpClient — lazy OkHttp with client cert
- sbQuery — signed query builder
- SBItem — data model
- sbSearch, sbExternalShareKey, sbFileList, sbGetDownloadUrl
- sbLocalIpv4 — best-effort IPv4 for CDN
- showBoxExtractRaw — main entry


## StreamScrapers.kt
- StreamQuery, ScrapedMirror — data models (StreamQuery at TOP of file)
  — StreamQuery now has totalEpisodes: Int and originalLanguage: String
- DOMAIN_JSON_URL, DOMAIN_CACHE_TTL — domain resolver
- resolveDomain — get latest domain from SaurabhKaperwan/Utils
- STOP_WORDS, stripQualifiers, titleMatches — title matcher
- SEASON_MATCH_ALL_TOKENS, extractSeasons, pageHasSeason — season parsing
- cachedGet, safeGet — HTTP helpers
- vegamoviesFindPage, vegamoviesExtractMovieRaw, vegamoviesExtractSeriesRaw — VM
- moviesdriveFindPage, moviesdriveExtractMovieRaw, moviesdriveExtractSeriesRaw, extractFromArchivePage — MD
- hdhub4uFindPage, hdhub4uExtractRaw — HDH
- movieboxExtractRaw — MB (Part-N offset + direct-part-hit applied here)
- prettyAudio — audio label normalizer
- ANIKOTO_DOMAIN, ANIKOTO_UA, anikotoBrowserHeaders, anikotoAjaxHeaders
- anikotoResultString, anikotoResultUrl, anikotoScore
- AnikotoSeries, anikotoFindSeries, anikotoGetEpInfo, anikotoResolvePlayerUrl
  - AnikotoEpInfo — strict ep lookup + total count
  - anikotoResolveFromServerIds — serverIds → mirrors
  - anikotoChainWalk — out-of-range → AniList chain → next part on site
  - isSeasonSpecific — season-marker check for AniKoto season search
- anikotoExtractRaw — AniKoto (Part-N offset applied here)
- resolveWrapper — wrapper URL resolver
- PER_SOURCE_TIMEOUT_MS
- scrapeAllSources — parallel source dispatcher


## TvdbApi.kt
- TVDB_ENDPOINT — https://api4.thetvdb.com/v4
- COUNTRY_ISO3, LANG_ISO3 — lang maps
- ENRICH_CAP — 15
- TvdbAuth — token cache
  - getToken, login
- tvdbFetch — single genre fetch
- tvdbDiscover — fan out across genres
- tvdbEnrich — Aiometa + TMDB ID swap


## package: com.flummox.bingecore
## path: BingeCloud/src/main/java/com/flummox/bingecore/


## CloudflareShield.kt
- GROUPS — currently empty (MLSBD removed)
- K_CF_EXPIRY_PREFIX
- getExpiry, clearCookieAndExpiry — helper
- State, GroupStatus — status enums
- statusOf — group health check
- bypassGroup — open WebView solver per domain


## Prewarm.kt
- HOSTS — 9 warmup URLs
- fire — fire-and-forget HEAD to all hosts on boot


## RepoAnalystic.kt
<!-- filename typo, object is RepoAnalytics -->
- WORKER_URL — Cloudflare Worker ping endpoint
- AUTH_TOKEN — hardcoded token
- getOrCreateInstallId — install UUID
- ping — daily anonymous ping (uses BuildConfig.PLUGIN_VERSION)


## SpeedBooster.kt
- deduped — in-flight job deduplication via ConcurrentHashMap


# ══════════════════════════════════════════════════════════════
# OTAKUTSU MODULE
# ══════════════════════════════════════════════════════════════

## Otakutsu/build.gradle.kts
path: Otakutsu/build.gradle.kts
- android — namespace com.flummox.otakutsu, compileSdk 35, minSdk 21
- cloudstream — description, authors, tvTypes=[Anime, AnimeMovie], version from otakutsu_version
- dependencies — cloudstream3 pre-release, NiceHttp, jsoup, okhttp, jackson, coroutines


## package: com.flummox.otakutsu
## path: Otakutsu/src/main/java/com/flummox/otakutsu/


## OLog.kt
- init — file + buffer setup; loads persisted verbose pref from CloudStream keys
- d, v, e, section — normal + verbose log levels (▸ prefix on verbose lines)
- setVerbose, isVerbose — toggle persisted across sessions
- allSanitized, allVerboseSanitized — UI feeds for log windows
- count, countVerbose — line counts for header
- clear — wipes both files + buffers, reopens writers
- xorEncrypt, xorDecrypt — obfuscation for otakutsu_verbose.enc
- sanitize — regex redaction (JWT, bearer, cookie, CF) applied to both views
- Files: otakutsu_log.txt (plain text), otakutsu_verbose.enc (xor+base64)


## OLogScrollbar.kt
- OLogScrollbar — custom draggable scrollbar View (dim gray 0x8C7A7A7A thumb)


## OSettings.kt
- show — main dialog: OTAKUTSU hero + EXTENSION badge + SETTINGS/LOGS tiles + CLOSE
- showSettings — sub-window: prefetch toggle row + clear cache row
- showLogs — sub-window: verbose toggle row, log view, REFRESH/SAVE/COPY/CLEAR buttons, footer note
- isPrefetchEnabled, setPrefetchEnabled — prefs (K_PREFETCH)
- makeTile, subWindow, stagger, shape, ripple — UI builders
- Colors — BG, SURFACE, BORDER, BORDER_HI, TEXT, SUBTEXT, ACTIVE, RED, LOG_TEXT
- Tile structure: SETTINGS (⚙️) → showSettings, LOGS (📋) → showLogs
- Badge ACTIVE dot pulses 0.25↔1.0 alpha at 1200ms cycles
- Staggered entrance: 70ms offset, 420ms duration, DecelerateInterpolator


## OtakutsuPlugin.kt
- OtakutsuPlugin — @CloudstreamPlugin entry point
- load — OLog.init, registerMainAPI(OtakutsuProvider)
- openSettings → OSettings.show


## OtakutsuProvider.kt
- OtakutsuProvider — MainAPI class
  - mainUrl = https://otakutsu.cc
  - name = Otakutsu
  - hasMainPage, hasQuickSearch, hasDownloadSupport, supportedTypes = [Anime, AnimeMovie]
  - NEXT_ACTION_ID — hardcoded fallback (787faac6445fbc39cfe9376659cbfb5168c3f714b2)
    Turbopack does NOT emit server action IDs to client JS (verified via
    homepage HTML, homepage chunks, watch route chunks — all miss)
  - playbackCookie — session cookie state captured from bootstrap+session Set-Cookie
  - baseHeaders, browserHeaders, playbackHeadersFn — header sets
  - homeHtml, homeTime, homeDoc, HOME_TTL (10min) — home cache
  - totalEpsByAnime — per-anime episode count for prefetch planning
  - getHomeHtml, getHomeDoc — cached accessors
  - getMainPage — parse <section id=X> cards from home HTML
  - search — GET /api/feed/search (English → Romaji → Native title chain)
  - load — parallel /anime/{id} + /watch/{id}?ep=1, parse metadata + episodes, fires PrefetchEngine.warmLoad
  - buildStateTree — Next.js router state JSON for RSC action POST
  - fetchChain — bootstrap → session → cookie collect → POST /watch/{id}?ep=N with next-action + state-tree → parse sources[] from RSC flight lines
  - loadLinks — PrefetchEngine.obtain → probe each source (200 + #EXTM3U + STREAM-INF/MEDIA) → emit ExtractorLink (M3U8) → recordPlay + warmAfterPlay
  - clearSectionCache — static; wipes in-memory home section cache
  - Stale-action heuristic: rsc.len > 20KB + contains "$Sreact.fragment" + no "sources" key → log OTAKUTSU UPDATED + ActionHealth.markStale()
  - ActionHealth.markOk() on successful sources parse


## PrefetchEngine.kt
- DEBOUNCE_MS = 800, HOME_GRACE_MS = 5000, HISTORY_SIZE = 5
- SCOPE — SupervisorJob + Dispatchers.IO
- inFlight — ConcurrentHashMap<String, CompletableDeferred<PrefetchCache?>>
  (dedup; second concurrent caller awaits first, no duplicate network)
- watchHistory — per-anime last-5 plays (binge detection)
- currentAnimeId — session tracking
- sessionJobs — cancelable per-session jobs
- markHomeRender — sets lastHomeRenderMs for grace check
- isFromHome — true if within HOME_GRACE_MS
- beginSession — switch anime; cancels prior session jobs
- recordPlay — append episode to history (dedup on consecutive)
- plan — sequential 3-history → warm [ep+1, ep+2]; else warm [ep+1]
- warmLoad — batch warm E1+E2 on series open
- warmAfterPlay — history-aware next-ep warming
- warm — enqueues jobs with debounce + grace + cache checks
- obtain — public in-flight dedup entry (single network call per key even if many callers race)


## OCache.kt
- OtakutsuSource — data class (label, server, subType, url, tracks: List<Pair<String,String>>)
- tracks holds native Otakutsu subtitle URLs (currently 502, preserved for future)
- PrefetchCache — data class (sources, cookieHeader)
- OCache — LRU prefetch cache
- getPrefetch, putPrefetch, hasPrefetch
- TTL_VALID = 30min, TTL_EMPTY = 60s
- clear, size


## AniKotoSubs.kt
- AniKotoSubs — standalone AniKoto subtitle extractor (ported from BingeCloud)
- DOMAIN, UA, browserHeaders, ajaxHeaders — request config
- resultString, resultUrl, score — parsing + scoring helpers
- findSeries — /filter?keyword=X → best title match → data-id extraction
- serverIdsForEpisode — /ajax/episode/list/{id} → data-ids for target ep
- resolvePlayerUrl — /ajax/server?get={linkId} → player URL
- tracksFromPlayer — fetch player page → #megaplay-player data-id → /stream/getSources → tracks
- Sub, Series — data classes
- fetch — top-level entry: search → episode → server → player → subs (first 3 servers)


## AniZoneSubs.kt
- AniZoneSubs — standalone AniZone subtitle extractor (ported from BingeCloud)
- BASE, UA — request config
- RX_JSON_PARSE_TPL, RX_PLAYER, RX_NON_ALNUM, RX_WS — regexes
- unescapeJs, extractJsonParse, normalize — parsing helpers
- search — /anime?search=X → items JSON → Hit list
- pickBest — exact → containment → first hit
- getSubtitles — /anime/{slug}/{ep} → vidstackPlayer JSON → subtitles[]
- Sub, Hit — data classes
- fetch — top-level entry


## SubtitleFetcher.kt
- SubtitleFetcher — orchestrator for AniKoto + AniZone subs
- fetch — runs both in parallel, 6s timeout each, merges + dedupes by URL
- Sub — data class (label, url) with source prefix applied
- cache — LruCache<String, Entry>, TTL 7 days in-memory
- clear — wipes cache


## ActionHealth.kt
- ActionHealth — action-ID health state for OSettings badge
- K_LAST_OK, K_LAST_STALE — CloudStream pref keys
- markOk — called by fetchChain when sources parse successfully
- markStale — called by fetchChain when stale-detection heuristic fires
- isHealthy — true if last OK >= last stale (or both zero)


## OAnalytics.kt
- OAnalytics — anonymous daily ping (same Worker + AUTH_TOKEN as BingeCloud)
- PREFS = "otakutsu_repo_analytics" — separate install ID namespace
- PING_INTERVAL_MS — 24h throttle
- getOrCreateInstallId — persistent UUID in CloudStream prefs
- ping — POST {timestamp, installId, repo, ext, ver} with X-Auth-Token


# ══════════════════════════════════════════════════════════════
# OTAKUTSU — reverse-engineered API surface
# ══════════════════════════════════════════════════════════════

| Purpose              | Endpoint                                     | Method | Notes                                       |
|----------------------|----------------------------------------------|--------|---------------------------------------------|
| Search               | /api/feed/search?query=X&per_page=30&page=1  | GET    | JSON; ranked                                |
| Bootstrap            | /api/media/bootstrap                         | POST   | body {animeId, ep} → {streamToken: JWT}     |
| Session              | /api/media/session                           | POST   | body {streamToken} → {ok, expiresAt}        |
| Stream mint          | /watch/{animeId}?ep=N                        | POST   | Next.js server action, RSC flight response  |
| Master playlist      | /playlist/{id}.m3u8?e={exp}&s={sig}          | GET    | needs Referer + Cookie                      |
| Variant playlist     | /video/{id}/playlist_X.m3u8?e=&s=            | GET    | listed in master                            |
| Segment              | /video/{id}/NNNNN.html?e=&s=                 | GET    | content-type video/mp2t (disguised)         |
| Subtitles            | /subtitle/{id}/{n}_{lang}.html?e=&s=         | GET    | currently 502 — server-side dead            |
| Related seasons      | /api/catalog-seasons?animeId={id}            | GET    | JSON                                        |
| Comments             | /api/comments?animeId={id}&ep=N              | GET    | JSON                                        |

## Critical headers for bootstrap/session POST
- Content-Type: application/json
- Accept: application/json, text/plain, */*
- User-Agent: browser UA
- Referer / Origin: https://otakutsu.cc
- sec-ch-ua, sec-ch-ua-mobile, sec-ch-ua-platform
- sec-fetch-dest: empty / mode: cors / site: same-origin
- Missing sec-fetch-* → Cloudflare returns 404

## Critical headers for /watch server action POST
Same as above PLUS:
- Accept: text/x-component
- Content-Type: text/plain;charset=UTF-8
- next-action: <40-hex id>
- next-router-state-tree: <URL-encoded JSON route tree>
- Cookie: <from bootstrap + session>

Body: ["<animeId>", <ep>, "<streamToken>"]

## Cookie flow
1. POST /api/media/bootstrap sets 1-2 cookies via Set-Cookie
2. POST /api/media/session sets 1-2 cookies via Set-Cookie
3. Both collected into Cookie: name1=val1; name2=val2
4. Sent on /watch action POST and all m3u8 playback requests
5. Without cookies → m3u8 requests return 404 (9-byte "Not Found")

## Action ID status (2026-09-28)
- Hardcoded fallback: 787faac6445fbc39cfe9376659cbfb5168c3f714b2
- Turbopack does NOT emit server action IDs into client JS bundles
- Scanned: homepage HTML, homepage chunks (19 files), watch route chunks (12 files)
- All scans → 0 hits on `[a-f0-9]{40}` pattern
- Fallback is authoritative until Otakutsu rotates their action source
- Stale detection heuristic: RSC response >20KB containing "$Sreact.fragment"
  but no "sources" key → log OTAKUTSU UPDATED
