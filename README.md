# ScanDeleteFiles

Applicazione desktop in Java 21 e JavaFX per individuare file obsoleti, revisionarli tramite CSV e rimuovere in modo controllato quelli approvati.

## Stato

È disponibile l'applicazione Java 21/JavaFX con le schermate **Scansione** e
**Move/Delete**, i motori operativi e la produzione dei relativi CSV e log.

## Obiettivo MVP

- scansione ricorsiva di cartelle locali, montate o UNC;
- filtro per data e formati configurabili;
- file CSV compatibile con Excel italiano;
- elaborazione da CSV con spostamento in cartella, cestino o cancellazione definitiva;
- report CSV e audit log italiano per ogni operazione;
- distribuzione Windows come ZIP portabile con runtime incluso.

La specifica completa è in [docs/SPEC.md](docs/SPEC.md).

## Requisiti di sviluppo

- JDK 21;
- ambiente grafico per avviare JavaFX (su Linux è necessario un display X11 o
  Wayland).

Maven non deve essere installato separatamente: il repository include il Maven
Wrapper.

## Build e test

```bash
./mvnw -B clean verify
```

Il comando compila con Java 21 ed esegue gli smoke test del composition root e
della navigazione. Lo smoke test JavaFX viene eseguito quando è disponibile la
variabile `DISPLAY`; in un ambiente Linux headless può essere lanciato con:

```bash
xvfb-run -a ./mvnw -B test
```

## Avvio

```bash
./mvnw javafx:run
```

La schermata iniziale è **Scansione**. La barra laterale permette di passare a
**Move/Delete** e tornare indietro.

## Struttura

- `application`: interfacce dei servizi applicativi, comandi, avanzamento e
  annullamento;
- `platform`: seam per cestino e preferenze;
- `bootstrap`: composition root e dipendenze della piattaforma;
- `navigation`: stato di navigazione indipendente da JavaFX;
- `ui`: sola presentazione JavaFX, senza accesso al filesystem o logica di
  cancellazione.

## Distribuzione Windows portabile

Su Windows l'applicazione è distribuita come archivio ZIP contenente l'immagine
app-image prodotta con `jpackage` e il runtime Java 21, così non richiede una
Java preinstallata sul PC di destinazione:

```powershell
.\mvnw.cmd -B -Pwindows-package clean package
```

L'archivio risultante si trova in `target/ScanDeleteFiles-<versione>.zip`.
Una volta estratto, si avvia l'EXE incluso (`ScanDeleteFiles.exe`) senza
installazione.

## Limiti del cestino

Lo spostamento in una cartella scelta dall'utente è l'azione predefinita. Su Windows, nel
l'MVP, l'integrazione nativa con il cestino di sistema non è inclusa: se il
cestino non è disponibile il file viene saltato e segnalato nell'esito, senza
passare automaticamente alla cancellazione permanente.

## Cancellazione permanente

La cancellazione definitiva è opzionale e richiede una conferma esplicita prima
di avviarsi. Non esiste un fallback automatico dal cestino alla cancellazione
permanente: se il cestino non è disponibile, il file non viene eliminato.

## CSV e report

Il file CSV prodotto dalla scansione usa UTF-8 con BOM ed è delimitato da punto e
virgola, con sei colonne (`Nome file`, `Path cartella`, `Path completo`,
`Formato file`, `Data ultima modifica`, `Delete`) inizializzate a `Delete=Yes`.
Il CSV finale dell'operazione aggiunge le colonne `Esito` e `Dettaglio` e viene
salvato accanto al CSV di input. L'audit log (`.log`) viene prodotto in
italiano per ogni scansione e operazione Move/Delete. La scansione salva inoltre
un file `.meta.json` affiancato al CSV con la root selezionata: Move/Delete la
ripropone in un campo modificabile e ricrea nella destinazione l'alberatura
relativa, preservando le date di file e cartelle.

Ogni nuovo spostamento produce anche un registro `moving_*.undo.csv`. Se lo
spostamento è stato eseguito per errore, nella schermata **Move/Delete** si può
selezionare **Ripristina uno spostamento** e indicare quel registro. Il programma
riporta i file nei percorsi originali, ripristina le date di file e cartelle e
rimuove solo le cartelle create dallo spostamento rimaste vuote. Per sicurezza
non sovrascrive file ricreati nel percorso originale e non ripristina file che
risultano modificati dopo il Move. I report Move prodotti dalle versioni
precedenti non sono utilizzabili per il ripristino.

Il pacchetto Windows include sempre anche:

- `CartellaCollaudo_Sorgente`, con 1000 documenti segnaposto di oltre 30 formati,
  date distribuite fra il 2018 e il 2026 e un'alberatura ampia;
- `CartellaCollaudo_Destinazione`, pronta per le prove di Move;
- `LEGGIMI_COLLAUDO.txt`, con la procedura di prova.

I file sono rigenerati in modo deterministico a ogni build e servono esclusivamente
per collaudare scansione, filtri, Move e ripristino.
