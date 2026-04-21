# Calliope Android App - План рефакторингу

## Мета
Підвищення стабільності, тестованості та підтримуваності застосунку через впровадження сучасних архітектурних патернів.

---

## Фаза 1: Критичні виправлення (2-3 тижні)

### 1.1 Виправлення threading проблем
- [ ] **Utils.java** - Замінити blocking ping на coroutine/async
- [ ] **WebFragment.java** - Видалити `StrictMode.permitAll()`, виправити реальні проблеми
- [ ] **CheckService.kt** - Замінити `while(true)` на proper lifecycle-aware scanning

### 1.2 Розбиття God Classes
- [ ] **BaseActivity.java** (608 рядків) розбити на:
  - `StateObserverActivity` - спостереження за станом
  - `AnimationHelper` - анімації FAB
  - `SensorController` - shake detection, accelerometer
  - `SnowfallController` - святкові ефекти
  - `PopupMenuHelper` - меню пристроїв

### 1.3 Стабілізація BLE
- [ ] Завершити MTU negotiation в PartialFlashingService
- [ ] Додати retry з exponential backoff
- [ ] Уніфікувати error handling

---

## Фаза 2: Dependency Injection (1-2 тижні)

### 2.1 Впровадження Hilt
- [ ] Додати Hilt залежності в build.gradle
- [ ] Створити `@HiltAndroidApp` Application class
- [ ] Мігрувати `AppContext` singleton до Hilt module

### 2.2 Модулі
```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun provideBluetoothManager(@ApplicationContext context: Context): BluetoothManager

    @Provides @Singleton
    fun providePreferences(@ApplicationContext context: Context): SharedPreferences
}

@Module
@InstallIn(ViewModelComponent::class)
object ViewModelModule {
    @Provides
    fun provideDeviceRepository(...): DeviceRepository
}
```

### 2.3 Мігрувати сервіси
- [ ] FlashingService → @AndroidEntryPoint
- [ ] DfuService → @AndroidEntryPoint
- [ ] CheckService → @AndroidEntryPoint

---

## Фаза 3: Repository Pattern (2 тижні)

### 3.1 Data layer abstractions
```kotlin
interface DeviceRepository {
    fun getCurrentDevice(): Flow<Device?>
    fun saveDevice(device: Device)
    fun clearDevice()
}

interface FileRepository {
    suspend fun saveHexFile(uri: Uri): Result<File>
    fun getFileVersion(path: String): FileVersion
}

interface PreferencesRepository {
    val isPartialFlashingEnabled: Flow<Boolean>
    val currentEditor: Flow<EditorType>
    suspend fun setPartialFlashing(enabled: Boolean)
}
```

### 3.2 Implementations
- [ ] `DeviceRepositoryImpl` - SharedPreferences based
- [ ] `FileRepositoryImpl` - File system operations
- [ ] `PreferencesRepositoryImpl` - DataStore migration

---

## Фаза 4: State Management (2 тижні)

### 4.1 Уніфікований StateFlow
```kotlin
data class AppState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val flashingState: FlashingState = FlashingState.Idle,
    val currentDevice: Device? = null,
    val error: AppError? = null
)

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Scanning : ConnectionState()
    data class Connected(val device: Device) : ConnectionState()
}

sealed class FlashingState {
    object Idle : FlashingState()
    object Preparing : FlashingState()
    data class Flashing(val progress: Int) : FlashingState()
    object Completed : FlashingState()
    data class Error(val error: FlashingError) : FlashingState()
}
```

### 4.2 AppStateManager
```kotlin
@Singleton
class AppStateManager @Inject constructor() {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    fun updateConnectionState(state: ConnectionState)
    fun updateFlashingProgress(progress: Int)
    fun setError(error: AppError?)
}
```

### 4.3 Видалити ApplicationStateHandler
- [ ] Мігрувати всі виклики на AppStateManager
- [ ] Видалити статичні LiveData
- [ ] Оновити observers в Activities/Fragments

---

## Фаза 5: Clean Architecture (3-4 тижні)

