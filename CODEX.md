# CODEX.md — FLUMMOX Repo symbol → file map

<!-- ============================================================ -->
<!--  HOW TO USE                                                   -->
<!--  Ctrl+F a symbol → look at the "## file" line directly above. -->
<!--  That's the file to edit.                                     -->
<!--                                                               -->
<!--  STATUS MARKERS                                               -->
<!--  [DISABLED] = file/symbol present but not wired               -->
<!--  [DEAD]     = unreferenced, no revival planned                -->
<!-- ============================================================ -->


# ═══════════════════════════════════════════════════════════════
# TOP-LEVEL
# ═══════════════════════════════════════════════════════════════

## settings.gradle.kts
path: settings.gradle.kts
- pluginManagement — AGP 8.13.0, Kotlin 2.4.10, recloudstream gradle plugin
- rootProject.name = "FLUMMOX-Repo"
- includes :BingeCloud, :Otakutsu, :BingeAnime

## build.gradle.kts
path: build.gradle.kts
- buildscript — AGP 8.13.0, Kotlin 2.4.10, cloudstream gradle plugin
- allprojects — google, mavenCentral, jitpack

## gradle.properties
path: gradle.properties
- bingecloud_version — BingeCloud plugin version (SSOT)
- otakutsu_version — Otakutsu plugin version
- bingeanime_version — BingeAnime plugin version

## build.yml
path: .github/workflows/build.yml
- Triggers on push to main, master, dev
- Outputs to builds/ (main) or builds-dev/ (dev)
- Env: TMDB_API_KEY, TVDB_API_KEY, MAL_CLIENT_ID, ANIMESCHEDULE_API_KEY, IS_DEV_BUILD
- Reads bingecloud_version for NEW_VERSION env → patch_plugins.py

## patch_plugins.py
path: patch_plugins.py
- Reads NEW_VERSION, OUTPUT_BRANCH, GITHUB_REPOSITORY
- Rewrites builds/plugins.json URLs + versions
- Reads src/gradle.properties → {internalName.lower()}_version per plugin


# ═══════════════════════════════════════════════════════════════
# BINGECLOUD MODULE
# ═══════════════════════════════════════════════════════════════

## BingeCloud/build.gradle.kts
path: BingeCloud/build.gradle.kts
- android — namespace com.flummox.bingecloud, compileSdk 35, minSdk 21
- defaultConfig — TMDB_API_KEY, TVDB_API_KEY, PLUGIN_VERSION, IS_DEV_BUILD
- cloudstream — description, authors, tvTypes, version from bingecloud_version
- dependencies — cloudstream3 pre-release, NiceHttp, jsoup, okhttp, jackson, coroutines

## package: com.flummox.bingecloud
## path: BingeCloud/src/main/java/com/flummox/bingecloud/

## AiometaApi.kt
- AIOMETA_BASE — Aiometa metadata base URL
- AioCast, AioVideo, AioAppExtras, AioMeta, AioMetaResponse, AioCatalogResponse — data models
  - AioAppExtras.seasonPosters — @JsonIgnore
- aioFetchMeta — single meta fetch
- aioFetchCatalog — catalog page fetch
- aioSearch — Aiometa search
- TmdbDiscoverItem, TmdbDiscoverResponse — TMDB models
- INDIAN_ONLY_PROVIDERS — provider ID set (122, 220, 237, 232)
- discoverPrimaryOk — per-(type,provider,region) path cache
- tmdbDiscover, tmdbDiscoverFetch — TMDB discover with fallback
- tmdbDiscoverMerged — movie+tv merged per provider
- tmdbTrendingDirect — TMDB /trending/day
- tmdbDetailMeta — full TMDB detail fallback
- tmdbDiscoverByLanguage — TMDB by original_language
- tmdbHindiSeriesClean — premium-genre Hindi pool, daily rotated
- fetchHindiPool — internal pool fetch
- TmdbDiscoverItem.toAioMeta — mapper

