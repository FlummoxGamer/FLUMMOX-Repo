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
- includes :BingeCloud, :Otakutsu, :BingeAnime

## gradle.properties
path: gradle.properties
- bingecloud_version — SINGLE SOURCE OF TRUTH for BingeCloud version
- otakutsu_version — plugin version for Otakutsu
- bingeanime_version — plugin version for BingeAnime

## build.yml
path: .github/workflows/build.yml
- Triggers on push to main, master, dev
- Outputs to builds/ (main) or builds-dev/ (dev)
- Env passed to `gradle make` and `makePluginsJson`:
  - TMDB_API_KEY, TVDB_API_KEY (BingeCloud)
  - MAL_CLIENT_ID (BingeAnime — MAL fallback search, currently unused)
  - ANIMESCHEDULE_API_KEY (BingeAnime — search fallback)
  - IS_DEV_BUILD (analytics gate)
- Reads bingecloud_version for the CI sanity check + NEW_VERSION env

## patch_plugins.py
path: patch_plugins.py
- reads NEW_VERSION, OUTPUT_BRANCH, GITHUB_REPOSITORY env
- rewrites builds/plugins.json URLs + versions
- reads src/gradle.properties → {internalName.lower()}_version per plugin
- currently handles BingeCloud, Otakutsu, BingeAnime automatically


## BingeCloud/build.gradle.kts
path: BingeCloud/build.gradle.kts
- android — namespace com.flummox.bingecloud, compileSdk 35, minSdk 21
- defaultConfig — TMDB_API_KEY, TVDB_API_KEY, PLUGIN_VERSION, IS_DEV_BUILD from env
- cloudstream — description, authors, tvTypes, version from bingecloud_version
- dependencies — cloudstream3 pre-release, NiceHttp, jsoup, okhttp, jackson, coroutines


## package: com.flummox.bingecloud
## path: BingeCloud/src/main/java/com/flummox/bingecloud/


## AiometaApi.kt
- AIOMETA_BASE — Aiometa metadata base URL
- AioCast, AioVideo, AioAppExtras, AioMeta, AioMetaResponse, AioCatalogResponse — data models
  - AioAppExtras.seasonPosters marked @JsonIgnore — AIOMetadata sometimes returns an
    object here, not String[]. Jackson hard-failed on type mismatch. Field unused.
- aioFetchMeta — fetch single meta by type/id (diagnostic logs HTTP code + body head on failure)
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
- sameBaseTitle — normalized equality check
- parseEntry, entryToJson, parseSearchResponse — helpers


## AniZoneApi.kt
- BASE — https://anizone.to
- RX_NON_ALNUM, RX_WS, RX_JSON_PARSE_TPL, RX_PLAYER, RX_CUT — regexes
- Hit, Episode, StreamResult — data models
- searchKey, epsKey, SEARCH_TTL, EPS_TTL — cache keys/TTLs
- unescapeJs, extractJsonParse, normalize — parsing helpers
- buildVariants — generate shortened query variants
- search — AniZone search (cached 30min)
- pickBest — pick best AniZone hit
  — fuzzy step now requires containsAll either direction (query tokens fully
    inside candidate, or vice versa). Blocks "The Final Problem" matching
    "Shingeki no Kyojin: The Final Season" on shared stopwords.
- getEpisodes — episode list (cached 60min)
- getStream — resolve m3u8 from vidstackPlayer JSON
- pickBestAniList — pick best AniList entry by title/year
- tryNextPartInChain — out-of-range ep → next part on site
- resolve — main entry, uses AniList titles for anime
- mirror — ScrapedMirror builder


## BCLog.kt
- init — file + buffer setup
- d, v, e, section — log levels (d=normal, v=verbose, e=error)
- allSanitized, count, clear — debug UI support
- sanitize — regex redaction (JWT, bearer, cookie, CF, signCookie)
- setVerbose, isVerbose — toggle


## BingeCloudPlugin.kt
- BingeCloudPlugin — @CloudstreamPlugin entry point
- load — boot: RepoAnalytics.ping, BCLog.init, HostHealth.init, restoreMbSession, Prewarm.fire
- registerMainAPI(BingeCloudProvider)
- registerExtractorAPI: VCloud, GDirect, Filepress


