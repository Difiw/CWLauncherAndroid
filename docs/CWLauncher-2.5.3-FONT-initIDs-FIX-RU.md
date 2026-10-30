# CWLauncher 2.5.3 — отчёт по исправлению `java.awt.Font.initIDs()`

Дата: 18 октября 2026 года.

## Результат расследования

Диагностический архив `cwlauncher-diagnostics-1792331614048.zip` подтверждает, что исправление предыдущей ошибки с отсутствующим `libawt_xawt.so` сработало: JVM создаётся, GL4ES загружает GLES/EGL, Cacio запускает `premain`. Новая остановка происходит в статическом инициализаторе `java.awt.Font`:

```text
java.lang.UnsatisfiedLinkError: 'void java.awt.Font.initIDs()'
    at java.desktop/java.awt.Font.initIDs(Native Method)
    at java.desktop/java.awt.Font.<clinit>(Font.java:289)
```

После исключения из javaagent HotSpot закономерно завершает процесс сообщением `processing of -javaagent failed`.

## Точная причина

`java.awt.Font.initIDs()` должна предоставляться штатной библиотекой OpenJDK `libawt_headless.so`, соответствующей классам модуля `java.desktop` из того же JRE.

В `assets/jre17-arm64.zip` находится правильная ARM64-библиотека:

```text
lib/libawt_headless.so
size: 28520
SHA-256: C7C752714B4F859EA709476B7818FF5DC8A38855D825EF75B4E0E0B4C497975B
```

Анализ `llvm-readelf -Ws` и `llvm-nm -D --defined-only` подтвердил экспорт:

```text
Java_java_awt_Font_initIDs
Java_sun_awt_PlatformFont_initIDs
JNI_OnLoad
```

В предыдущем исправлении `BundledJava.prepareLibraries()` ошибочно заменял этот файл одноимённым Cacio linker shim из APK:

```text
size: 5120
SHA-256: 47E5248C98CB44138580C6313A6060DBB63E2C002969DCD5B029B2E01CF3B623
SONAME: libawt_headless.so
```

Shim не экспортирует `Java_java_awt_Font_initIDs`, `Java_sun_awt_PlatformFont_initIDs` или `JNI_OnLoad`. Из-за одинакового имени и SONAME он также мог быть выбран Android dynamic linker из native-каталога APK. Таким образом, проблема была не в ClassLoader и не в отсутствии метода в Java-классе: в runtime смешивались Java-классы и нативные AWT-компоненты разных комплектов.

## Исправление

Изменён `app/src/main/java/ru/cw/launcher/engine/BundledJava.kt`:

- штатные `libawt.so` и `libawt_headless.so` больше не перезаписываются;
- из APK в JRE копируются только отсутствующие `libawt_xawt.so`, `libfontmanager.so` и `libfreetype.so`;
- runtime stamp изменён на `jre17-17.0.20d-cw4-font-jni`;
- marker библиотек изменён на `7`;
- минимальный допустимый размер `libawt_headless.so` повышен с 4 КБ до 20 КБ;
- preflight сверяет точный SHA-256 штатного `libawt_headless.so`;
- при несовпадении JVM не запускается и журнал прямо сообщает, что provider `Font.initIDs` недоступен.

Изменён `app/build.gradle.kts`:

- несовместимый APK-side `libawt_headless.so` исключён из упаковки, чтобы исключить SONAME-конфликт в Android linker namespace.

Новая метка заставляет приложение локально заново распаковать встроенный JRE. Minecraft, Fabric, assets и библиотеки игры повторно скачиваться не будут.

## Пути загрузки

До входа в `JNI_CreateJavaVM` код задаёт `JAVA_HOME`, `LD_LIBRARY_PATH` и вызывает native `setLdLibraryPath`. Порядок native-поиска начинается с `${java.home}/lib`, затем включает каталог APK, `${java.home}/lib/server` и системные ARM64-пути. `java.library.path` также содержит игровые natives, APK natives и JRE.

После исключения shim из APK единственным `libawt_headless.so`, доступным запуску, является совместимый файл из `${java.home}/lib`. `libawt_xawt.so` имеет `DT_NEEDED: libawt_headless.so`; эта зависимость теперь разрешается на штатную библиотеку JRE.

## Сохранённые исправления

