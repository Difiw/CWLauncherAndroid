# Проверка запуска Minecraft на телефоне

Нужен ARM64-телефон с включённой USB-отладкой. Используйте debug APK: только у него
`adb run-as ru.cw.launcher` может читать внутренние журналы приложения.

1. Установите сборку без удаления данных:

   `adb install -r app/build/outputs/apk/debug/app-debug.apk`

2. Очистите системный журнал, запустите CWLauncher и нажмите «Играть»:

   `adb logcat -c`

3. Дождитесь главного меню либо возврата в лаунчер. Сохраните системный журнал:

   `adb logcat -d -v threadtime > cw-logcat.txt`

4. Скопируйте журналы из Android sandbox:

   `adb exec-out run-as ru.cw.launcher cat files/CWLauncher/logs/launcher.log > cw-launcher.log`

   `adb exec-out run-as ru.cw.launcher cat files/CWLauncher/logs/jvm.log > cw-jvm.log`

   `adb exec-out run-as ru.cw.launcher cat files/CWLauncher/logs/fabric.log > cw-fabric.log`

   Имя отдельного `launch-<id>.log` выводится в `launcher.log`. Получите его той же
   командой `run-as ... cat files/CWLauncher/logs/<имя>`.

5. Для Minecraft 1.20.1 также сохраните игровой журнал:

   `adb exec-out run-as ru.cw.launcher cat files/CWLauncher/games/1.20.1/logs/latest.log > cw-latest.log`

Успех подтверждается не кодом `0`, а одновременно тремя фактами: в журнале есть
инициализация GLFW и передача Surface, процесс `ru.cw.launcher:game` остаётся жив,
на экране открыто главное меню Minecraft. Если `:game` исчез без callback, лаунчер
теперь показывает «код неизвестен»; причину следует искать в `cw-logcat.txt` по
`Fatal signal`, `DEBUG`, `linker`, `lowmemorykiller`, `AndroidRuntime` и имени процесса.

Файлы `accounts.json` и `secrets.json` передавать не нужно: они могут содержать
учётные данные. Аргументы запуска в новых диагностических строках выводятся только
сводно, без access token.