### 5.1 Шари архітектури
```
┌─────────────────────────────────────────┐
│              Presentation               │
│  (Activities, Fragments, ViewModels)    │
├─────────────────────────────────────────┤
│                Domain                   │
│  (Use Cases, Entities, Repositories)    │
├─────────────────────────────────────────┤
│                 Data                    │
│  (Repository Impl, DataSources, DAOs)   │
├─────────────────────────────────────────┤
│              Framework                  │
│  (BLE Services, File System, Network)   │
└─────────────────────────────────────────┘
```

### 5.2 Use Cases
```kotlin
class FlashDeviceUseCase @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val fileRepository: FileRepository,
    private val flashingService: FlashingServiceManager
) {
    suspend operator fun invoke(filePath: String): Result<Unit>
}

class ScanForDevicesUseCase @Inject constructor(
    private val bleScanner: BleScanner,
    private val deviceRepository: DeviceRepository
) {
    operator fun invoke(): Flow<List<Device>>
}
```

### 5.3 Структура пакетів
```
cc.calliope.mini/
├── di/                          # Hilt modules
├── domain/
│   ├── model/                   # Domain entities
│   ├── repository/              # Repository interfaces
│   └── usecase/                 # Use cases
├── data/
│   ├── repository/              # Repository implementations
│   ├── local/                   # Local data sources
│   └── mapper/                  # Data mappers
├── presentation/
│   ├── main/                    # Main screen
│   ├── flashing/                # Flashing screen
│   ├── editors/                 # Editors screen
│   └── common/                  # Shared UI components
└── framework/
    ├── bluetooth/               # BLE implementation
    ├── file/                    # File operations
    └── notification/            # Notifications
```

---

## Фаза 6: Error Handling (1 тиждень)

### 6.1 Typed Errors
```kotlin
sealed class AppError {
    sealed class Bluetooth : AppError() {
        object NotEnabled : Bluetooth()
        object PermissionDenied : Bluetooth()
        data class ConnectionFailed(val code: Int) : Bluetooth()
        object DeviceNotFound : Bluetooth()
    }

    sealed class Flashing : AppError() {
        object FileNotFound : Flashing()
        object VersionMismatch : Flashing()
        data class TransferFailed(val reason: String) : Flashing()
    }

    sealed class File : AppError() {
        object InvalidFormat : File()
        object ReadError : File()
    }
}
```

### 6.2 Result wrapper
```kotlin
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val error: AppError) : Result<Nothing>()
}

inline fun <T> Result<T>.onSuccess(action: (T) -> Unit): Result<T>
inline fun <T> Result<T>.onError(action: (AppError) -> Unit): Result<T>
```

---

## Фаза 7: Testing (ongoing)

### 7.1 Unit Tests
- [ ] Repository tests з MockK
- [ ] ViewModel tests
- [ ] Use Case tests
- [ ] Mapper tests

### 7.2 Integration Tests
- [ ] BLE service tests (з mock BluetoothGatt)
- [ ] File operation tests

### 7.3 UI Tests
- [ ] Espresso tests для критичних flows
- [ ] Screenshot tests

---

## Фаза 8: UI Modernization (optional, 4+ тижні)

### 8.1 Jetpack Compose Migration
- [ ] Створити Compose theme
- [ ] Мігрувати прості screens (Settings, About)
- [ ] Поступово мігрувати складніші screens
- [ ] Зберегти WebView в XML (Compose interop)

### 8.2 Material 3
- [ ] Оновити тему до Material 3
- [ ] Dynamic colors support
- [ ] Adaptive layouts

---

## Пріоритети

| Фаза | Пріоритет | Вплив на стабільність |
|------|-----------|----------------------|
| 1. Критичні виправлення | 🔴 CRITICAL | Високий |
| 2. Dependency Injection | 🟠 HIGH | Середній |
| 3. Repository Pattern | 🟠 HIGH | Середній |
| 4. State Management | 🟡 MEDIUM | Високий |
| 5. Clean Architecture | 🟡 MEDIUM | Середній |
| 6. Error Handling | 🟡 MEDIUM | Високий |
| 7. Testing | 🟢 ONGOING | Високий |
| 8. UI Modernization | 🔵 LOW | Низький |

---

## Метрики успіху

- [ ] 0 ANR crashes
- [ ] 0 threading violations в StrictMode
- [ ] >60% code coverage
- [ ] Час підключення до пристрою <3s
- [ ] Час прошивки зменшено на 30%