## AnikotoExtractors.kt
- MEGAPLAY_ENC_IV, MEGAPLAY_ENC_KEY, MEGAPLAY_TOKEN_SECRET — crypto constants
- ANIKOTO_PROXY_MAP — vault → uwu domain map
- anikotoProxyPlayerHost — host rewrite
- anikotoGetHashM3u8 — fragment base64 decoder
- anikotoServerTypeLabel — sub/dub/hsub label
- b64UrlNoPad — base64url encoder
- anikotoSignMegaPlayUrl — HMAC token signer
- anikotoDecryptMegaPlaySources — AES decrypt
- anikotoExtractMegaPlayUrl — main player extractor
- AnikotoMegaPlay, AnikotoVidtube, AnikotoVidwish — ExtractorApi classes

## AniListApi.kt
- ENDPOINT — https://graphql.anilist.co
- Title, Entry, Relation, PartInfo — data models
- searchAnime — GraphQL search, 10 hits, 24h cache
- getEntry — single entry by AniList ID
- resolveChain — prequel/sequel walk to full ordered chain
- parsePartInfo — extract Part N + Season N
- getPrequelOffset — sum same-franchise prequel ep counts
- normalizedBase, sameBaseTitle — title comparison helpers
- parseEntry, entryToJson, parseSearchResponse — serializers

## AniZoneApi.kt
- BASE — https://anizone.to
- RX_NON_ALNUM, RX_WS, RX_JSON_PARSE_TPL, RX_PLAYER, RX_CUT — regexes
- Hit, Episode, StreamResult — data models
- searchKey, epsKey, SEARCH_TTL, EPS_TTL — cache keys/TTLs
- unescapeJs, extractJsonParse, normalize — parsing helpers
- buildVariants — shortened query variants
- search — /anime?search=X (30min cache)
- pickBest — exact → containment → fuzzy → year → ep count
- getEpisodes — episode list (60min cache)
- getStream — m3u8 from vidstackPlayer JSON
- pickBestAniList — AniList hit by title/year
- tryNextPartInChain — out-of-range → AniList chain → next part on site
- resolve — main entry
- mirror — ScrapedMirror builder

## BCLog.kt
- buffer, lock, writer — log state
- setVerbose, isVerbose — toggle
- RX_* sanitize patterns
- init — file + buffer setup
- d, v, e, section — log levels
- allSanitized, count, clear — UI support

## BingeCloudPlugin.kt
- @CloudstreamPlugin entry
- load — RepoAnalytics.ping, BCLog.init, HostHealth.init, restoreMbSession, Prewarm.fire
  - registers BingeCloudProvider, VCloud, GDirect, Filepress
  - sets openSettings → Settings.showSettingsDialog

## BingeCloudProvider.kt
- SEP, ROW_TAG — row config separators
- PREFETCH_DEBOUNCE_MS, HOME_GRACE_MS
- PREFETCH_SCOPE, activePrefetchJob, lastHomeRenderMs — prefetch state
- StreamQuery.cacheKey — cache key extension
- ADULT_TERMS, HOME_BLOCKED_GENRES — filters
- isAdultContent, AioMeta.isJunk — filter functions
- BingeCloudProvider — MainAPI
  - mainPage — filtered by Settings rows
  - getMainPage — row dispatch + tvdbEnrich + dedup + isJunk filter
  - resolveRow — row type → source dispatcher
  - jwTypeFor, routeWestern, routeIndian, routeBanglaTVDB
  - getHindiMergedPool, validateHindiSeries
  - routeLanguageTVDB, routeLanguage
  - search — TMDB + AniList parallel merge
  - RX_SEASON_N, RX_EXTRA_MARKER, GROUP_STOPWORDS
  - isExtraTitle, seasonNumberOf, searchGroupKey
  - mergeSearchResults, searchDedupeKey
  - tmdbSeasonNumbers — season list for TMDB id
  - AniListApi.Entry.toAniListSearchResponse
  - searchViaAiometa [DEAD]
  - AioMeta.toSearchResponse, TmdbSearchItem.toSearchResponse
  - load — Aiometa → TMDB fallback → AniList direct
  - loadFromAniList — anilist: URL entry
  - loadLinks — mirror resolve + progressive emit
  - hostOf, qualityRank, audioPriority, computeStatusTag
- encodeQuery, decodeQuery — StreamQuery serialization
- TmdbSearchResponse, TmdbSearchItem — models
- RX_COUR — filter "Cour N" AniList entries