- AWT/FreeType/JVM preflight;
- `libawt_xawt.so`, `libfontmanager.so`, `libfreetype.so` в `${java.home}/lib`;
- захват stdout/stderr JVM;
- экспорт настоящих журналов;
- различение JVM exit code и Android process status/signal;
- отсутствие ложного успешного кода при signal callback со значением ноль;
- экранная кнопка «Сохранить логи».

## Проверка APK

Файл:

```text
CWLauncher-2.5.3-FONT-JNI-FIX.apk
SHA-256: A06C39BCBB79349AFA958116387E2BA061760F47B0BE4540588369C52DDCEA12
```

Выполнены проверки:

- изменённый Kotlin-класс успешно скомпилирован;
- D8 успешно создал DEX;
- APK успешно собран;
- `zipalign -c -v 4` — успешно;
- `apksigner verify` — успешно, подпись v2/v3;
- `aapt2 dump badging` — `versionName=2.5.3`, `versionCode=25300`, ARM64-приложение;
- APK содержит `libawt_xawt.so`, `libfontmanager.so`, `libfreetype.so` и встроенный `jre17-arm64.zip`;
- APK не содержит конфликтующий `lib/arm64-v8a/libawt_headless.so`;
- DEX содержит новую runtime-метку, ожидаемый SHA-256 и сообщение `Font.initIDs provider`;
- SHA-256 проверенного DEX: `20F0B137C45F1224BFC27DB3555DFA7420840C3533426F4D6A10D984DCA64920`;
- `git diff --check` не нашёл ошибок whitespace (только предупреждения Windows LF/CRLF).

Обычная Gradle-сборка на данном ПК по-прежнему блокируется локальной системной ошибкой Gradle/JDK `Unable to establish loopback connection` ещё до конфигурации проекта. Поэтому APK собран проверенным ручным конвейером Kotlin compiler → D8 → APKEditor → zipalign → apksigner. Это ограничение среды разработки, а не ошибка исходного кода Android-приложения.

## Фактически достигнутые этапы

По журналу устройства до исправления:

1. Подготовка runtime и предыдущий preflight — пройдены, но прежний preflight проверял только ELF/размер и пропустил несовместимый файл.
2. `JNI_CreateJavaVM` — достигнут.
3. GL4ES, GLES и EGL — загружены.
4. Cacio `premain` — начат.
5. `Font.initIDs` — сбой из-за подменённого provider.
6. LWJGL/GLFW, окно и главное меню — не достигнуты.

После исправления на компьютере доказаны наличие правильного JNI-символа, отсутствие конфликтующего shim в APK, компиляция и целостность сборки. Запуск на Android после исправления ещё не выполнен, поэтому успешный `premain`, GLFW, окно и главное меню не заявляются.

## План проверки на TECNO LI6

1. Установить новый APK поверх текущей совместимо подписанной версии.
2. Не удалять профиль Minecraft и не инициировать повторное скачивание.
3. Запустить Minecraft 1.20.1 + Fabric 0.19.5 один раз.
4. В логе проверить новую строку:

```text
Java/Cacio preflight пройден: AWT, Font.initIDs provider, fontmanager, FreeType, instrument и JVM готовы
```

5. Проверить отсутствие `UnsatisfiedLinkError: java.awt.Font.initIDs()`.
6. Затем проверить последовательно: окончание Cacio premain, загрузку LWJGL/GLFW, инициализацию renderer, появление окна, главное меню.
7. При новом сбое не выполнять второй запуск до экспорта: открыть «Логи» → «Сохранить логи» и передать ZIP.

## Задание для независимой перепроверки

Независимому проверяющему следует:

1. Извлечь оба исследованных `libawt_headless.so` и сравнить экспортируемые символы через `readelf`/`nm`.
2. Подтвердить, что классы `java.desktop` и штатные `libawt.so`/`libawt_headless.so` происходят из одного `jre17-arm64.zip`.
3. Убедиться, что `prepareLibraries()` не перезаписывает штатный headless-файл.
4. Проверить отсутствие APK-side файла с конфликтующим SONAME.
5. Проверить порядок `LD_LIBRARY_PATH` до `JNI_CreateJavaVM`.
6. Проверить, что preflight отклоняет старый hash `47E524...` и принимает только `C7C752...`.
7. По новому журналу устройства подтвердить переход за `Font.<clinit>` и установить следующую точку остановки, если она появится.

Критерий окончательной готовности остаётся прежним: главное меню Minecraft на устройстве либо новый диагностический архив, который показывает следующую конкретную причину. Одна только сборка APK не считается доказательством успешного запуска игры.