---

## Технічний стек після рефакторингу

- **DI:** Hilt
- **Async:** Kotlin Coroutines + Flow
- **State:** StateFlow + ViewModel
- **Navigation:** Navigation Component (Safe Args)
- **Storage:** DataStore (preferences), Room (optional)
- **Testing:** JUnit 5, MockK, Turbine (Flow testing)
- **UI:** XML + Compose (hybrid)
- **BLE:** Nordic BLE library (unified)

---

---

## Фаза 9: Resource Naming Conventions (1-2 тижні)

### 9.1 Drawable Naming Convention

**Формат:** `{type}_{feature}_{description}_{size}`

| Тип | Префікс | Приклад |
|-----|---------|---------|
| Icons | `ic_` | `ic_nav_home_24.xml` |
| Buttons | `btn_` | `btn_primary_rounded.xml` |
| Backgrounds | `bg_` | `bg_dialog_rounded.xml` |
| Shapes | `shape_` | `shape_circle.xml` |
| Selectors | `selector_` | `selector_tab.xml` |
| Animations | `anim_` | `anim_battery_charging.xml` |
| Dividers | `divider_` | `divider_horizontal.xml` |
| LED states | `led_` | `led_01.xml` (залишити) |
| Patterns | `pattern_` | `pattern_01.xml` |

**Перейменування:**
```
# Поточна назва → Нова назва
delete_icon.xml → ic_action_delete_24.xml
document_ic.xml → ic_file_document_24.xml
circle_shape.xml → shape_circle.xml
custom_progressbar.xml → progress_bar_custom.xml
line_divider.xml → divider_line.xml
ratingbar.xml → rating_bar.xml
welcome.xml → bg_welcome.xml
dialog_background.xml → bg_dialog.xml
dialog_success_background.xml → bg_dialog_success.xml
bottom_sheet_background.xml → bg_bottom_sheet.xml
item_list_background.xml → bg_item_list.xml
tab_indicator_default.xml → selector_tab_indicator.xml
```

**Групування іконок:**
```
# Navigation icons
ic_nav_home_24.xml
ic_nav_editors_24.xml
ic_nav_help_24.xml
ic_nav_settings_24.xml

# Action icons
ic_action_edit_24.xml
ic_action_delete_24.xml
ic_action_share_24.xml
ic_action_fullscreen_enter_24.xml
ic_action_fullscreen_exit_24.xml

# Editor icons
ic_editor_makecode.xml
ic_editor_roberta.xml
ic_editor_blocks.xml
ic_editor_python.xml
ic_editor_custom.xml
ic_editor_cardboard_control.xml
ic_editor_cardboard_face.xml

# Status icons
ic_status_bluetooth_off.xml
ic_status_location_off.xml
ic_status_ble_connected.xml
ic_status_ble_disconnected.xml
```

### 9.2 Color Naming Convention

**Структура colors.xml:**
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- ==================== -->
    <!-- Brand Colors         -->
    <!-- ==================== -->
    <color name="brand_primary">#FF00C8C6</color>
    <color name="brand_primary_variant">#FF009896</color>
    <color name="brand_secondary">#FFFDC800</color>

    <!-- ==================== -->
    <!-- Semantic Colors      -->
    <!-- ==================== -->
    <color name="color_success">#FF00D265</color>
    <color name="color_warning">#FFFDC800</color>
    <color name="color_error">#FFB00020</color>
    <color name="color_info">#FF00C0FF</color>

    <!-- ==================== -->
    <!-- Surface Colors       -->
    <!-- ==================== -->
    <color name="color_surface">#FFFFFFFF</color>
    <color name="color_surface_variant">#FFF5F5F5</color>
    <color name="color_background">#FFFFFFFF</color>

    <!-- ==================== -->
    <!-- Text Colors          -->
    <!-- ==================== -->
    <color name="text_primary">#FF000000</color>
    <color name="text_secondary">#FF585858</color>
    <color name="text_disabled">#FFC1C1C1</color>
    <color name="text_on_primary">#FFFFFFFF</color>
    <color name="text_on_surface">#FF000000</color>

    <!-- ==================== -->
    <!-- Component Colors     -->
    <!-- ==================== -->
    <color name="button_primary">#FF00C8C6</color>
    <color name="button_secondary">#FFFDC800</color>
    <color name="button_danger">#FFB00020</color>
    <color name="button_disabled">#FFC1C1C1</color>

    <color name="nav_background">#FFFFFFFF</color>
    <color name="nav_item_active">#FF00C8C6</color>
    <color name="nav_item_inactive">#FF585858</color>

    <color name="divider">#FFE0E0E0</color>
    <color name="overlay">#B0000000</color>

    <!-- ==================== -->
    <!-- LED Colors (Board)   -->
    <!-- ==================== -->
    <color name="led_on">#FFFF0000</color>
    <color name="led_off">#FFEEEEEE</color>

    <!-- ==================== -->
    <!-- Editor Brand Colors  -->
    <!-- ==================== -->
    <color name="editor_makecode">#FF633B94</color>
    <color name="editor_roberta">#FFFF6200</color>
    <color name="editor_blocks">#FF4C97FF</color>
    <color name="editor_python">#FF3776AB</color>