## Cache.kt
- BCCache
  - get, put — text cache (5min default)
  - getMirrors, putMirrors — 30min, 16 entries
  - clear

## CfSolverDialog.kt
- CF_UA — must match CloudflareHelper.CF_UA
- CfResult — data model
- resolve — WebView solver entry
- solve — dialog + WebView + JS bridge
- JS_INSTALL_OBSERVER, JS_DETECT_CF — injected scripts
- CfBridge — JS → Kotlin HTML push

## CloudflareHelper.kt
- BingeCloudCtx — context holder
- CF_UA — must match CfSolverDialog.CF_UA
- CF_INDICATORS, isCfChallenge — CF page detection
- currentActivity — reflective Activity finder
- cloudflareGet — 3-stage GET (stored cookie, plain, solver)
- cloudflareGetDoc — Jsoup-wrapped variant

## Extractor.kt
- base64Decode, getBaseUrl, getIndexQuality, getLatestBaseUrl, resolveFinalUrl — utils
- TRAILING_QUALITY_REGEX, cleanServerName, shortenServer, isDirectFile, linkTypeFor
- DEAD_HOSTS — skip list
- VCloud — ExtractorApi (sourceTag, mirrorLabel, qualityLabel, emojiPrefix)
- GDirect — Google Drive extractor
- Filepress — filepress extractor

## HostHealth.kt
- FILE_NAME, FLUSH_INTERVAL_MS, SUCCESS_WINDOW_MS
- Health — per-host state
- map, lock, dirty, file, timer
- init — load from disk + flush timer
- bonus — score contribution
- recordSuccess, recordFailure
- flushIfDirty, loadFromDisk

## JustWatchApi.kt
- JW_ENDPOINT — https://apis.justwatch.com/graphql
- JW_QUERY — GraphQL query
- jwDiscoverByProvider — filter by provider slug
- jwDiscoverByLanguage — filter by originalLanguages
- jwFetch — internal POST + parse

## LinkScore.kt
- prelimScore — 0-100 score per mirror
- emoji — 🟢🟡🔴 bands
- hostOf — URL → host

## LogScrollbar.kt
- LogScrollbar — custom draggable scrollbar View

## MlsbdApi.kt [DISABLED]
- Header documents revival procedure
- MLSBD_BASE, MLSBD_UA
- mlsbdHttpClient — OkHttp with MlsbdDns
- mlsbdFetch, mlsbdFetchVia
- mlsbdSearch, mlsbdFindPage
- mlsbdResolveSavelinks, mlsbdExtractFromMulticloud, mlsbdExtractPlayerStream
- mlsbdDecodeBongHd — x_data decoder (URL → strip:N → ROT13 → base64)
- mlsbdResolveToMirrors, mlsbdExtractRaw

## MlsbdDns.kt [DISABLED]
- MlsbdDns — OkHttp Dns with DoH fallback
- UNREACHABLE_1, UNREACHABLE_2, VERIFIED_EDGES

## MovieBoxApi.kt
- MB_SECRET_B64, MB_SECRET_ALT_B64, MB_VERSION_CODE, MB_VERSION_NAME, MB_PACKAGE, MB_INSTALL_STORE, MB_UA
- MB_HOSTS, MB_WEB_DOMAINS, MB_WEB_UA
- MB_BOOTSTRAP_HOST, MB_BOOTSTRAP_PATH
- deviceId, clientInfo — request fingerprint
- md5Hex, b64DecodeBytes, b64Encode — encoding
- generateXClientToken, buildCanonicalString, generateXTrSignature, buildHeaders — signing
- parseJwtExp, restoreMbSession
- bootstrapToken, ensureSession
- mbGet — GET with retry + cache
- MBSubject, MBStream — models
- highestQuality — resolutions → best
- extractPolicyResource — signCookie decoder
- mbSearch, parseSearchResults
- mbDetail, mbLanguages
- mbPlay — orchestrator (web → native fallback)
- mbPlayWeb — iterate MB_WEB_DOMAINS
- parseWebStreams — dash/hls/streams + emitHeaders
- mbPlayNative — play-info + resourceDetectors