## BingeCloudProvider.kt
- SEP, ROW_TAG, PREFETCH_DEBOUNCE_MS, HOME_GRACE_MS — constants
- PREFETCH_SCOPE, activePrefetchJob, lastHomeRenderMs — prefetch state
- StreamQuery.cacheKey — extension
- ADULT_TERMS, HOME_BLOCKED_GENRES — filter lists
- isAdultContent, AioMeta.isJunk — filters
- BingeCloudProvider — MainAPI class
  - getMainPage — row dispatch
  - resolveRow — row type → source
  - jwTypeFor — rowType → JustWatch objectType
  - routeWestern, routeIndian, routeBanglaTVDB — routing
  - getHindiMergedPool, validateHindiSeries — Hindi pool
  - routeLanguageTVDB, routeLanguage — language rows
  - search, searchViaTmdb — search
  - RX_SEASON_N, RX_EXTRA_MARKER, GROUP_STOPWORDS
  - isExtraTitle, seasonNumberOf, searchGroupKey — grouping helpers
  - mergeSearchResults, searchDedupeKey — merge + group + Part/season dedupe
  - tmdbSeasonNumbers — fetch TMDB season list for id (24h cache)
  - AniListApi.Entry.toAniListSearchResponse — mapper
  - searchViaAiometa [DEAD — unused]
  - AioMeta.toSearchResponse, TmdbSearchItem.toSearchResponse — mappers
  - load — detail fetch (Aiometa → TMDB fallback → AniList direct)
  - loadFromAniList — anilist: URL entry point
  - loadLinks — mirror sort + emit (no probe — removed v217)
  - hostOf, qualityRank, audioPriority, computeStatusTag — helpers
- encodeQuery, decodeQuery — JSON serialize StreamQuery
- TmdbSearchResponse, TmdbSearchItem — data models
- RX_COUR — filter AniList "Cour N" entries at search boundary


## Cache.kt
- BCCache — LRU cache
  - get, put — text cache (5min default)
  - getMirrors, putMirrors — scrape results (30min, 16 entries)
  - clear


## CfSolverDialog.kt
- CfResult — data model (html, cookie)
- CfSolverDialog.resolve — main entry, WebView-based solver
- solve — dialog + WebView + JS bridge
- JS_INSTALL_OBSERVER, JS_DETECT_CF — injected scripts
- CfBridge — JS → Kotlin HTML push (drops CF challenge pages)


## CloudflareHelper.kt
- BingeCloudCtx — context holder
- CF_INDICATORS — CF page marker list
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
- jwDiscoverByLanguage — filter by originalLanguages
- jwFetch — internal GraphQL POST + parse


## LinkScore.kt
- prelimScore — 0-100 score per mirror; demotes MovieBox sbcdn5 edge (−50) —
  sbcdn5 serves certain subjects at unsustainable bitrate (Reacher 2022
  confirmed, sticky per subject ID)
- emoji — 🟢🟡🔴 bands
- hostOf — URL → host


## LogScrollbar.kt
- LogScrollbar — custom draggable scrollbar View for debug log box


## MlsbdApi.kt [DISABLED]
- MLSBD_BASE — https://mlsbd.co
- MLSBD_UA — must match CF UA exactly
- mlsbdHttpClient — OkHttp with MlsbdDns
- mlsbdFetch, mlsbdFetchVia, mlsbdSearch, mlsbdFindPage
- mlsbdResolveSavelinks, mlsbdExtractFromMulticloud, mlsbdExtractPlayerStream
- mlsbdDecodeBongHd — x_data decoder (URL → strip:N → ROT13 → base64)
- mlsbdResolveToMirrors, mlsbdExtractRaw
- REVIVAL NOTES in file header


## MlsbdDns.kt [DISABLED]
- MlsbdDns — custom OkHttp Dns resolver with DoH fallback


## MovieBoxApi.kt
- MB_SECRET_B64, MB_SECRET_ALT_B64, MB_VERSION_CODE, MB_VERSION_NAME, MB_PACKAGE, MB_INSTALL_STORE, MB_UA — DO NOT TOUCH
- MB_HOSTS — native API host pool (api3..api6, api4sg)
- MB_WEB_DOMAINS — 7 candidate web domains for /wefeed-h5api-bff/subject/play
- MB_WEB_UA — Chrome 154 Mobile UA for web path
- MB_BOOTSTRAP_HOST, MB_BOOTSTRAP_PATH
- deviceId, clientInfo — request fingerprint
- md5Hex, b64DecodeBytes, b64Encode — encoding helpers
- generateXClientToken, buildCanonicalString, generateXTrSignature, buildHeaders — signing
- parseJwtExp — decode exp claim
- restoreMbSession — reads saved token from Settings, restores mbSession
  (real implementation now; previously a no-op stub)
