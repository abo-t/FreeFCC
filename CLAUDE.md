# FreeFCC - fork CEO

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

## Budowanie

Java 17 + Android SDK 35. Test jednostkowy: `./gradlew testDebugUnitTest`; APK: `./gradlew assembleRelease`
(bez `keystore.properties` wychodzi niepodpisany).