</resources>
```

### 9.3 String Naming Convention

**Формат:** `{category}_{screen}_{element}`

| Категорія | Префікс | Приклад |
|-----------|---------|---------|
| Screen titles | `title_` | `title_home`, `title_settings` |
| Button labels | `btn_` | `btn_save`, `btn_cancel` |
| Dialog titles | `dialog_title_` | `dialog_title_delete` |
| Dialog messages | `dialog_msg_` | `dialog_msg_delete_confirm` |
| Error messages | `error_` | `error_connection_failed` |
| Success messages | `success_` | `success_flashing_completed` |
| Info messages | `info_` | `info_editor_makecode` |
| Hints/placeholders | `hint_` | `hint_enter_url` |
| Content descriptions | `cd_` | `cd_button_settings` |
| Menu items | `menu_` | `menu_share`, `menu_delete` |
| Preferences | `pref_title_`, `pref_summary_` | `pref_title_partial_flashing` |
| Notifications | `notif_` | `notif_flashing_progress` |
| Actions | `action_` | `action_retry`, `action_dismiss` |

**Групування в strings.xml:**
```xml
<resources>
    <!-- ==================== -->
    <!-- App Identity         -->
    <!-- ==================== -->
    <string name="app_name" translatable="false">Calliope mini</string>

    <!-- ==================== -->
    <!-- Navigation           -->
    <!-- ==================== -->
    <string name="title_home">Home</string>
    <string name="title_editors">Editors</string>
    <string name="title_help">Help</string>
    <string name="title_settings">Settings</string>

    <!-- ==================== -->
    <!-- Common Actions       -->
    <!-- ==================== -->
    <string name="btn_save">Save</string>
    <string name="btn_cancel">Cancel</string>
    <string name="btn_ok">Ok</string>
    <string name="btn_retry">Retry</string>
    <string name="btn_delete">Delete</string>
    <string name="btn_share">Share</string>
    <string name="btn_rename">Rename</string>

    <!-- ==================== -->
    <!-- Editors              -->
    <!-- ==================== -->
    <string name="editor_title_makecode" translatable="false">MakeCode</string>
    <string name="editor_title_roberta" translatable="false">Open Roberta Lab</string>
    <string name="editor_info_makecode">The MakeCode editor enables...</string>
    <string name="editor_info_roberta">In the Open Roberta Lab...</string>

    <!-- ==================== -->
    <!-- Flashing             -->
    <!-- ==================== -->
    <string name="flashing_status_connecting">Connecting...</string>
    <string name="flashing_status_uploading">Uploading...</string>
    <string name="flashing_status_completed">Flashing completed</string>
    <string name="flashing_error_timeout">Connection timeout</string>
    <string name="flashing_error_version_mismatch">Version mismatch</string>

    <!-- ==================== -->
    <!-- Dialogs              -->
    <!-- ==================== -->
    <string name="dialog_title_delete">Delete file</string>
    <string name="dialog_msg_delete_confirm">You will permanently delete \"%s\".</string>
    <string name="dialog_title_rename">Rename file</string>

    <!-- ==================== -->
    <!-- Errors               -->
    <!-- ==================== -->
    <string name="error_bluetooth_disabled">Bluetooth is disabled</string>
    <string name="error_no_internet">No internet available</string>
    <string name="error_device_not_connected">No Calliope mini connected</string>

    <!-- ==================== -->
    <!-- Permissions          -->
    <!-- ==================== -->
    <string name="permission_title_bluetooth">Bluetooth Permission Required</string>
    <string name="permission_msg_bluetooth">To communicate with your Calliope mini...</string>
    <string name="permission_title_location">Location Permission Required</string>
    <string name="permission_msg_location">From Android 6.0 onwards...</string>

    <!-- ==================== -->
    <!-- Settings             -->
    <!-- ==================== -->
    <string name="pref_title_partial_flashing">Partial flashing</string>
    <string name="pref_summary_partial_flashing">Enable or disable</string>
    <string name="pref_title_auto_flashing">Automatic flashing</string>
    <string name="pref_summary_auto_flashing">After downloading .hex file</string>