## Settings.kt
- RowSpec — home row descriptor
- K_* — all pref keys
- ALL_ROWS — full RowSpec list
- DEFAULT_ON_ROWS
- getRowOrder, setRowOrder, getRowSpecByKey, isRowEnabled, resetHomeToDefaults
- showResetConfirmDialog, showPasteTokenDialog
- getConcurrency, isPrefilterEnabled, isPrefetchEnabled, getQualityPref, isVerboseLog
- getCfDomains, getCookieForDomain, saveCookieForDomain, clearCookieForDomain
- getMbToken, getMbTokenExp, saveMbToken
- getFebBoxToken, saveFebBoxToken, clearFebBoxToken
- getTvdbToken, getTvdbTokenExp, saveTvdbToken
- isSrc* toggles
- Colors, dp, bg, skyGradient, cardBg, withRipple, accentPill, saveButtonBg, cancelButtonBg, arrowButtonBg
- ShootingStarsView, NightCloudsView
- buildCard, toggleRow, stepperRow, actionRow, domainRow, labelBlock, makeArrowBtn, rowReorderItem
- showSettingsDialog, openCfWebView, openFebBoxLogin

## ShowBoxApi.kt
- SB_IV, SB_KEY, SB_APP_KEY, SB_APP_ID, SB_APP_VERSION, SB_VERSION_CODE, SB_API_PRIMARY, SB_API_FALLBACK, SB_FEBBOX
- SB_CLIENT_CERT, SB_CLIENT_KEY — mTLS client cert
- sbFebBoxHeaders, sbMd5Hex, sbEncrypt, sbRandomToken, sbExpiry
- sbHttpClient — lazy OkHttp with cert
- sbQuery — signed query
- SBItem — data model
- sbSearch, sbExternalShareKey, sbFileList, sbGetDownloadUrl
- sbLocalIpv4
- showBoxExtractRaw — main entry

## StreamScrapers.kt
- StreamQuery, ScrapedMirror — models
- DOMAIN_JSON_URL, DOMAIN_CACHE_TTL, resolveDomain
- STOP_WORDS, stripQualifiers, titleMatches
- SEASON_MATCH_ALL_TOKENS, extractSeasons, pageHasSeason
- cachedGet, safeGet
- vegamovies* — VegaMovies
- moviesdrive* — MoviesDrive
- hdhub4u* — HDH
- movieboxExtractRaw, prettyAudio — MB
- ANIKOTO_DOMAIN, ANIKOTO_UA, anikotoBrowserHeaders, anikotoAjaxHeaders
- anikotoResultString, anikotoResultUrl, anikotoScore
- AnikotoSeries, anikotoFindSeries, AnikotoEpInfo, anikotoGetEpInfo
- anikotoResolvePlayerUrl, anikotoResolveFromServerIds, anikotoChainWalk
- isSeasonSpecific
- anikotoExtractRaw
- resolveWrapper
- PER_SOURCE_TIMEOUT_MS
- scrapeAllSources — parallel dispatcher

## TvdbApi.kt
- TVDB_ENDPOINT, TVDB_JSON
- COUNTRY_ISO3, LANG_ISO3
- ENRICH_CAP
- TvdbAuth.getToken, login
- tvdbFetch — single fetch
- tvdbDiscover — genre fan-out
- tvdbEnrich — Aiometa + TMDB ID swap

## package: com.flummox.bingecore
## path: BingeCloud/src/main/java/com/flummox/bingecore/

## CloudflareShield.kt
- GROUPS — currently empty (MLSBD disabled)
- K_CF_EXPIRY_PREFIX
- getExpiry, clearCookieAndExpiry
- State enum, GroupStatus
- statusOf — group health check
- bypassGroup — WebView solver per domain

## Prewarm.kt
- HOSTS — warmup URL list
- fire — HEAD to all hosts

## RepoAnalystic.kt
- WORKER_URL, AUTH_TOKEN
- getOrCreateInstallId
- ping — daily anonymous ping

## SpeedBooster.kt
- deduped — in-flight job deduplication


# ═══════════════════════════════════════════════════════════════
# OTAKUTSU MODULE
# ═══════════════════════════════════════════════════════════════