- bootstrapToken, ensureSession
- mbGet — GET with retry + cache
- MBSubject, MBStream — data models (MBStream has emitHeaders: Map<String,String>)
- highestQuality — pick max resolution from "1080,720,480" → "1080p"
- extractPolicyResource — decodes signCookie (new urlprefix + old CloudFront)
- mbSearch, parseSearchResults — search
- mbDetail, mbLanguages — detail + audio tracks
- mbPlay — orchestrator: fetch detail → try web → fallback native
- mbPlayWeb — iterate MB_WEB_DOMAINS, hit /wefeed-h5api-bff/subject/play,
  parse dash/hls/streams
- parseWebStreams — apply skip rules, extract signCookie + signHeaderKey,
  build emitHeaders (Origin / Referer / UA / sign cookie)
- mbPlayNative — original play-info + resourceDetectors, DASH-first sort


## Settings.kt
- RowSpec — data class for home row
- K_* — all pref keys (rows, sources, tokens, quality, prefilter, prefetch, verbose, CF cookies)
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
- Colors — UI palette constants (BG, SKY_*, CARD, ROW, ACCENT, TEXT, etc.)
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
  — StreamQuery has totalEpisodes: Int and originalLanguage: String
- DOMAIN_JSON_URL, DOMAIN_CACHE_TTL — domain resolver
- resolveDomain — get latest domain from SaurabhKaperwan/Utils
- STOP_WORDS, stripQualifiers, titleMatches — title matcher
- SEASON_MATCH_ALL_TOKENS, extractSeasons, pageHasSeason — season parsing
- cachedGet, safeGet — HTTP helpers
- vegamoviesFindPage, vegamoviesExtractMovieRaw, vegamoviesExtractSeriesRaw — VM
- moviesdriveFindPage, moviesdriveExtractMovieRaw, moviesdriveExtractSeriesRaw, extractFromArchivePage — MD
- hdhub4uFindPage, hdhub4uExtractRaw — HDH
- movieboxExtractRaw — MB; wires MBStream.emitHeaders into ScrapedMirror.headers
- prettyAudio — audio label normalizer
- ANIKOTO_DOMAIN, ANIKOTO_UA, anikotoBrowserHeaders, anikotoAjaxHeaders
- anikotoResultString, anikotoResultUrl, anikotoScore
- AnikotoSeries, anikotoFindSeries, AnikotoEpInfo, anikotoGetEpInfo, anikotoResolvePlayerUrl
  - anikotoResolveFromServerIds — serverIds → mirrors
  - anikotoChainWalk — out-of-range → AniList chain → next part on site
  - isSeasonSpecific — season-marker check for AniKoto season search
- anikotoExtractRaw — AniKoto (Part-N offset applied here)
- resolveWrapper — wrapper URL resolver
- PER_SOURCE_TIMEOUT_MS
- scrapeAllSources — parallel source dispatcher
  — sources-done log line includes ANIZONE bucket


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
- State, GroupStatus — status enums/data
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
- defaultConfig — PLUGIN_VERSION, IS_DEV_BUILD from env
- cloudstream — description, authors, tvTypes=[Anime, AnimeMovie], version from otakutsu_version
- dependencies — cloudstream3 pre-release, NiceHttp, jsoup, okhttp, jackson, coroutines


## package: com.flummox.otakutsu
## path: Otakutsu/src/main/java/com/flummox/otakutsu/


## ActionHealth.kt
- ActionHealth — action-ID health state for OSettings badge
- K_LAST_OK, K_LAST_STALE — CloudStream pref keys
- markOk — called by fetchChain when sources parse successfully
- markStale — called by fetchChain when stale-detection heuristic fires
- isHealthy — true if last OK >= last stale (or both zero)


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