</resources>
```

### 9.4 Dimension Naming Convention

**Формат:** `{type}_{component}_{property}`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- ==================== -->
    <!-- Spacing              -->
    <!-- ==================== -->
    <dimen name="spacing_xs">4dp</dimen>
    <dimen name="spacing_sm">8dp</dimen>
    <dimen name="spacing_md">16dp</dimen>
    <dimen name="spacing_lg">24dp</dimen>
    <dimen name="spacing_xl">32dp</dimen>

    <!-- ==================== -->
    <!-- Corner Radius        -->
    <!-- ==================== -->
    <dimen name="radius_sm">4dp</dimen>
    <dimen name="radius_md">8dp</dimen>
    <dimen name="radius_lg">16dp</dimen>
    <dimen name="radius_full">999dp</dimen>

    <!-- ==================== -->
    <!-- Icon Sizes           -->
    <!-- ==================== -->
    <dimen name="icon_size_sm">16dp</dimen>
    <dimen name="icon_size_md">24dp</dimen>
    <dimen name="icon_size_lg">48dp</dimen>

    <!-- ==================== -->
    <!-- Component Sizes      -->
    <!-- ==================== -->
    <dimen name="button_height_md">48dp</dimen>
    <dimen name="toolbar_height">56dp</dimen>
    <dimen name="bottom_nav_height">56dp</dimen>
    <dimen name="fab_size">56dp</dimen>
</resources>
```

### 9.5 Міграційний скрипт

Створити Gradle task для автоматичного перейменування:

```kotlin
// В build.gradle.kts
tasks.register("renameResources") {
    doLast {
        val renames = mapOf(
            "delete_icon.xml" to "ic_action_delete_24.xml",
            "document_ic.xml" to "ic_file_document_24.xml",
            // ... інші
        )
        // Виконати перейменування та оновити посилання в коді
    }
}
```

### 9.6 Lint правила

Додати custom lint rules для перевірки naming conventions:

```kotlin
class ResourceNamingDetector : ResourceXmlDetector() {
    override fun appliesTo(folderType: ResourceFolderType): Boolean {
        return folderType == ResourceFolderType.DRAWABLE
    }

    override fun visitElement(context: XmlContext, element: Element) {
        val filename = context.file.name
        if (!filename.matches(Regex("^(ic|btn|bg|shape|selector|anim|divider)_.*"))) {
            context.report(
                ISSUE,
                element,
                context.getLocation(element),
                "Drawable filename should start with type prefix"
            )
        }
    }
}
```

---

---

## Фаза 10: Logging Infrastructure (1 тиждень)

### 10.1 Поточні проблеми

| Проблема | Опис |
|----------|------|
| **Hardcoded TAGs** | `"SA"`, `"HexActivity"`, `"BaseActivity"` замість константи |
| **Немає абстракції** | Прямий `android.util.Log` всюди |
| **Debug в production** | 150+ debug logs потрапляють в release |
| **Різні формати** | Немає єдиного стилю повідомлень |
| **Немає фільтрації** | Неможливо вимкнути логи по категоріях |

### 10.2 Рішення: Timber + Custom Trees

**Залежність:**
```kotlin
// build.gradle.kts
implementation("com.jakewharton.timber:timber:5.0.1")
```

