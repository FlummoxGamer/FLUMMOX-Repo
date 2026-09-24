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

## gradle.properties
path: gradle.properties
- bingecloud_version — SINGLE SOURCE OF TRUTH for plugin version

## patch_plugins.py
path: patch_plugins.py
- reads NEW_VERSION, OUTPUT_BRANCH, GITHUB_REPOSITORY env
- rewrites builds/plugins.json URLs + versions


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
- AnikotoSeries, anikotoFindSeries, anikotoGetServerIds, anikotoResolvePlayerUrl
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