## OAnalytics.kt
- OAnalytics — anonymous daily ping (same Worker + AUTH_TOKEN as BingeCloud)
- PREFS = "otakutsu_repo_analytics" — separate install ID namespace
- PING_INTERVAL_MS — 24h throttle
- getOrCreateInstallId — persistent UUID in CloudStream prefs
- ping — POST {timestamp, installId, repo, ext, ver} with X-Auth-Token


## OCache.kt
- OtakutsuSource — data class (label, server, subType, url, tracks)
- PrefetchCache — data class (sources, cookieHeader)
- OCache — LRU prefetch cache
- getPrefetch, putPrefetch, hasPrefetch
- TTL_VALID = 30min, TTL_EMPTY = 60s
- clear, size


## OLog.kt
- init — file + buffer setup; loads persisted verbose pref from CloudStream keys
- d, v, e, section — normal + verbose log levels (▸ prefix on verbose lines)
- setVerbose, isVerbose — toggle persisted across sessions
- allSanitized, allVerboseSanitized — UI feeds for log windows
- count, countVerbose — line counts for header
- clear — wipes file + buffer, reopens writers
- sanitize — regex redaction (JWT, bearer, cookie, CF) applied to both views
- Files: otakutsu_log.txt (plain), otakutsu_verbose.txt (verbose)


## OLogScrollbar.kt
- OLogScrollbar — custom draggable scrollbar View (dim gray 0x8C7A7A7A thumb)


## OSettings.kt
- show — main dialog: OTAKUTSU hero + EXTENSION badge + SETTINGS/LOGS tiles + CLOSE
- showSettings — sub-window: prefetch toggle, subtitles toggle, clear cache
- showLogs — sub-window: verbose toggle row, log view, REFRESH/SAVE/COPY/CLEAR buttons
- isPrefetchEnabled, setPrefetchEnabled — prefs (K_PREFETCH)
- isSubtitlesEnabled, setSubtitlesEnabled — prefs (K_SUBS)
- makeTile, subWindow, stagger, shape, ripple — UI builders
- Colors — BG, SURFACE, BORDER, TEXT, SUBTEXT, ACTIVE, RED, LOG_TEXT
- Badge ACTIVE dot pulses 0.25↔1.0 alpha at 1200ms cycles


## OtakutsuPlugin.kt
- OtakutsuPlugin — @CloudstreamPlugin entry point
- load — OLog.init, OAnalytics.ping, registerMainAPI(OtakutsuProvider)
- openSettings → OSettings.show


## OtakutsuProvider.kt
- OtakutsuProvider — MainAPI class
  - mainUrl = https://otakutsu.cc
  - name = Otakutsu
  - hasMainPage, hasQuickSearch, hasDownloadSupport, supportedTypes = [Anime, AnimeMovie]
  - NEXT_ACTION_ID — hardcoded fallback (787faac6445fbc39cfe9376659cbfb5168c3f714b2)
    Turbopack does NOT emit server action IDs to client JS
  - playbackCookie — session cookie state captured from bootstrap+session
  - baseHeaders, browserHeaders, playbackHeadersFn — header sets
  - homeHtml, homeTime, homeDoc, HOME_TTL (10min) — home cache
  - totalEpsByAnime — per-anime episode count for prefetch planning
  - getHomeHtml, getHomeDoc — cached accessors
  - getMainPage — parse <section id=X> cards from home HTML
  - search — GET /api/feed/search (English → Romaji → Native title chain)
  - load — parallel /anime/{id} + /watch/{id}?ep=1, parse metadata + episodes
  - buildStateTree — Next.js router state JSON for RSC action POST
  - fetchChain — bootstrap → session → cookie collect → POST /watch/{id}?ep=N → parse sources[]
  - loadLinks — PrefetchEngine.obtain → probe each source → emit ExtractorLink (M3U8)
  - clearSectionCache — static; wipes in-memory home section cache
  - Stale-action heuristic: rsc.len > 20KB + contains "$Sreact.fragment" + no "sources" → markStale()
  - ActionHealth.markOk() on successful sources parse


## PrefetchEngine.kt
- DEBOUNCE_MS = 800, HOME_GRACE_MS = 5000, HISTORY_SIZE = 5
- SCOPE — SupervisorJob + Dispatchers.IO
- inFlight — ConcurrentHashMap<String, CompletableDeferred<PrefetchCache?>>
- watchHistory — per-anime last-5 plays
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
- obtain — public in-flight dedup entry


