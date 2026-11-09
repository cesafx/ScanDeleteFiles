# ScanDeleteFiles

Applicazione desktop in Java 21 e JavaFX per individuare file obsoleti, revisionarli tramite CSV e rimuovere in modo controllato quelli approvati.

## Stato

Il progetto è nella fase di specifica e decomposizione dei ticket. Nessuna funzione di cancellazione è ancora implementata.

## Obiettivo MVP

- scansione ricorsiva di cartelle locali, montate o UNC;
- filtro per data e formati configurabili;
- manifest CSV compatibile con Excel italiano;
- cancellazione da CSV verso cestino o, previa conferma, definitiva;
- report CSV e audit log italiano per ogni operazione;
- distribuzione Windows come ZIP portabile con runtime incluso.

La specifica completa è in [docs/SPEC.md](docs/SPEC.md).
