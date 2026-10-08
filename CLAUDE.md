# FreeFCC - fork CEO

**Stan i następny krok:** `kolejka_sesji.md` - czytaj `⏭ START TUTAJ` PRZED jakąkolwiek pracą.
**Domknięcie sesji:** rytuał i higiena → globalny skill `kolejka-sesji`.

Fork publicznego repo `doesthings/FreeFCC` (AGPL-3.0): aplikacja Android (Kotlin, Compose) wysyłająca
ramki DUML do kontrolerów DJI z ekranem. Opis protokołu, profili i budowania → `README.md`.

## Remote

| Remote | Adres | Rola |
| --- | --- | --- |
| `origin` | `abo-t/FreeFCC` | fork CEO, publiczny |
| `upstream` | `doesthings/FreeFCC` | autor oryginału; tylko `fetch` |

Synchronizacja z oryginałem: `git fetch upstream` + `git merge upstream/main` na `main`.

## Git

- **Push i pull na `origin` - bez pytania** (decyzja CEO 2026-10-05). Tag, release i PR do `upstream`
  wyłącznie na słowo CEO.
- Repo publiczne: przed każdym pushem diff przechodzi skan na sekrety i lokalne ścieżki (`E:\`, `R:\`).
- Push na `main` odpala CI (`.github/workflows/build.yml`, Actions włączone w forku).
- Upstream skasował `.gitignore` (`9ec2b2b`); fork go przywraca (treść sprzed kasacji + `.playwright-cli/`).
  Przy merge z `upstream` plik ma zostać - bez niego klucze podpisu (`keystore.properties`, `*.jks`)
  wpadają do `git add`. `git status` przed `git add`, stage jawnymi ścieżkami.

## Fork a oryginał - czym się różnimy

- **Aktualizator i link „Source” idą za `BuildConfig.UPDATE_REPO`** (`app/build.gradle.kts`, domyślnie
  `abo-t/FreeFCC`, nadpisanie `-PupdateRepo=owner/name`). Repo bez wydań = komunikat „brak wydań”, nie błąd sieci.
- **Profil FCC do wyboru na ekranie Info** (`FccProfile.kt`): „Uniwersalny” = `fcc.json` (21 ramek z oryginału),
  „Lito X1” = `fcc_lito_x1.json` (2 ramki × 8 co 1 s, pomiar `lmdegreeds/dji_fcc_gpsoff`). Kolejności ramek
  Lito X1 pilnuje `FccProfileTest` - odwrócona daje moc bez 5,8 GHz.
- **Wersja ma jedno źródło: `versionName` w `app/build.gradle.kts`**; kod czyta `BuildConfig.VERSION_NAME`.
- **Teksty UI tylko w `res/values/strings.xml` (EN) i `res/values-pl/strings.xml` (PL)** - nowy tekst idzie do
  OBU plików, z tymi samymi parametrami; pilnuje tego `StringsParityTest`. Język wybiera użytkownik w aplikacji
  (`Lang.kt`), bo piloty DJI nie mają pełnych ustawień systemu.

## Budowanie

Java 17 + Android SDK 35. Test jednostkowy: `./gradlew testDebugUnitTest`; APK: `./gradlew assembleRelease`
(bez `keystore.properties` wychodzi niepodpisany). Na maszynie CEO nie ma JDK ani SDK - kompilację sprawdza CI:
gałąź na `origin`, potem `gh workflow run build --ref <gałąź>` i `gh run watch`.