## SubtitleFetcher.kt
- SubtitleFetcher — orchestrator for AniKoto + AniZone subs
- fetch — runs both in parallel, 6s timeout each, merges + dedupes by URL
- Sub — data class (label, url) with source prefix applied
- cache — LruCache<String, Entry>, TTL 7 days in-memory
- clear — wipes cache


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
- Fallback is authoritative until Otakutsu rotates their action source
- Stale detection: RSC >20KB containing "$Sreact.fragment" but no "sources" → log OTAKUTSU UPDATED


# ══════════════════════════════════════════════════════════════
# BINGEANIME MODULE
# ══════════════════════════════════════════════════════════════

## BingeAnime/build.gradle.kts
path: BingeAnime/build.gradle.kts
- android — namespace com.flummox.bingeanime, compileSdk 35, minSdk 21
- defaultConfig — PLUGIN_VERSION, IS_DEV_BUILD, MAL_CLIENT_ID, ANIMESCHEDULE_API_KEY from env
- cloudstream — description, authors, tvTypes=[Anime, AnimeMovie], version from bingeanime_version
- dependencies — cloudstream3 pre-release, NiceHttp, jsoup, okhttp, jackson, coroutines


## package: com.flummox.bingeanime
## path: BingeAnime/src/main/java/com/flummox/bingeanime/


## AniListApi.kt
- ENDPOINT — https://graphql.anilist.co
- JSON_MEDIA — application/json media type
- CACHE_TTL — 24h
- Title — data model (romaji, english, native) with .all()
- Entry — universal data model shared by every source
  — fields: id, idMal, title, format, episodes, seasonYear, startDate,
    description, coverImage, bannerUrl, averageScore, status, genres,
    country, relations, source, sourceId, subEpisodes, dubEpisodes
  — `source` values: "anilist" | "mal" | "shikimori" | "animapper" | "animeschedule"
  — `sourceId` used by sources whose IDs are strings (AnimeSchedule routes)
- Relation — flattened to avoid R8 metadata rewriter crash on cycles
- MEDIA_FIELDS, MEDIA_FIELDS_WITH_RELATIONS — GraphQL projections
  — MEDIA_FIELDS includes nextAiringEpisode { episode } and bannerImage
- CatalogSpec — batch query descriptor (key, sort, genre, tag, format, country, status, year)
- fetchCatalogBatch — alias-batched GraphQL: one HTTP request for N rows
  — safeAlias sanitizes key for GraphQL alias charset
  — inFlightBatch dedupes concurrent callers
- parsePageArray — extract entries from a batch alias response
- parseList — extract Page.media
- searchAnime — GraphQL search, SEARCH_MATCH sort, 20 hits
- getEntry — full entry with relations by AniList ID
- PartInfo, parsePartInfo — Part/Season marker parsing
- RX_COUR_N, RX_ROMAN_WORD, ROMAN_MAP — display helpers
- stripCourBranding — "X Cour N" → "X" (N<=1) or "X Part N"
- baseTitleKey — franchise grouping key (before-first-colon prefix)
- convertRomanSeasons — "… III: …" → "… Season 3: …"
- formatPriority — TV 0, ONA 1, MOVIE 2, OVA 3, SPECIAL 4, TV_SHORT 5
- relevanceRank — 0 exact, 1 prefix, 2 contains, 3 fuzzy
- titleOf — pick best title (english → romaji → native)
- SPINOFF_MARKERS, isSpinoff — side-content detection
- effectiveFormatPriority — spinoffs forced to 100
- seasonOrdinal — Season N | roman | Final Season → 99 | bare trailing N
- partOrdinal — "Part N" / "Cour N"
- sortChronological — relevance → format → season → part → year → date
- filterAndSortChronological — drop tier-3 when any tier 0-2 exists
  (fixes AnimeSchedule fuzzy-match noise like "Tsuihou Sareta Cheat...")
- mergeKey — MAL ID primary, title+format fallback
- parseEntry — AniList JSON → Entry (source = "anilist")


