# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Debug build
./gradlew app:assembleDebug

# Release build
./gradlew app:assembleRelease

# Clean build
./gradlew clean

# Run lint
./gradlew app:lint

# Run all unit tests
./gradlew app:test

# Run single test class
./gradlew app:test --tests "com.hippo.ehviewer.client.parser.GalleryListParserTest"

# Run single test method
./gradlew app:test --tests "com.hippo.ehviewer.client.parser.GalleryListParserTest.testParseGalleryList"

# Generate DAO classes (after modifying database schema)
./gradlew :daogenerator:executeDaoGenerator
```

Generated APKs are located in `app/build/outputs/apk/`.

## Architecture Overview

EhViewer is an E-Hentai gallery browser for Android using a custom scene-based navigation system.

### Scene Navigation System

The app uses a custom scene framework instead of standard Fragment navigation:

- **SceneFragment** (`com.hippo.scene.SceneFragment`): Base class for all scenes, similar to Fragment
- **BaseScene** (`com.hippo.ehviewer.ui.scene.BaseScene`): Extends SceneFragment with drawer and snackbar support
- **StageLayout** (`com.hippo.scene.StageLayout`): Container that manages scene transitions
- **Announcer** (`com.hippo.scene.Announcer`): Used to launch scenes with arguments

Scenes are organized under `ui/scene/`:
- `gallery/list/` - Gallery list, favorites, subscriptions
- `gallery/detail/` - Gallery detail view
- `download/` - Download management scenes
- `sign/` - Sign-in related scenes

### Network Layer

- **EhClient**: Central client for all API requests, uses AsyncTask with thread pool
- **EhEngine**: Contains actual request execution logic and HTML parsing
- **EhRequest**: Wrapper for request parameters and callbacks
- **Parsers** (`client/parser/`): HTML/JSON parsers for E-Hentai responses (GalleryDetailParser, GalleryListParser, etc.)

### Image Loading & Downloading

- **SpiderQueen**: Core component for gallery image fetching - manages download queue, decoding, and caching
- **SpiderDen**: Handles download directory management and file operations
- **SpiderInfo**: Stores per-gallery metadata (page offsets, download state)
- **GalleryProvider2**: Abstract provider for gallery images (EhGalleryProvider for network, DirGalleryProvider for local files)
- **Native Image Processing**: C/C++ code in `app/src/main/cpp/` handles GIF, WebP, and JPEG decoding via JNI

### Database (GreenDAO)

- **DAO classes** in `dao/` package are auto-generated
- **EhDB**: Central database helper wrapping GreenDAO operations
- **daogenerator module**: Generates DAO classes. Run `./gradlew :daogenerator:executeDaoGenerator` after schema changes
- **Database version**: Defined in `EhDaoGenerator.java` as `VERSION` constant
- Entity classes: DownloadInfo, HistoryInfo, LocalFavoriteInfo, QuickSearch, Filter, RemotePushInfo, etc.

**Adding a new entity:**
1. Add entity definition in `EhDaoGenerator.java`
2. Add adjust method if custom fields/methods needed
3. Increment `VERSION` constant
4. Run `./gradlew :daogenerator:executeDaoGenerator`
5. Add helper methods in `EhDB.java`

### Key Data Flows

1. **Gallery List**: EhClient → EhEngine.getGalleryList → GalleryListParser → GalleryListScene
2. **Gallery Detail**: EhClient → EhEngine.getGalleryDetail → GalleryDetailParser → GalleryDetailScene
3. **Image Download**: SpiderQueen fetches pages via GalleryPageApiParser, downloads via OkHttpClient, decodes via native code
4. **Scene Navigation**: MainActivity hosts StageLayout, scenes launched via Announcer with startScene()

### Event Bus

Uses EventBus (`org.greenrobot:eventbus`) for cross-component communication. Events in `event/` package:
- `GalleryActivityEvent`: Gallery interaction events
- `SomethingNeedRefresh`: UI refresh signals (bookmarkDrawNeed, downloadLabelDrawNeed, downloadInfoNeed, galleryListNeedRefresh)

**Important**: When posting events that should update UI, ensure EventBus is registered (`EventBus.getDefault().register(this)`) in onCreate/onStart and unregistered in onDestroy/onStop.

## Native Code

The app uses JNI for image decoding performance:
- `gif/` - GIF decoding (giflib)
- `jni/libjpeg-turbo/` - JPEG decoding
- `jni/libwebp/` - WebP decoding
- `image.c`, `gifutils.c`, `fdutils.c` - JNI bridges

Native entry point: `Native.kt` provides Kotlin wrappers for JNI functions.

## Configuration

- **Settings** (`Settings.java`): SharedPreferences wrapper for all user preferences
- **EhConfig**: Per-request configuration (cookies, headers)
- **EhUrl**: URL builders for E-Hentai/ExHentai endpoints

## Download Modes

The app supports two download modes (configured in Settings):

- **Local mode** (`DOWNLOAD_MODE_LOCAL`): Downloads to device storage via SpiderQueen
- **Remote mode** (`DOWNLOAD_MODE_REMOTE`): Pushes download task to NAS server via `RemoteDownloadClient`

DownloadsScene has two tabs: local downloads and remote push records.

## Product Flavors

- `appRelease`: Standard release build
- Debug builds add `.debug` suffix to applicationId

## Dependencies

- OkHttp 3.14.7 (network layer)
- jsoup 1.15.4 (HTML parsing)
- GreenDAO 3.0.0 (database)
- EventBus 3.3.1 (event bus)
- AndroidX, Material Components
- Firebase Crashlytics & Analytics (optional, requires google-services.json)