**Ініціалізація в Application:**
```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }
    }
}

// Debug tree з автоматичним TAG
class DebugTree : Timber.DebugTree() {
    override fun createStackElementTag(element: StackTraceElement): String {
        return "(${element.fileName}:${element.lineNumber})"
    }
}

// Release tree - тільки warnings та errors
class ReleaseTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < Log.WARN) return // Ігнорувати verbose, debug, info

        // Опціонально: відправити в Crashlytics
        // FirebaseCrashlytics.getInstance().log(message)
        // t?.let { FirebaseCrashlytics.getInstance().recordException(it) }
    }
}
```

### 10.3 Міграція

**До:**
```java
private static final String TAG = "FlashingService";
Log.d(TAG, "Service started");
Log.e(TAG, "Error: " + message, exception);
```

**Після:**
```kotlin
Timber.d("Service started")
Timber.e(exception, "Error: %s", message)
```

### 10.4 Категоризовані логи (опціонально)

```kotlin
object AppLogger {
    private const val PREFIX_BLE = "[BLE]"
    private const val PREFIX_FLASH = "[FLASH]"
    private const val PREFIX_UI = "[UI]"
    private const val PREFIX_FILE = "[FILE]"

    fun ble(message: String, vararg args: Any?) =
        Timber.tag(PREFIX_BLE).d(message, *args)

    fun flash(message: String, vararg args: Any?) =
        Timber.tag(PREFIX_FLASH).d(message, *args)

    fun ui(message: String, vararg args: Any?) =
        Timber.tag(PREFIX_UI).d(message, *args)

    fun file(message: String, vararg args: Any?) =
        Timber.tag(PREFIX_FILE).d(message, *args)
}

// Використання
AppLogger.ble("Connected to device: %s", deviceAddress)
AppLogger.flash("Progress: %d%%", progress)
```

### 10.5 Правила логування

| Рівень | Коли використовувати | Приклад |
|--------|---------------------|---------|
| `Timber.v()` | Детальна інформація для debugging | Lifecycle callbacks, values |
| `Timber.d()` | Корисна для розробки | State changes, operations |
| `Timber.i()` | Важливі події | Connection established |
| `Timber.w()` | Потенційні проблеми | Fallback used, retry |
| `Timber.e()` | Помилки | Exceptions, failures |
| `Timber.wtf()` | Критичні помилки | Should never happen |

### 10.6 Міграційний скрипт

```bash
#!/bin/bash
# migrate_logs.sh

# Замінити Log.d на Timber.d
find app/src/main/java -name "*.java" -o -name "*.kt" | xargs sed -i '' \
    -e 's/Log\.d(TAG, /Timber.d(/g' \
    -e 's/Log\.e(TAG, /Timber.e(/g' \
    -e 's/Log\.w(TAG, /Timber.w(/g' \
    -e 's/Log\.i(TAG, /Timber.i(/g' \
    -e 's/Log\.v(TAG, /Timber.v(/g'

# Замінити hardcoded tags
find app/src/main/java -name "*.java" -o -name "*.kt" | xargs sed -i '' \
    -e 's/Log\.d("[^"]*", /Timber.d(/g' \
    -e 's/Log\.e("[^"]*", /Timber.e(/g' \
    -e 's/Log\.w("[^"]*", /Timber.w(/g'

# Видалити непотрібні TAG константи
# (вручну після перевірки)
```

### 10.7 Lint правила

```kotlin
// Заборонити прямий android.util.Log
class DirectLogUsageDetector : Detector(), SourceCodeScanner {
    override fun getApplicableMethodNames() = listOf("d", "e", "w", "i", "v", "wtf")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        if (context.evaluator.isMemberInClass(method, "android.util.Log")) {
            context.report(
                ISSUE,
                node,
                context.getLocation(node),
                "Use Timber instead of android.util.Log"
            )
        }
    }
}
```

### 10.8 Очищення debug логів

Файли з надмірним логуванням:
- [ ] `FlashingActivity.java` - 31 debug logs (видалити lifecycle logs)
- [ ] `WebFragment.java` - 15 logs
- [ ] `PartialFlashingService.kt` - 40+ logs (залишити важливі)

---

## Оновлені пріоритети