## AniMapperApi.kt
- BASE — https://api.animapper.net/api/v1
- ROW_TTL — 6h
- headers — Accept + custom UA
- ANILIST_GRAPHQL — https://graphql.anilist.co (rating enrichment)
- RATING_TTL — 24h per-ID rating cache
- JSON_MEDIA — application/json media type
- CN_TTL — 1h CN ID set cache
- cnIds, cnIdsFetchedAt — cached CN ID set (search responses omit countryOfOrigin)
- ensureCnIds — one query returns ~100-200 CN IDs, cached 1h
- enrichRatings — batch fetch missing ratings via one AniList GraphQL query
  — per-ID cache 24h; new IDs fetch, cached are free
  — wired into trending(), donghua(), detail()
- trending — sortBy=UPDATED_AT + status=RELEASING; 30% CN cap; no row cache
- donghua — countryOfOrigin=CN; optional yearFloor; cached 6h
- searchByTitle — for Shikimori cover fallback; tries ?q= then ?title=
- detail — /metadata?id=N, unwraps {result}, runs enrichRatings
- parseResponse — flexible shape: results | data | data.results | data.media
- parseEntry — maps titles.{ja-ro,main,user-preferred,en,ja} + images.{coverXl,coverLg,coverMd,bannerUrl} + totalUnits


## AnimeScheduleApi.kt
- BASE — https://animeschedule.net/api/v3
- IMG_BASE — https://img.animeschedule.net/production/assets/public/img/
- ROW_TTL — 6h
- headers — Bearer token from BuildConfig.ANIMESCHEDULE_API_KEY
- search — GET /anime?q=...&st=popularity; 30s timeout via app.get
- detail — GET /anime/{route}; no per-episode endpoint; counts from *Override
- parseList — extract from `anime` array
- parseEntry
  — reads names.{romaji, english, native, abbreviation, synonyms}
  — format mapped by substring ("tv short" → TV_SHORT, "tv" → TV)
  — episodes: max of subEpisodeOverride / episodeOverride / dubEpisodeOverride
    (each is an object with nested overrideEpisode int)
  — subEpisodes / dubEpisodes from their respective overrides
  — score: stats.rating (0-100) > stats.averageScore (0-100) > stats.score (0-10)
  — sourceId = route slug; id = route.hashCode()


## BLog.kt
- init — file + buffer setup
- d, v, e, section — log levels (d=normal, v=verbose, e=error)
- recent(n) — last n lines (used in tile preview if any)
- allSanitized, count, clear — debug UI support
- sanitize — regex redaction (JWT, bearer, cookie)
- setVerbose, isVerbose — toggle


## BingeAnimePlugin.kt
- BingeAnimePlugin — @CloudstreamPlugin entry point
- load — BLog.init, ShikimoriApi.init, registerMainAPI(BingeAnimeProvider),
  openSettings → BingeAnimeSettings.show, ShikimoriApi.warmPrefetch


## BingeAnimeProvider.kt
- ROW_SEP — "|" separator for row config
- ROWS — top-level list of (configString, label); filtered at runtime by
  isRowEnabled in `mainPage`
- BingeAnimeProvider — MainAPI class
  - mainUrl — https://shikimori.one (legacy default)
  - supportedTypes — Anime, AnimeMovie
  - mainPage — filtered by BingeAnimeSettings.isRowEnabled per label
  - getMainPage — dispatches per row:
    - "Donghua" → AniMapperApi.donghua(30, yearFloor)
    - "Trending" → AniMapperApi.trending(30) with Shikimori fallback
    - else → ShikimoriApi.fetchForRow(rowName, 30)
    — then rebuildExclusions + isExcluded filter for cross-row dedup
    — hasNext = false (CloudStream otherwise re-fetches page 2+)
  - search — primary source switch (BingeAnimeSettings.getSearchSource)
    - "animeschedule" → AnimeSchedule primary, AniList fallback
    - "anilist" (default) → AniList primary, AnimeSchedule fallback
    — AnimeSchedule path uses filterAndSortChronological (tier-3 drop)
    — AniList path uses plain sortChronological
  - load — dispatches by URL prefix:
    - animeschedule:{route} → AnimeScheduleApi.detail
    - animapper:{id} → AniMapperApi.detail
    - shikimori:{id} → ShikimoriApi.detail
    - anilist:{id} → AniListApi.getEntry
    - mal:{id} → JikanApi.detail (dead — Jikan shut down 2026-10-01)
    — sets backgroundPosterUrl = entry.bannerUrl
  - loadLinks — Phase 2 stub, returns false
  - toSearchResponse — private extension; source tag from sourceId