## Otakutsu/build.gradle.kts
path: Otakutsu/build.gradle.kts
- android — namespace com.flummox.otakutsu, compileSdk 35, minSdk 21
- defaultConfig — PLUGIN_VERSION, IS_DEV_BUILD
- cloudstream — description, authors, tvTypes=[Anime, AnimeMovie], version from otakutsu_version
- dependencies — cloudstream3 pre-release, NiceHttp, jsoup, okhttp, jackson, coroutines

## package: com.flummox.otakutsu
## path: Otakutsu/src/main/java/com/flummox/otakutsu/

## ActionHealth.kt
- K_LAST_OK, K_LAST_STALE
- markOk, markStale
- isHealthy — last OK >= last stale

## AniKotoSubs.kt
- DOMAIN, UA, browserHeaders, ajaxHeaders
- resultString, resultUrl, score
- findSeries, serverIdsForEpisode, resolvePlayerUrl, tracksFromPlayer
- Sub, Series — models
- fetch — search → episode → server → player → subs

## AniZoneSubs.kt
- BASE, UA
- RX_JSON_PARSE_TPL, RX_PLAYER, RX_NON_ALNUM, RX_WS
- unescapeJs, extractJsonParse, normalize
- search, pickBest, getSubtitles
- Sub, Hit — models
- fetch — top-level entry

## OAnalytics.kt
- WORKER_URL, AUTH_TOKEN, PREFS
- getOrCreateInstallId
- ping — daily anonymous ping

## OCache.kt
- OtakutsuSource, PrefetchCache — models
- TTL_VALID = 30min, TTL_EMPTY = 60s
- map, lock
- getPrefetch, putPrefetch, hasPrefetch
- clear, size

## OLog.kt
- buffer, lock, writer, vWriter
- setVerbose, isVerbose (persisted)
- RX_* sanitize patterns
- init — file setup
- d, v, e, section
- allSanitized, allVerboseSanitized
- count, countVerbose
- clear

## OLogScrollbar.kt
- OLogScrollbar — custom scrollbar

## OSettings.kt
- Colors
- K_PREFETCH, K_SUBS
- isPrefetchEnabled, setPrefetchEnabled
- isSubtitlesEnabled, setSubtitlesEnabled
- dp, shape, ripple, stagger
- makeTile, baseRoot, subWindow
- show — main dialog
- showSettings, showLogs

## OtakutsuPlugin.kt
- @CloudstreamPlugin entry
- load — OLog.init, OAnalytics.ping, registerMainAPI(OtakutsuProvider), openSettings

## OtakutsuProvider.kt
- NEXT_ACTION_ID — hardcoded fallback
- playbackCookie — session state
- baseHeaders, browserHeaders, playbackHeadersFn
- homeHtml, homeTime, homeDoc, HOME_TTL
- totalEpsByAnime
- getHomeHtml, getHomeDoc
- mainPage — 6 sections
- getMainPage — parse <section id=X> cards
- search — /api/feed/search
- load — parallel /anime + /watch + metadata + episodes
- buildStateTree, fetchChain — bootstrap → session → RSC action POST → sources
- loadLinks — PrefetchEngine.obtain → probe → emit
- clearSectionCache
- Stale heuristic → ActionHealth.markStale

## PrefetchEngine.kt
- DEBOUNCE_MS, HOME_GRACE_MS, HISTORY_SIZE
- SCOPE, inFlight
- watchHistory, currentAnimeId, sessionJobs
- markHomeRender, isFromHome
- beginSession, recordPlay
- plan, warmLoad, warmAfterPlay, warm
- obtain — in-flight dedup

## SubtitleFetcher.kt
- TTL_MS = 7 days
- cache, lock
- key
- clear
- fetch — AniKoto + AniZone parallel, 6s each
- Sub — model with source prefix


# ═══════════════════════════════════════════════════════════════
# BINGEANIME MODULE
# ═══════════════════════════════════════════════════════════════

