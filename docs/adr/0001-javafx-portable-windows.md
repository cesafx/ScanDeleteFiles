# ADR 0001: Java 21, JavaFX e distribuzione Windows portabile

- Stato: accettata
- Data: 2026-11-09

## Contesto

ScanDeleteFiles deve essere un’applicazione desktop avviabile su un PC Windows senza richiedere una Java preinstallata, mantenendo il nucleo verificabile anche su Linux.

## Decisione

Usare Java 21 LTS e JavaFX. Produrre su Windows un `jpackage --type app-image`, includere il runtime e distribuire l’immagine come archivio ZIP portabile. Separare UI, servizi applicativi e adattatori di piattaforma.

## Conseguenze

- il pacchetto Windows deve essere costruito e collaudato su Windows;
- il cestino e le junction richiedono integrazioni e test specifici di piattaforma;
- i servizi applicativi restano testabili su Linux con filesystem temporanei e adattatori controllati;
- MSI ed installer EXE non fanno parte dell’MVP.