- StreamQuery — data class (title, year, type, sourceUrl, season, episode, totalEpisodes)
- encodeQuery, decodeQuery — JSON serialization


## BingeAnimeSettings.kt
- Colors — BG, SURFACE, SURFACE_2, BORDER, BORDER_HI, TEXT, SUBTEXT, ACTIVE, ACCENT, RED, LOG_TEXT
- K_* pref keys:
  - K_CONCURRENCY, K_PREFETCH, K_PREFILTER, K_VERBOSE
  - K_SRC_ANIKOTO, K_SRC_ANIZONE, K_SRC_OTAKUTSU
  - K_ROW_ORDER, K_ROW_PREFIX
  - K_YEAR_FILTER_ON, K_YEAR_FLOOR
  - K_SEARCH_SOURCE
- getConcurrency, isPrefetchEnabled, isPrefilterEnabled, isVerbose
- isSrcAniKoto, isSrcAniZone, isSrcOtakutsu
- isYearFilterEnabled, getYearFloor, getYearFloorIfEnabled
- getSearchSource, setSearchSource
- getRowOrder, setRowOrder, isRowEnabled (falls back to DEFAULT_ON_ROWS),
  resetHomeToDefaults
- DEFAULT_ON_ROWS — 19 rows enabled by default (dynamic + demographics + some genre)
- dp, shape — UI helpers
- stagger — animation helper
- themedSwitch — overrides Material purple: thumb TEXT/SUBTEXT, track 0x3A3A3A/0x1A1A1A
- GlyphView — Canvas vector glyphs, monochrome TEXT, idle pulse 0.75↔1.0 at 2200ms
- tile — square tile with glyph + press animation + accent underline
- show — root dialog with 4 tiles
- subWindow — scaffold with onClose + onDismiss callbacks; dialog-level
- openSettings — prefetch, prefilter, concurrency, clear cache, search source toggle
- openSources — AniKoto / AniZone / Otakutsu toggles
- openHomepage — year filter toggle, yearScrollPicker, row list with
  snapshot/restore semantics (revert on non-SAVE dismiss), RESET action
- openLogs — verbose toggle, log view, REFRESH/SAVE/COPY/CLEAR
- toggleRow, stepperRow, actionRow, arrowBtn, labelBlock — reusable rows
- yearScrollPicker — HorizontalScrollView, centered year auto-snaps,
  neighbours fade to 0.35 alpha, range 1960–2015
- Helpers: labelBlock, arrowBtn


## Cache.kt
- BCCache — text-only LRU cache
  - get(key, ttl) — default 5min
  - put(key, body)
  - clear
  — used by all sources for row + detail + rating caches


## JikanApi.kt [DEAD]
- Whole file is dead as of 2026-10-01. Jikan public API shut down permanently.
- Kept for reference only. No callers reach it except BingeAnimeProvider.load()
  `mal:` branch — but no source produces `mal:` URLs anymore.
- Delete candidate for a future cleanup commit.


## LogScrollbar.kt
- LogScrollbar — custom draggable scrollbar for the logs sub-window
- thumbColor default 0x8CB8B8B8 (gray) — was 0x8C38BDF8 (blue), changed to
  match the monochrome palette


## ShikimoriApi.kt
- BASE — https://shikimori.one/api
- IMG_BASE — https://shikimori.one
- UA — "BingeAnime/1.0" (custom; browser UAs get IP-banned)
- ROW_TTL — 1h
- GENRE_TTL — 7 days (SharedPreferences-persisted genre map)
- requestLock, lastRequestMs, MIN_GAP_MS = 250 — strict serial throttle;
  holds lock through entire HTTP call, guarantees steady 4/sec, zero bursts
- throttled<T>(block) — rate-limited wrapper used by every HTTP call
- DYNAMIC_ROWS — Trending, Top Anime Series, Top Anime Movies (excluded
  from cross-row dedup counting)
- exclusions — rowName → set of dedupeKeys to drop (cross-row dedup)
- coverOverrides — shiki id → AniMapper-resolved cover URL (persisted)
- prefs, cacheDir — SharedPreferences + filesDir/shikimori_rows
- genreMap — name → id (anime genres/themes only)
- genreMapDeferred — single-flight guard for /api/genres fetch
- ROW_QUERY — LinkedHashMap row label → Triple(paramKey, paramValue, sort)
  — "id" / "name:..." / "kind" / "order" modes
  — Isekai hardcoded as id:62 (not exposed via /api/genres)