## BingeAnime/build.gradle.kts
path: BingeAnime/build.gradle.kts
- android — namespace com.flummox.bingeanime, compileSdk 35, minSdk 21
- defaultConfig — PLUGIN_VERSION, IS_DEV_BUILD, MAL_CLIENT_ID, ANIMESCHEDULE_API_KEY
- cloudstream — description, authors, tvTypes=[Anime, AnimeMovie], version from bingeanime_version
- dependencies — cloudstream3 pre-release, NiceHttp, jsoup, okhttp, jackson, coroutines

## package: com.flummox.bingeanime
## path: BingeAnime/src/main/java/com/flummox/bingeanime/

## AnikageApi.kt
- BASE — https://anikage.cc
- PROXY — https://og.bakayaro.live
- UA
- TTL_SEARCH, TTL_EPS, TTL_SERVERS, TTL_SOURCES
- headers()
- ServerInfo, SourceItem, SubItem, EmbedOption, EpInfo, SourceBundle — models
- search — /api/media/anime/browse
- episodes — /api/media/anime/{slug}/episodes
- servers — /api/media/anime/{slug}/episodes/{ep}/servers
- sources — /api/media/anime/{slug}/episodes/{ep}/sources
- hlsUrl, subUrl, referer — URL builders

## AniListApi.kt
- ENDPOINT, JSON_MEDIA, CACHE_TTL
- blockedUntil — 429 cooldown (getEntry)
- inFlightBatch — batch dedup
- Title, Entry, Relation, PartInfo — models
- MEDIA_FIELDS, MEDIA_FIELDS_WITH_RELATIONS
- CatalogSpec
- safeAlias
- fetchCatalogBatch — aliased GraphQL
- parsePageArray, parseList
- searchAnime — SEARCH_MATCH sort
- getEntry — with relations
- RX_PART_N, RX_SEASON_N
- parsePartInfo
- RX_COUR_N, RX_ROMAN_WORD, ROMAN_MAP
- stripCourBranding, baseTitleKey, convertRomanSeasons
- formatPriority, relevanceRank, titleOf
- SPINOFF_MARKERS, isSpinoff, effectiveFormatPriority
- seasonOrdinal, partOrdinal
- sortChronological, filterAndSortChronological
- mergeKey
- parseEntry

## AniMapperApi.kt
- BASE — https://api.animapper.net/api/v1
- ROW_TTL
- headers()
- ANILIST_GRAPHQL, RATING_TTL, JSON_MEDIA
- enrichRatings — batch AniList rating enrichment
- cnIds, cnIdsFetchedAt, CN_TTL, ensureCnIds
- trending — UPDATED_AT + RELEASING
- donghua — countryOfOrigin=CN
- searchByTitle — cover fallback
- detail — /metadata?id=N
- parseResponse, parseEntry

## AnimeScheduleApi.kt
- BASE — https://animeschedule.net/api/v3
- IMG_BASE
- ROW_TTL
- headers() — Bearer BuildConfig.ANIMESCHEDULE_API_KEY
- search, detail
- parseList, parseEntry

## AniwavesApi.kt
- BASE — https://aniwaves.ru
- UA
- TTL_SEARCH, TTL_DETAIL, TTL_SOURCES
- baseHeaders, ajaxHeaders
- Hit, Ep, Srv, Detail — models
- search — /ajax/anime/search
- detail — /watch/{slug}, parse #watch-main[data-id] + JSON-LD + ep ranges
- servers — /ajax/server/list
- resolveLink — /ajax/sources?id={linkId}
- parseSearch, parseDetail, parseServers

## AniwavesScraper.kt
- ANIWAVES_CONCURRENCY — reads user setting, capped 1..6
- ALLOWED_SV_IDS = setOf("4") — Vidplay only
- pickBest — title + year matcher
- hostOf
- ANIWAVES_CLICK_SCRIPT — JW Player click nudge (14 retries × 500ms)
- resolveEmbed — WebViewResolver wrapper (interceptUrl = .m3u8/.mp4, useOkhttp = false, timeout 8s)
- aniwavesExtractRaw — search → detail → servers → resolve per (server × subType)

## BingeAnimeCtx.kt
- BingeAnimeCtx — application context holder

## BingeAnimePlugin.kt
- @CloudstreamPlugin entry
- load — BLog.init, BingeAnimeCtx boot, ShikimoriApi.init, registerMainAPI, openSettings, ShikimoriApi.warmPrefetch
- No custom ExtractorApi classes (AniWaves uses built-in WebViewResolver)