| Фаза | Пріоритет | Вплив | Час |
|------|-----------|-------|-----|
| 1. Критичні виправлення | 🔴 CRITICAL | Стабільність | 2-3 тижні |
| 2. Dependency Injection | 🟠 HIGH | Тестованість | 1-2 тижні |
| 3. Repository Pattern | 🟠 HIGH | Підтримуваність | 2 тижні |
| 4. State Management | 🟡 MEDIUM | Передбачуваність | 2 тижні |
| 5. Clean Architecture | 🟡 MEDIUM | Масштабованість | 3-4 тижні |
| 6. Error Handling | 🟡 MEDIUM | UX | 1 тиждень |
| 7. Testing | 🟢 ONGOING | Якість | постійно |
| 8. UI Modernization | 🔵 LOW | UX | 4+ тижні |
| 9. Resource Naming | 🟡 MEDIUM | Підтримуваність | 1-2 тижні |
| **10. Logging** | 🟠 HIGH | Debuggability | 1 тиждень |
| **11. Kotlin Migration** | 🟡 MEDIUM | Maintainability | паралельно |

---

## Фаза 11: Kotlin Migration Strategy

### 11.1 Структура пакетів

**Стратегія:** Новий пакет `app/` для рефакторованого коду на Kotlin.

```
cc.calliope.mini/
│
├── [LEGACY - старий код, поступово видаляється]
├── core/
│   ├── state/              → мігрує в app/domain/model/
│   ├── service/            → мігрує в app/data/service/
│   └── bluetooth/          → мігрує в app/data/bluetooth/
├── ui/
│   ├── activity/           → мігрує в app/presentation/
│   ├── fragment/           → мігрує в app/presentation/
│   └── views/              → мігрує в app/presentation/common/
├── utils/                  → мігрує в app/core/ або app/data/
│
└── app/                    [NEW - Kotlin + Clean Architecture]
    ├── di/                         # Hilt modules
    │   ├── AppModule.kt
    │   ├── DataModule.kt
    │   └── DomainModule.kt
    │
    ├── core/                       # Shared utilities
    │   ├── extensions/
    │   │   ├── ContextExt.kt
    │   │   ├── FlowExt.kt
    │   │   └── StringExt.kt
    │   ├── utils/
    │   │   ├── FileUtils.kt
    │   │   ├── BluetoothUtils.kt
    │   │   └── PermissionUtils.kt
    │   └── logging/
    │       ├── AppLogger.kt
    │       └── TimberTrees.kt
    │
    ├── data/                       # Data layer
    │   ├── model/                  # Data models (DTOs)
    │   │   ├── DeviceDto.kt
    │   │   └── HexFileDto.kt
    │   ├── repository/             # Repository implementations
    │   │   ├── DeviceRepositoryImpl.kt
    │   │   ├── FileRepositoryImpl.kt
    │   │   └── PreferencesRepositoryImpl.kt
    │   ├── local/                  # Local data sources
    │   │   ├── PreferencesDataSource.kt
    │   │   └── FileDataSource.kt
    │   ├── bluetooth/              # BLE layer
    │   │   ├── BleScanner.kt
    │   │   ├── BleConnection.kt
    │   │   └── GattCallback.kt
    │   └── service/                # Background services
    │       ├── FlashingService.kt
    │       ├── DfuService.kt
    │       └── PartialFlashingService.kt
    │
    ├── domain/                     # Domain layer
    │   ├── model/                  # Domain entities
    │   │   ├── Device.kt
    │   │   ├── HexFile.kt
    │   │   ├── FlashingState.kt
    │   │   └── AppError.kt
    │   ├── repository/             # Repository interfaces
    │   │   ├── DeviceRepository.kt
    │   │   ├── FileRepository.kt
    │   │   └── PreferencesRepository.kt
    │   └── usecase/                # Use cases
    │       ├── ConnectDeviceUseCase.kt
    │       ├── FlashDeviceUseCase.kt
    │       ├── ScanDevicesUseCase.kt
    │       └── LoadEditorsUseCase.kt
    │
    └── presentation/               # UI layer
        ├── main/
        │   ├── MainActivity.kt
        │   └── MainViewModel.kt
        ├── home/
        │   ├── HomeFragment.kt
        │   └── HomeViewModel.kt
        ├── editors/
        │   ├── EditorsFragment.kt
        │   ├── EditorsViewModel.kt
        │   └── EditorWebFragment.kt
        ├── flashing/
        │   ├── FlashingActivity.kt
        │   └── FlashingViewModel.kt
        ├── settings/
        │   ├── SettingsFragment.kt
        │   └── SettingsViewModel.kt
        ├── connection/
        │   ├── ConnectionDialog.kt
        │   └── ConnectionViewModel.kt
        └── common/
            ├── base/
            │   ├── BaseActivity.kt
            │   ├── BaseFragment.kt
            │   └── BaseViewModel.kt
            ├── views/
            │   ├── BoardView.kt
            │   ├── BoardProgressBar.kt
            │   └── PatternMatrixView.kt
            ├── adapter/
            │   └── BaseAdapter.kt
            └── extensions/
                └── ViewExt.kt
```