- init — load disk cache + genre map + cover overrides; schedule
  background revalidation of stale rows (stale-while-revalidate)
- fetchForRow — public; applies year filter, hourly-seeded shuffle,
  cover override, AniMapper fallback (capped 3/row), drops still-coverless
- fetchRawForRow — in-flight dedup + resolve name→id + fetchAndStore
- fetchAndStore — throttled GET → JSON → disk + memory cache → parse
- detail — throttled GET /animes/{id}
- ensureGenreMap — single-flight fetch + 7d SharedPreferences cache
- resolveRowId — name → id lookup
- isExcluded, rebuildExclusions — cross-row dedup by dedupeKey
- resetForClearCache — wipes inFlight, exclusions, cover overrides,
  prefetch timer (called by Clear Cache button)
- warmPrefetch — enabled rows only, top 8 priority, then rest with delay
- prefetchRowsNow(rows) — on-demand prefetch after SAVE & CLOSE
- persistCoverOverrides — JSON persist
- dedupeKey — normalized title (lowercase, alnum-only, 48 chars)
- parseList, parseEntry — Shikimori JSON → Entry
  — filters /assets/* placeholder URLs (site's missing-image marker) so
    AniMapper fallback in fetchForRow triggers


# ══════════════════════════════════════════════════════════════
# CROSS-CUTTING — Dev-build analytics gate (2026-09-28)
# ══════════════════════════════════════════════════════════════

- build.yml passes IS_DEV_BUILD env (github.ref_name == 'dev')
- BingeCloud + Otakutsu + BingeAnime build.gradle.kts emit BuildConfig.IS_DEV_BUILD
- RepoAnalytics.ping() and OAnalytics.ping() early-return when true
- Effect: dev branch builds never contribute to Firebase ping counts


# ══════════════════════════════════════════════════════════════
# CROSS-CUTTING — MovieBox web-path fix (2026-09-29)
# ══════════════════════════════════════════════════════════════

- mbPlay now tries mbPlayWeb (7 candidate web domains) first, then falls
  back to mbPlayNative if web returns empty
- Web path attaches per-stream emitHeaders (Origin/Referer/UA/sign cookie)
- Pre-flight probe in BingeCloudProvider.loadLinks deleted (was tripping
  429 on CDN and poisoning subsequent ExoPlayer fetch)


# ══════════════════════════════════════════════════════════════
# CROSS-CUTTING — BingeAnime module (2026-10-02)
# ══════════════════════════════════════════════════════════════

Architecture:
- Home rows: Shikimori (18 rows) + AniMapper (Trending + Donghua)
- Search: AniList primary (default), AnimeSchedule toggle alternative
- Detail: dispatch per source prefix (anilist / animapper / shikimori /
  animeschedule)
- Ratings on AniMapper entries: enriched via one batched AniList GraphQL
  query, cached per-ID for 24h
- Cross-row dedup: any title in 3+ genre rows kept only in top 2 by
  ROW_QUERY order; dynamic rows exempt
- Year filter: opt-in floor (1960–2015), applied client-side after fetch
  (raw fetch always 50, filtered to <=30 for return)
- Hourly shuffle on rows: same hour = same order, different hour = reshuffle

Persistence:
- Shikimori row cache: filesDir/shikimori_rows/*.json, 1h TTL
- Shikimori genre map: SharedPreferences "genre_map" + "genre_map_ts", 7d TTL
- Shikimori cover overrides: SharedPreferences "cover_overrides"
- AniList rating cache: BCCache per-ID 24h
- Row toggle prefs: CloudStream keys K_ROW_PREFIX + label

Rate limiting:
- Shikimori: strict serial 250ms gap → 4/sec peak, zero bursts, no 429s
- AniMapper: 60/min server-side; fetchForRow unaffected
- AnimeSchedule: 120/min server-side
- AniList: only search + detail + rating enrichment; kept well under 30/min

Dead code:
- JikanApi.kt unused (Jikan public API shut down 2026-10-01)
- BingeAnimeProvider.loadLinks is a stub (scrapers pending)