## BingeAnimeProvider.kt
- ROW_SEP
- ROWS — data config + label list (27 rows)
- BingeAnimeProvider — MainAPI
  - mainPage — filtered by BingeAnimeSettings.isRowEnabled
  - getMainPage — Donghua → AniMapper.donghua, Trending → AniMapper.trending + Shikimori fallback, else Shikimori.fetchForRow
  - rebuildExclusions + isExcluded filter
  - search — AnimeSchedule or AniList primary (BingeAnimeSettings.getSearchSource)
  - load — dispatch by URL prefix (animeschedule:, shikimori:, animapper:, anilist:)
  - loadLinks — parallel fan-out AniKage + AniWaves, progressive emit, cache-hit path uses MirrorBundle
  - emitMirror, emitMirrorScored — 3-param / 4-param variants
  - toSearchResponse — AniListApi.Entry → SearchResponse
  - statusTag mapping
- StreamQuery — data class (title, year, type, sourceUrl, season, episode, totalEpisodes)
- encodeQuery, decodeQuery

## BingeAnimeSettings.kt
- Colors
- K_CONCURRENCY, K_PREFETCH, K_PREFILTER, K_VERBOSE
- K_SUB_LANGS
- K_SRC_ANIKOTO, K_SRC_ANIZONE, K_SRC_OTAKUTSU, K_SRC_ANIKAGE, K_SRC_ANIWAVES
- K_SEARCH_SOURCE
- K_ROW_ORDER, K_ROW_PREFIX
- K_YEAR_FILTER_ON, K_YEAR_FLOOR
- getConcurrency, isPrefetchEnabled, isPrefilterEnabled, isVerbose
- SubLang, ALL_SUB_LANGS
- enabledSubLangs, isSubLangEnabled, setSubLangEnabled, subLangMatches, allSubLangs
- isSrc* toggles
- isYearFilterEnabled, getYearFloor, getYearFloorIfEnabled
- getSearchSource, setSearchSource
- getRowOrder, setRowOrder, isRowEnabled, resetHomeToDefaults, DEFAULT_ON_ROWS
- dp, themedSwitch, shape, stagger
- GlyphView — canvas glyph tiles
- tile — settings tile factory
- show — root dialog
- subWindow — sub-dialog scaffold
- openSettings, openSubtitles, openSources, openHomepage, openLogs
- toggleRow, stepperRow, actionRow, arrowBtn, labelBlock
- yearScrollPicker

## BLog.kt
- buffer, lock, writer, verbose, pendingWrites, appContext
- setVerbose, isVerbose
- RX_* sanitize patterns
- init — file setup
- d, v, e, section
- recent, allSanitized, count, clear

## Cache.kt
- MirrorBundle — data class (mirrors: List<ScrapedMirror>, subSources: List<Pair<String,String>>)
- BCCache
  - map, bundleMap, lock
  - get, put — text cache
  - getBundle, putBundle — 30min, 16 entries
  - getMirrors, putMirrors — thin wrappers around bundle API
  - clear

## LinkScore.kt
- prelimScore — quality + source reputation + captions bonus
- emoji — 🟢🟡🔴 bands

## LogScrollbar.kt
- LogScrollbar — custom scrollbar

## MalApi.kt
- BASE — https://api.myanimelist.net/v2
- CACHE_TTL, FIELDS
- headers() — X-MAL-CLIENT-ID
- search, ranking, detail
- parseList, parseEntry
- mapMediaType, mapRelationType
- Not called by active code path

## MegaPlayEmbed.kt
- MEGAPLAY_ENC_IV, MEGAPLAY_ENC_KEY, MEGAPLAY_TOKEN_SECRET
- MEGAPLAY_PROXY_MAP
- megaplayProxyHost
- b64UrlNoPad
- signMegaPlayUrl
- decryptMegaPlaySources
- megaplayExtract — AniKage fallback extractor

