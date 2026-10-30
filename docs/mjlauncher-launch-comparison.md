# CWLauncher и MJLauncher: совместимость запуска

Эталон: официальный `MojoLauncher/MojoLauncher`, ветка `v3_openjdk`, commit
`20e53f3e9ebfcbf8da4554bbc5fa5d0684f53b57` от 15 октября 2026 года.

Обозначения: **совпадает** — используется тот же контракт/исходник; **совместимо** —
реализация CW своя, но результат эквивалентен; **проверка на телефоне** — статической
проверки APK недостаточно.

| № | Этап | MJLauncher | CWLauncher после исправления | Статус |
|---:|---|---|---|---|
| 1 | ABI | Выбор runtime по архитектуре | Сборка arm64-v8a и явная проверка ABI | Совместимо для ARM64 |
| 2 | Java для Minecraft 1.20.1 | Java 17 runtime | Встроенная Java 17, проверяются `modules`, `libjvm`, `libjava`, `libnio` | Совместимо |
| 3 | Runtime paths | `JAVA_HOME`, `HOME`, `TMPDIR`, `LD_LIBRARY_PATH` | Те же категории путей выставляются до HotSpot | Совместимо |
| 4 | Version JSON | Mojang profile + inheritance | Mojang profile + inheritance | Совместимо |
| 5 | Client JAR | Размер/хеш/валидный архив | Размер/SHA-1/ZIP, повреждённый файл скачивается заново | Совместимо |
| 6 | Libraries | Maven coordinates и Mojang rules | Те же правила платформы и проверка артефактов | Совместимо |
| 7 | Assets | Индекс и content-addressed objects | Та же схема, размер и SHA-1 | Совместимо |
| 8 | Fabric | Fabric Meta, `KnotClient` | Fabric Meta, настоящий `KnotClient` | Совместимо |
| 9 | Classpath | Список путей → `-Djava.class.path=` | Android LWJGL стоит раньше desktop JAR, затем библиотеки и client | Совместимо |
| 10 | JVM args | Фильтрация desktop flags, нормализация module args | Та же нормализация; Android/JRE свойства формируются до JVM | Совместимо |
| 11 | App args | Отдельный массив Minecraft arguments | Отдельный массив после main class | Совпадает |
| 12 | Передача аргументов | `nativeTransferArguments` | Тот же JNI-вызов и `heap_str_array` | Совпадает |
| 13 | JNI ABI main class | Java `String` в `nativeLoadJVM` | Возвращён официальный `String`; прежний `long` удалён | Совпадает |
| 14 | Создание HotSpot | Официальный `jre_launcher.c` | Собран из официального файла без `CW-MJ stage` | Совпадает |
| 15 | Classloader hook | `native_library_hook.c` | Тот же исходник и порядок вызова | Совпадает |
| 16 | Process hook | `hookExec(vm_env)` | Возвращён официальный вызов; прежний skip удалён | Совпадает |
| 17 | Native executor | `mojoexec` submodule | Собран из того же submodule commit | Совпадает |
| 18 | Вызов main | `FindClass` → `main(String[])` | Тот же код; `CwMain` не используется | Совпадает |
| 19 | Native memory | Официальный lifecycle аргументов | Возвращён официальный lifecycle; локальный обход удалён | Совпадает |
| 20 | Game Activity | MJ XML Activity/platform layer | CW Compose Activity и Surface bridge | Проверка на телефоне |
| 21 | GLFW/LWJGL | Android GLFW и подмены LWJGL | Android GLFW bridge CW с совместимым LWJGL API | Не один исходник |
| 22 | Renderer | Выбираемый MJ renderer (`libGLMojo` и др.) | GL4ES (`libng_gl4es`) | Функционально отличается |
| 23 | Surface/EGL | MJ platform передаёт Android Surface | CW передаёт Surface и размер до JVM | Проверка на телефоне |
| 24 | Настройки | Профиль/renderer-specific fixes | 2 чанка, simulation 5, 10 FPS, fast graphics, без облаков/теней/AO/mipmap | Реализовано |
| 25 | Главное меню | Fabric/Minecraft после resource reload | Та же Java/Fabric цель | Проверка на телефоне |
| 26 | Мир/сервер | Стандартный Minecraft/Fabric | Minecraft/Fabric, quick-play для 1.20.1 | Проверка на телефоне |

## Удалённые расхождения

- удалён изменённый JNI-контракт с `mainClass` как адресом `long`;
- удалены строки `CW-MJ stage 1..6`;
- удалён пропуск `ProcessImpl` hook;
- удалён локальный обход освобождения tagged pointers;
- native-loader и `mojoexec` пересобраны из текущих официальных исходников MJLauncher;
- Fabric main вызывается напрямую, без `ru.cw.boot.CwMain`.

## Граница статической проверки

Пункты 20–26 зависят от Android Surface, GPU-драйвера, модов, памяти и сети.
Gradle build подтверждает структуру APK, но не появление кадра Minecraft. Финальная
проверка — запуск APK на ARM64-телефоне и лог Fabric до главного меню. UI CWLauncher
намеренно остаётся собственным.
