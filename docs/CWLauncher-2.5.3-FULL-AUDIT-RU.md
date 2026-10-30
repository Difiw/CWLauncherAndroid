# CWLauncher 2.5.3 — полный аудит запуска Minecraft на Android

Дата аудита: 18 октября 2026 года
Целевая конфигурация: TECNO LI6, Android 15 / API 35, ARM64, Java 17.0.20, Minecraft 1.20.1, Fabric Loader 0.19.5.

## 1. Итог

Последний предоставленный архив `cwlauncher-diagnostics-1792328820226.zip` позволил установить конкретную причину повторного сбоя. Загрузка файлов Minecraft на этот раз не была местом падения: JVM создавалась, GL4ES и EGL инициализировались, аргументы передавались в JVM, после чего Java падала во время запуска Cacio/AWT-агента.

Фактическая ошибка:

```text
java.lang.UnsatisfiedLinkError: Can't load library: .../jre/17/lib/libawt_xawt.so
```

Стек ошибки проходил через `java.awt.Toolkit`, `CTCPreloadAgent` и параметр `-javaagent`. После исключения javaagent JVM попадала во внутренние assertion failures и завершала отдельный Android-процесс `ru.cw.launcher:game` аварийно.

Это не было ошибкой classpath Minecraft или Fabric. Необходимая Java-часть Cacio присутствовала, но нативные AWT-библиотеки находились только в каталоге native-библиотек APK. OpenJDK искал `libawt_xawt.so` в `${java.home}/lib`, где файла не было.

Исправленный APK:

```text
CWLauncher-2.5.3-FULL-AUDIT-FIX.apk
SHA-256: F0F9470AEB4125197FCB1A3C54F7150DC38F9B6A6A2D43BA1D52383C697634C2
package: ru.cw.launcher
versionName: 2.5.3
versionCode: 25300
```

Важно: статическая проверка, компиляция и сборка выполнены. Физическое Android-устройство к компьютеру не подключено, поэтому достижение главного меню Minecraft пока не подтверждено и не заявляется.

## 2. Что исправлено после последнего сбоя

### 2.1. Подготовка AWT/Cacio runtime

`BundledJava` теперь при подготовке Java 17 копирует в `${java.home}/lib` четыре связанные ARM64-библиотеки:

- `libawt_headless.so`;
- `libawt_xawt.so`;
- `libfontmanager.so`;
- `libfreetype.so`.

Добавление `libfreetype.so` принципиально: `libfontmanager.so` имеет прямую зависимость `DT_NEEDED` от `libfreetype.so`. Изменение `LD_LIBRARY_PATH` уже внутри процесса на Android 15 не является надёжным способом обеспечить поиск этой зависимости.

Внутренняя метка runtime изменена на `jre17-17.0.20d-cw3-awt`, а метка набора библиотек — на `6`. Поэтому уже распакованный runtime будет не скачан заново, а автоматически проверен и локально дополнен файлами из установленного APK.

### 2.2. Обязательная проверка до запуска JVM

Перед передачей управления JVM добавлен preflight. Он запрещает запуск, если отсутствует, слишком мал или не имеет ELF-заголовка любой обязательный файл:

- `lib/libawt.so`;
- `lib/libawt_headless.so`;
- `lib/libawt_xawt.so`;
- `lib/libfontmanager.so`;
- `lib/libfreetype.so`;
- `lib/libinstrument.so`;
- `lib/server/libjvm.so`.

Успешная проверка записывает:

```text
Java/Cacio preflight пройден: AWT, fontmanager, FreeType, instrument и JVM готовы
```

При ошибке JVM не запускается, а журнал называет конкретный отсутствующий или повреждённый файл.

### 2.3. Отсутствующий Java-класс native-моста

Аудит всех `FindClass` в нативных библиотеках обнаружил отсутствовавший класс `git.artdeell.mojo.Logger` и его вложенный интерфейс с исторически заданным именем `eventLogListener`. Класс добавлен с точными JNI-сигнатурами:

```text
appendToLog(String): void
begin(String): void
setLogListener(Logger.eventLogListener): void
eventLogListener.onEventLogged(String): void
```

Это устраняет потенциальный `ClassNotFoundException`/JNI failure при включении нативного перехвата журнала Mojo.

### 2.4. Правильная трактовка аварийного завершения

Ранее native callback с признаком сигнала и значением `0` приводил к ложной записи `jvmStatus=0` и сообщению «Игра остановилась по сигналу 0». Ноль в этом callback не является подтверждённым кодом завершения Java.

Теперь такая ситуация записывается как неизвестный код и фатальная native-ошибка. Отдельно сохраняются Android process-exit reason/status, поэтому различаются:

- обычный код завершения JVM, когда он действительно доступен;
- сигнал native-слоя;
- авария отдельного Android-процесса `:game`;
- неизвестный код, когда Android не предоставляет код Java-процесса.

## 3. Аудит классов, модулей и библиотек

### Java и Cacio

- В Cacio JAR присутствует `Premain-Class: CTCPreloadAgent`.
- Присутствуют `CTCToolkit`, `CTCGraphicsEnvironment`, `CTCScreen`, `CTCAndroidInput`.
- Зависимости Cacio по `jdeps`: `java.base`, `java.desktop`, `java.instrument`, `java.logging`, `jdk.unsupported`.
- В образе Java 17 через `jimage` подтверждены модуль `java.desktop`, `java.awt.Toolkit`, `java.instrument` и `sun.instrument.InstrumentationImpl`.
- JNI-сигнатуры `CTCScreen.getCurrentScreenRGB(): int[]` и `CTCAndroidInput.receiveData(int,int,int,int,int): void` совпадают с нативными вызовами.

### JNI-мосты

- `AWTBridge`, `ExitActivity` и `VMLoadException` присутствуют.
- Методы Java-классов `JREUtils`, `JavaRunner`, `MojoExec`, `GLFW` сверены с ожидаемыми JNI-дескрипторами.
- `DynamicLinkLoader` и `SharedLibraryUtil`, которые ожидает LWJGL native-код, присутствуют в bundled `lwjgl-3.3.1.jar`.
- Добавлен отсутствовавший `Logger$eventLogListener`.

### ARM64 native-библиотеки

В финальном APK найдено 25 файлов `.so`. У каждого проверены ELF magic и поле `e_machine=183`, то есть AArch64. Среди них:

- JVM/AWT: `libawt_headless.so`, `libawt_xawt.so`, `libfontmanager.so`, `libfreetype.so`, `libjli.so`;
- графика: `libgl4es_114.so`, `libng_gl4es.so`, `libglfw.so`;
- LWJGL: `liblwjgl.so`, `liblwjgl_opengl.so`, `liblwjgl_stb.so`;
- звук: `libopenal.so`;
- мост запуска: `libpojavexec.so`, `libpojavexec_awt.so`, `libmojoexec.so`.

Зависимости ELF были просмотрены. Системные Android-зависимости и библиотеки из JRE разрешаются своими каталогами; критическая внешняя зависимость `libfontmanager.so -> libfreetype.so` теперь явно помещается рядом с AWT-библиотеками.

### Графика

По фактическому журналу последней попытки GL4ES стартовал, GLES/EGL загружались до ошибки AWT. Поэтому последняя доказанная первопричина находилась не в EGL/GL4ES, а в размещении AWT native-библиотеки. GLFW и создание игрового окна ещё не были достигнуты из-за более раннего падения javaagent.

## 4. Загрузка и целостность файлов

Логика установки не должна переводить запуск в состояние подготовки при неуспешном обязательном скачивании. В текущей ветке сохранены проверки результата скачивания, размера/хэша там, где хэш доступен, повторные попытки и переключение URL. Ошибка обязательного файла останавливает запуск.

Новый AWT preflight является дополнительным последним барьером перед JVM: даже если runtime ранее был частично подготовлен, отсутствующая native-библиотека больше не маскируется состоянием `PREPARED`.

Для этой установки повторная загрузка Minecraft не требуется. Исправляемые AWT/FreeType-файлы входят в APK и копируются из него в существующий Java runtime. Переустанавливать игровой профиль или удалять папку Minecraft не нужно.

## 5. Диагностика и экспорт

В интерфейсе экрана «Логи» присутствует отдельная кнопка «Сохранить логи»; сохранение не привязано только к экрану настроек.

Экспорт собирает реально существующие файлы и не создаёт пустые подмены:

- `launcher.log`;
- журнал конкретной попытки `launch-<id>.log`;
- `jvm.log`;
- игровой `logs/latest.log`;
- `fabric.log`;
- `hs_err.log`, если JVM его создала;
- файлы из `crash-reports`;
- сведения о завершении отдельного Android-процесса.

Сводка должна перечислять найденные и отсутствующие журналы. Команда JVM записывается с маскированием секретов. Само системное свойство `-Dcw.jvm.log=...` не считается перенаправлением вывода; захват реализуется на фактическом пути запуска JVM/native callback.

## 6. Проверки финального APK на компьютере

Выполнено:

1. Компиляция изменённых Kotlin/Java-классов — успешно.
2. D8-преобразование в DEX — успешно.
3. Сборка APK — успешно.
4. `zipalign -c -v 4` — успешно.
5. `apksigner verify --verbose --print-certs` — успешно.
6. Подпись APK Signature Scheme v2 и v3 — подтверждена.
7. `aapt2 dump badging` — `versionName=2.5.3`, `versionCode=25300`, `targetSdk=35`, `minSdk=26`.
8. SHA-256 DEX до упаковки и `classes3.dex` из готового APK совпадают: `8704A5F459809E1C5D8341358AFF739484F697BBC46732DE18A1732152B3D387`.
9. В DEX готового APK присутствуют маркеры новой runtime-версии, FreeType preflight, `Logger$eventLogListener` и версии 2.5.3.
10. Все упакованные `.so` — AArch64 ELF.
11. `git diff --check` не обнаружил ошибок пробелов; показаны только предупреждения Git о будущем преобразовании LF/CRLF.

APK подписан стандартным Android debug-сертификатом. Если ранее установленная сборка подписана другим ключом, Android потребует удалить её перед установкой; удаление приложения может удалить внутренние данные приложения, поэтому перед этим следует экспортировать нужные файлы.

## 7. Что ещё нельзя считать проверенным

Без реального устройства нельзя честно подтвердить:

- фактическое разрешение всех dynamic linker-зависимостей именно на TECNO LI6 / Android 15;
- завершение `CTCPreloadAgent` после исправления;
- создание GLFW-окна;
- переход Minecraft к главному меню;
- поведение конкретных Fabric-модов пользователя.

Поэтому формулировка «точно всё будет работать» сейчас была бы недостоверной. Исправлена конкретная подтверждённая ошибка последнего запуска и добавлена защита от её повторения, но итог принимается только после запуска на устройстве.

## 8. План проверки на устройстве

1. Установить `CWLauncher-2.5.3-FULL-AUDIT-FIX.apk` поверх совместимо подписанной версии.
2. Не удалять Minecraft 1.20.1, libraries, assets или Fabric-профиль.
3. Открыть CWLauncher и экран «Логи».
4. Запустить существующий профиль Minecraft 1.20.1 + Fabric 0.19.5.
5. Убедиться, что есть запись `Java/Cacio preflight пройден`.
6. Проверить этапы по порядку: JVM создана; Cacio javaagent завершил premain; LWJGL/GLFW загружены; EGL/GL4ES инициализированы; появилось окно; показано главное меню.
7. При любом сбое сразу нажать «Сохранить логи» на экране логов и сохранить ZIP.
8. Не запускать игру повторно до сохранения первого архива, чтобы не затереть наиболее полезный журнал попытки.

Если окно не появится, следующий архив должен показать уже следующую точку отказа вместо прежнего `Can't load library: libawt_xawt.so`. Для native-crash дополнительно собрать широкий Logcat с ПК:

```powershell
adb logcat -c
# Воспроизвести сбой, затем:
adb logcat -d -v threadtime > cwlauncher-logcat.txt
adb shell dumpsys activity exit-info ru.cw.launcher > cwlauncher-exit-info.txt
```

В полном Logcat искать `ru.cw.launcher`, `ru.cw.launcher:game`, `FATAL EXCEPTION`, `AndroidRuntime`, `DEBUG`, `libc`, `SIGSEGV`, `SIGABRT`, `linker`, `tombstone`, но не применять узкий фильтр при сборе исходного файла.

## 9. Инструкция для независимой проверки другим ChatGPT

Передать этому чату исходный проект, данный отчёт, финальный APK и свежий диагностический ZIP после одной попытки запуска. Попросить независимо проверить:

1. Соответствует ли вывод о причине `UnsatisfiedLinkError libawt_xawt.so` фактическому стеку.
2. Копируются ли четыре AWT/шрифтовые библиотеки в `${java.home}/lib` до `JNI_CreateJavaVM`.
3. Блокирует ли preflight запуск при отсутствии каждого обязательного файла.
4. Совпадают ли JNI-дескрипторы Java-классов с `FindClass`/`GetStaticMethodID`/`GetMethodID` в `.so`.
5. Все ли ELF-файлы имеют архитектуру AArch64 и разрешимы ли их `DT_NEEDED`.
6. Есть ли необходимые Java-модули и классы Cacio/LWJGL.
7. Не трактуется ли signal callback со значением ноль как успешный exit code.
8. Содержит ли экспорт реальные `jvm.log`, `latest.log`, `fabric.log`, `hs_err.log` и crash reports либо честно объясняет их отсутствие.
9. Не выводятся ли токены и секреты в командной строке и ZIP.
10. Достигнуты ли на устройстве все контрольные точки вплоть до главного меню.

Критерий окончательной готовности: не только установка APK или строка `PREPARED`, а подтверждённое главное меню Minecraft либо диагностический архив, однозначно показывающий следующую реальную причину.