## PrefetchEngine.kt
- DEBOUNCE_MS, HOME_GRACE_MS, HISTORY_SIZE
- SCOPE, inFlight
- watchHistory, currentAnimeKey, sessionJobs
- lastHomeRenderMs
- markHomeRender, isFromHome
- beginSession, recordPlay
- warmOnLoad, warmAfterPlay
- plan, warm
- obtain — in-flight dedup
- StreamQuery.cacheKey, StreamQuery.animeKey — extensions

## ShikimoriApi.kt
- BASE — https://shikimori.one/api
- IMG_BASE
- UA — "BingeAnime/1.0"
- ROW_TTL, GENRE_TTL
- requestLock (Mutex), lastRequestMs, MIN_GAP_MS = 250 — strict serial throttle
- throttled — rate-limited wrapper
- prefetchScope, lastPrefetchMs, prefetchRunning (AtomicBoolean)
- inFlightRows
- genreMap, genreMapDeferred
- prefs, cacheDir, initialized
- DYNAMIC_ROWS, exclusions
- coverOverrides
- headers()
- ROW_QUERY — row label → Triple(param, value, sort)
- init — disk cache load + stale revalidation
- fetchForRow — year filter + hourly shuffle + cover override + AniMapper fallback
- persistCoverOverrides
- fetchRawForRow — in-flight dedup + cache
- fetchAndStore
- ensureGenreMap, compareAndSetDeferred, resolveRowId
- detail
- warmPrefetch — top 8 rows
- resetForClearCache
- prefetchRowsNow
- dedupeKey, isExcluded, rebuildExclusions
- parseList, parseEntry

## StreamScrapers.kt
- ScrapedMirror — data class
- AniKageScrape — data class (mirrors, subs)
- PER_SOURCE_TIMEOUT_MS = 8000
- ANIKAGE_CONCURRENCY = 4 — hardcoded
- SUB_PROVIDERS = {"koto", "suge"}
- SERVER_PRIORITY = ["koto", "suge", "kiwi", "wave"]
- DROPPED_SERVERS = {"dib", "megg", "zen"}
- STOP_WORDS
- stripQualifiers, matchScore, pickBest
- lookupAniListRef — sourceUrl → AniList entry
- trySearchAndPick — AniKage search → best
- isEnglish
- md5
- fetchSubLocal — internal, downloads VTT to app-private storage with Accept-Encoding: identity
- anikageExtractRaw — progressive emit (onLink/onSub), kiwi fallback
- anikageExtractRaw — no-emit overload
- scrapeAllSources — parallel source dispatcher

## SubServer.kt
- SubServer — local HTTP server for VTT files
- serverSocket, port
- ensureStarted, urlFor
- handle — request parser (path traversal filter)
- writeStatus

# ═══════════════════════════════════════════════════════════════
# CROSS-CUTTING — Dev-build analytics gate
# ═══════════════════════════════════════════════════════════════
- build.yml passes IS_DEV_BUILD (github.ref_name == 'dev')
- Every module's build.gradle.kts emits BuildConfig.IS_DEV_BUILD
- RepoAnalytics.ping / OAnalytics.ping early-return when true

# ═══════════════════════════════════════════════════════════════
# CROSS-CUTTING — AniKage rate limits
# ═══════════════════════════════════════════════════════════════
- /sources ceiling: 15 req/min
- ANIKAGE_CONCURRENCY = 4 (hardcoded)
- Per-source timeout: 8s, whole-scrape: 24s
- Cache TTL: 10 min per resolved episode

# ═══════════════════════════════════════════════════════════════
# CROSS-CUTTING — AniWaves / Vidplay
# ═══════════════════════════════════════════════════════════════
- Only server sv-id 4 (Vidplay) resolves reliably
- Resolve via CloudStream WebViewResolver (useOkhttp = false)
- Click script nudges JW Player play button
- 8s WebView timeout per embed
- No subs exposed by Vidplay
- Concurrency capped 1..6 (user setting)

# ═══════════════════════════════════════════════════════════════
# CROSS-CUTTING — Cloudflare UA binding
# ═══════════════════════════════════════════════════════════════
- CfSolverDialog.CF_UA, CloudflareHelper.CF_UA, MlsbdApi.MLSBD_UA must be identical
- cf_clearance is bound to the UA that solved it
