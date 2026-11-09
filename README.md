# ScanDeleteFiles

Applicazione desktop in Java 21 e JavaFX per individuare file obsoleti, revisionarli tramite CSV e rimuovere in modo controllato quelli approvati.

## Stato

È disponibile la base eseguibile Java 21/JavaFX con le schermate **Scansione** e
**Cancellazione**. I comandi operativi sono intenzionalmente disabilitati: il
motore di scansione e cancellazione non è ancora implementato e la UI non
produce risultati simulati.

## Obiettivo MVP

- scansione ricorsiva di cartelle locali, montate o UNC;
- filtro per data e formati configurabili;
- manifest CSV compatibile con Excel italiano;
- cancellazione da CSV verso cestino o, previa conferma, definitiva;
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
**Cancellazione** e tornare indietro.

## Struttura

- `application`: interfacce dei servizi applicativi, comandi, avanzamento e
  annullamento;
- `platform`: seam per cestino e preferenze;
- `bootstrap`: composition root e dipendenze della piattaforma;
- `navigation`: stato di navigazione indipendente da JavaFX;
- `ui`: sola presentazione JavaFX, senza accesso al filesystem o logica di
  cancellazione.