### 11.2 Порядок міграції

**Етап 1: Інфраструктура (1-2 дні)**
- [ ] Створити пакет `cc.calliope.mini.app`
- [ ] Налаштувати Hilt (`di/`)
- [ ] Створити `core/logging/` з Timber
- [ ] Створити базові extensions

**Етап 2: Domain layer (2-3 дні)**
- [ ] Domain models (`Device`, `HexFile`, `FlashingState`)
- [ ] Repository interfaces
- [ ] Use cases (заглушки)

**Етап 3: Data layer (1 тиждень)**
- [ ] Repository implementations
- [ ] Мігрувати `PreferencesDataSource`
- [ ] Мігрувати `FileDataSource`
- [ ] Мігрувати BLE services

**Етап 4: Presentation layer (2-3 тижні)**
- [ ] Base classes (`BaseActivity`, `BaseFragment`, `BaseViewModel`)
- [ ] Мігрувати `MainActivity`
- [ ] Мігрувати `HomeFragment`
- [ ] Мігрувати `EditorsFragment`
- [ ] Мігрувати `FlashingActivity`
- [ ] Мігрувати `SettingsFragment`
- [ ] Мігрувати custom views

**Етап 5: Cleanup (2-3 дні)**
- [ ] Видалити legacy код
- [ ] Перемістити `app/` → кореневий пакет
- [ ] Оновити imports
- [ ] Фінальне тестування

### 11.3 Правила міграції

1. **Один компонент за раз** - не мігрувати все одночасно
2. **Тести перед міграцією** - написати тести для legacy коду
3. **Тести після міграції** - переконатися що працює
4. **Не ламати існуючий код** - legacy і новий код співіснують
5. **Code review** - кожна міграція окремим PR

### 11.4 Naming conventions для Kotlin

```kotlin
// Classes - PascalCase
class FlashingViewModel
class DeviceRepository

// Functions - camelCase
fun connectToDevice()
suspend fun flashFirmware()

// Properties - camelCase
val deviceState: StateFlow<DeviceState>
private val _isConnected = MutableStateFlow(false)

// Constants - SCREAMING_SNAKE_CASE
companion object {
    private const val CONNECTION_TIMEOUT_MS = 10_000L
    private const val TAG = "FlashingService"
}

// Extensions
fun Context.showToast(message: String)
fun View.visible()
fun View.gone()

// Flow/StateFlow naming
private val _state = MutableStateFlow<State>(State.Idle)
val state: StateFlow<State> = _state.asStateFlow()
```

### 11.5 Kotlin idioms

```kotlin
// ❌ Java style
if (device != null) {
    device.connect()
}

// ✅ Kotlin style
device?.connect()

// ❌ Java style
val list = ArrayList<Device>()
for (item in items) {
    if (item.isValid) {
        list.add(item)
    }
}

// ✅ Kotlin style
val list = items.filter { it.isValid }

// ❌ Java style
fun getData(): Data? {
    return if (isValid) data else null
}

// ✅ Kotlin style
fun getData(): Data? = data.takeIf { isValid }

// ❌ Callbacks
interface Callback {
    fun onSuccess(data: Data)
    fun onError(error: Error)
}

// ✅ Coroutines + Result
suspend fun getData(): Result<Data>
```

---

*Документ створено: 2024*
*Остання редакція: автоматична*
