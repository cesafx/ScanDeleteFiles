# Glossario

## Radice di scansione
La singola cartella locale, montata o UNC dalla quale parte una scansione ricorsiva.

## Manifest
Il CSV prodotto dalla scansione e modificabile dall’utente. È l’unico input della cancellazione e contiene esattamente le sei colonne contrattuali.

## File conforme
File visitato dalla scansione la cui estensione è selezionata e la cui data di ultima modifica è strettamente precedente alla soglia.

## Riga candidabile
Riga del manifest con `Delete=Yes`, senza distinzione tra maiuscole e minuscole. Prima dell’azione deve superare tutte le validazioni.

## Cestino
Capacità della piattaforma usata come modalità predefinita di rimozione. Se non disponibile, il file viene saltato e segnalato.

## Cancellazione definitiva
Rimozione irreversibile opzionale, avviabile solo dopo conferma esplicita.

## Report di cancellazione
CSV con le sei colonne del manifest più `Esito` e `Dettaglio`, salvato accanto al manifest.

## Audit log
File `.log` testuale in italiano che riepiloga stato, durata, conteggi e distribuzione per formato di una scansione o cancellazione.
