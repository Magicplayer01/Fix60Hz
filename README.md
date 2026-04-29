# Fix60Hz

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%2014-green?style=flat-square"/>
  <img src="https://img.shields.io/badge/Device-Realme%20GT%205G%20(RMX2202)-blue?style=flat-square"/>
  <img src="https://img.shields.io/badge/Framework-LSPosed-orange?style=flat-square"/>
  <img src="https://img.shields.io/badge/Version-1.1.0-red?style=flat-square"/>
  <img src="https://img.shields.io/badge/License-MIT-yellow?style=flat-square"/>
</p>

---

## 🇷🇺 Русский

### Описание

После замены оригинального дисплея на 60 Гц аналог система ColorOS продолжает
принудительно устанавливать 90 Гц через системные сервисы Android и Oplus.
Это приводит к зависанию экрана при загрузке и нестабильной работе устройства.

**Fix60Hz** — LSPosed-модуль, который перехватывает запросы на изменение частоты
обновления на нескольких уровнях и принудительно удерживает 60 Гц.

### Как это работает

Модуль работает в системном процессе `android (system_server)` и перехватывает
частоту обновления на 7+ уровнях одновременно:

| Уровень | Что перехватывается |
|---------|---------------------|
| 1 | `SurfaceControl.setDesiredDisplayModeSpecs` |
| 2 | `DisplayModeDirector.getDesiredDisplayModeSpecs` |
| 3 | `VoteSummary / summarizeVotes` |
| 4 | `VotesStorage.updateVote / updateGlobalVote` |
| 5 | `getMaxRefreshRateLocked` |
| 6 | `DisplayManagerService.setDisplayPropertiesInternal` |
| 7 | `Settings peak_refresh_rate / min_refresh_rate` |
| + | Watchdog каждые 5 секунд |

### Возможности

- 🔒 Принудительная фиксация 60 Гц
- 🛡 Защита от возврата 90 Гц системой
- 🔁 Watchdog-служба (каждые 5 секунд)
- 💓 Heartbeat — GUI знает, жив ли модуль
- 📊 Статистика перехватов / исправлений / утечек
- 📱 GUI-приложение для мониторинга состояния

### Требования

- **Устройство:** Realme GT 5G (RMX2202)
- **Прошивка:** ColorOS 14 / Android 14
- **Root:** Magisk или KernelSU
- **LSPosed:** установлен и активен
- **Scope:** `System Framework (android)`

### Установка

1. Скачай APK из раздела [Releases](https://github.com/Magicplayer01/Fix60Hz/releases)
2. Установи APK на устройство
3. Открой LSPosed → Модули → включи **Fix60Hz**
4. Убедись, что в Scope выбрано **System Framework (android)**
5. Перезагрузи устройство
6. Открой приложение Fix60Hz и убедись, что статус **«Модуль активен»**

### Проверка через ADB

```bash
# Посмотреть логи модуля
adb logcat -s Fix60Hz

# Посмотреть статистику
adb shell su -c "cat /data/system/fix60hz_stats"
Статистика (формат файла)
text

1|60|636|125|0|1714412345678
│  │  │   │  │  └── timestamp heartbeat (ms)
│  │  │   │  └───── утечки (leaks)
│  │  │   └──────── исправлений (rewrites)
│  │  └──────────── перехватов (intercepts)
│  └─────────────── целевая частота (Hz)
└────────────────── модуль активен (1=да)
Важно
⚠️ Модуль разработан под конкретное устройство и прошивку.
На других устройствах или версиях ColorOS работа не гарантируется.
Используйте на свой страх и риск.

Changelog
v1.1.0
Отдельный HandlerThread для watchdog
Защита от циклических ссылок при обходе полей
Улучшена числовая проверка containsHighRefresh
Добавлен heartbeat для GUI
Исправлено отображение статуса в приложении
Улучшено чтение stats-файла
v1.0.0
Первый рабочий релиз
7 уровней перехвата частоты
Watchdog каждые 5 секунд
GUI со статистикой
🇬🇧 English
Description
After replacing the original screen with a 60Hz analogue, ColorOS continues
to force 90Hz through Android system services and Oplus components.
This causes screen freezes on boot and unstable device behavior.

Fix60Hz is an LSPosed module that intercepts refresh rate requests
at multiple levels and forces them back to 60Hz.

How it works
The module runs inside the android (system_server) process and intercepts
the refresh rate at 7+ levels simultaneously:

Level	What is intercepted
1	SurfaceControl.setDesiredDisplayModeSpecs
2	DisplayModeDirector.getDesiredDisplayModeSpecs
3	VoteSummary / summarizeVotes
4	VotesStorage.updateVote / updateGlobalVote
5	getMaxRefreshRateLocked
6	DisplayManagerService.setDisplayPropertiesInternal
7	Settings peak_refresh_rate / min_refresh_rate
+	Watchdog every 5 seconds
Features
🔒 Force lock to 60Hz
🛡 Protection against system reverting to 90Hz
🔁 Watchdog service (every 5 seconds)
💓 Heartbeat — GUI knows if module is alive
📊 Statistics: intercepts / rewrites / leaks
📱 GUI app for real-time monitoring
Requirements
Device: Realme GT 5G (RMX2202)
OS: ColorOS 14 / Android 14
Root: Magisk or KernelSU
LSPosed: installed and active
Scope: System Framework (android)
Installation
Download APK from Releases
Install APK
Open LSPosed → Modules → enable Fix60Hz
Make sure System Framework (android) scope is selected
Reboot your device
Open Fix60Hz app and verify status shows "Module active"
Verify via ADB
Bash

# View module logs
adb logcat -s Fix60Hz

# View statistics
adb shell su -c "cat /data/system/fix60hz_stats"
Stats file format
text

1|60|636|125|0|1714412345678
│  │  │   │  │  └── heartbeat timestamp (ms)
│  │  │   │  └───── leaks
│  │  │   └──────── rewrites
│  │  └──────────── intercepts
│  └─────────────── target Hz
└────────────────── module active (1=yes)
Warning
⚠️ This module was developed for a specific device and firmware.
Functionality on other devices or ColorOS versions is not guaranteed.
Use at your own risk.

Changelog
v1.1.0
Separate HandlerThread for watchdog
Protection against circular references
Improved numeric containsHighRefresh check
Added heartbeat for GUI
Fixed module status display in app
Improved stats file reading
v1.0.0
First working release
7 levels of refresh rate interception
Watchdog every 5 seconds
GUI with statistics
👤 Author
Magicplayer01 — GitHub

⚖️ License
MIT License — see LICENSE for details.