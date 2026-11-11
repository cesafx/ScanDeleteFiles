# ScanDeleteFiles — Specifica MVP

## Problem Statement

Gli utenti devono individuare, censire e successivamente eliminare grandi quantità di file obsoleti presenti in cartelle locali, condivisioni montate o percorsi UNC, senza affidarsi a operazioni manuali rischiose e difficili da verificare.

Il processo deve separare chiaramente la fase di scansione dalla fase di cancellazione:

- la scansione deve produrre un manifest CSV leggibile e modificabile con Excel in italiano;
- il manifest deve permettere all’utente di decidere quali file eliminare;
- la cancellazione deve applicare regole rigorose di validazione prima di intervenire sul filesystem;
- il cestino deve essere il comportamento predefinito;
- la cancellazione permanente deve essere esplicita e confermata;
- errori di accesso, righe non valide, file modificati e funzionalità di sistema non disponibili non devono causare cancellazioni non intenzionali;
- ogni operazione deve essere tracciata mediante un log di audit comprensibile in italiano.

L’applicazione deve essere distribuibile come programma desktop portabile per Windows, includendo il runtime necessario, senza rinunciare alla compatibilità del nucleo applicativo con Linux.

## Solution

ScanDeleteFiles sarà un’applicazione desktop JavaFX in italiano, basata su Java 21 LTS, organizzata in due schermate principali:

1. **Scansione**, per cercare ricorsivamente i file che corrispondono alle estensioni selezionate e la cui data di ultima modifica è strettamente precedente alla data scelta.
2. **Cancellazione**, per leggere un manifest CSV prodotto dalla scansione, validarne ogni riga e spostare nel cestino o eliminare permanentemente soltanto i file esplicitamente marcati con `Yes`.

La scansione accetterà una singola cartella radice per operazione. La cartella potrà essere locale, una condivisione già montata o un percorso UNC. Non verranno seguiti link simbolici o junction. I file nascosti saranno esclusi per impostazione predefinita, con la possibilità di includerli tramite un’apposita casella di controllo.

Il risultato della scansione sarà un CSV UTF-8 con BOM, delimitato da punto e virgola e compatibile con Excel in configurazione italiana. Il file conterrà esattamente sei colonne:

1. `Nome file`
2. `Path cartella`
3. `Path completo`
4. `Formato file`
5. `Data ultima modifica`
6. `Delete`

La colonna `Delete` sarà valorizzata inizialmente con `Yes`. L’utente potrà modificare il CSV prima di usarlo nella schermata di cancellazione.

Durante la cancellazione, `Path completo` sarà il riferimento autorevole per il file. L’applicazione controllerà la coerenza della riga, l’esistenza del file e la corrispondenza, a livello di giorno, tra la data di ultima modifica corrente e quella registrata nel CSV. Le righe incoerenti o non valide non produrranno cancellazioni.

Ogni operazione produrrà un file di audit `.log` in italiano. La cancellazione produrrà inoltre un CSV di report accanto al manifest di input, contenente le sei colonne originali più:

7. `Esito`
8. `Dettaglio`

Entrambe le operazioni mostreranno l’avanzamento e potranno essere annullate.

## User Stories

1. Come utente Windows, voglio avviare ScanDeleteFiles da un pacchetto portabile, così da non dover installare separatamente Java.
2. Come utente, voglio usare un’interfaccia desktop in italiano, così da comprendere chiaramente opzioni, avanzamento ed esiti.
3. Come utente, voglio distinguere chiaramente la schermata di scansione da quella di cancellazione, così da non confondere la preparazione del manifest con l’eliminazione dei file.
4. Come utente, voglio selezionare una cartella locale come origine della scansione, così da analizzare i file presenti sul mio computer.
5. Come utente, voglio selezionare una condivisione montata come origine della scansione, così da analizzare file presenti su storage di rete.
6. Come utente Windows, voglio poter indicare un percorso UNC come origine della scansione, così da lavorare direttamente con una condivisione di rete supportata dal sistema.
7. Come utente, voglio sottoporre a scansione una sola cartella radice per operazione, così da avere un ambito di lavoro chiaramente definito.
8. Come utente, voglio che la cartella scelta venga esplorata ricorsivamente, così da includere anche i file nelle sottocartelle.
9. Come utente, voglio che la scansione non segua link simbolici, così da evitare di uscire involontariamente dall’albero selezionato o visitare più volte gli stessi contenuti.
10. Come utente Windows, voglio che la scansione non attraversi junction, così da evitare percorsi inattesi, duplicazioni e cicli.
11. Come utente, voglio che i file nascosti siano esclusi per impostazione predefinita, così da non includere accidentalmente contenuti non normalmente visibili.
12. Come utente, voglio poter includere esplicitamente i file nascosti, così da estendere la scansione quando necessario.
13. Come utente, voglio che un errore di accesso a un file o a una cartella non interrompa l’intera scansione, così da ottenere comunque i risultati disponibili.
14. Come utente, voglio trovare nel log gli errori di accesso incontrati, così da sapere quali parti dell’albero non sono state analizzate correttamente.
15. Come utente, voglio scegliere una data limite tramite un controllo calendario, così da evitare l’inserimento manuale ambiguo della data.
16. Come utente, voglio includere soltanto i file modificati strettamente prima delle ore 00:00 della data selezionata, così da applicare una soglia temporale precisa.
17. Come utente, voglio che i file modificati durante la data selezionata siano esclusi, così da rispettare la regola “strettamente precedente”.
18. Come utente, voglio scegliere i formati da cercare da una checklist, così da limitare la scansione alle tipologie di file pertinenti.
19. Come utente, voglio che il confronto delle estensioni non distingua tra maiuscole e minuscole, così da trattare nello stesso modo, per esempio, `.PDF` e `.pdf`.
20. Come utente, voglio che le selezioni della checklist dei formati siano persistenti, così da poter riutilizzare la stessa configurazione nelle sessioni successive.
21. Come utente al primo utilizzo, voglio disporre di una configurazione predefinita dei formati supportati, così da poter avviare una scansione senza configurazione aggiuntiva.
22. Come utente, voglio includere i formati di testo `TXT` e `LOG`, così da censire documenti testuali e file di log.
23. Come utente, voglio includere il formato `PDF`, così da censire documenti PDF.
24. Come utente, voglio includere i formati immagine `JPG`, `JPEG`, `PNG`, `GIF`, `BMP`, `TIF`, `TIFF`, `WEBP` e `HEIC`, così da censire le immagini supportate.
25. Come utente, voglio includere i formati documento `DOC`, `DOCX`, `DOCM`, `DOT`, `DOTX`, `DOTM` e `RTF`, così da censire documenti e modelli compatibili.
26. Come utente, voglio includere i formati foglio di calcolo `XLS`, `XLSX`, `XLSM`, `XLSB`, `XLT`, `XLTX`, `XLTM` e `CSV`, così da censire fogli di calcolo e dati tabellari.
27. Come utente, voglio includere i formati presentazione `PPT`, `PPTX`, `PPTM`, `POT`, `POTX`, `POTM`, `PPS`, `PPSX` e `PPSM`, così da censire presentazioni, modelli e slideshow.
28. Come utente, voglio includere i formati OpenDocument `ODT`, `ODS` e `ODP`, così da censire documenti, fogli di calcolo e presentazioni OpenDocument.
29. Come utente, voglio includere gli archivi `ZIP`, `RAR` e `7Z`, così da censire gli archivi compressi supportati.
30. Come utente, voglio scegliere la cartella e il nome del manifest di scansione, così da controllare dove viene salvato il risultato.
31. Come utente, voglio che ogni manifest abbia un nome univoco e non sovrascriva mai un manifest o il relativo log già esistente, così da conservare scansioni diverse.
32. Come utente, voglio che il manifest sia codificato in UTF-8 con BOM, così da conservare correttamente i caratteri italiani e facilitarne l’apertura con Excel.
33. Come utente di Excel in configurazione italiana, voglio che il manifest usi il punto e virgola come delimitatore, così da visualizzare correttamente le colonne.
34. Come utente, voglio che il manifest contenga esattamente le sei colonne previste, così da avere un formato stabile e comprensibile.
35. Come utente, voglio vedere il nome del file in `Nome file`, così da identificare rapidamente ogni elemento.
36. Come utente, voglio vedere la cartella contenitrice in `Path cartella`, così da comprendere dove si trova il file.
37. Come utente, voglio vedere il percorso completo in `Path completo`, così da identificare senza ambiguità il file.
38. Come utente, voglio vedere il formato in `Formato file`, così da riconoscere la tipologia del file.
39. Come utente, voglio vedere la data di ultima modifica in `Data ultima modifica`, così da verificare l’anzianità del file.
40. Come utente, voglio che la data nel CSV usi il formato `yyyy/MM/dd` senza ora, così da avere una rappresentazione stabile e leggibile.
41. Come utente, voglio che `Delete` sia inizializzato con `Yes`, così da poter deselezionare nel CSV soltanto i file che desidero conservare.
42. Come utente, voglio poter modificare il manifest con un programma esterno prima della cancellazione, così da revisionare e correggere le decisioni di eliminazione.
43. Come utente, voglio che il manifest non contenga colonne tecniche aggiuntive, così da mantenere il file semplice e conforme al contratto previsto.
44. Come utente, voglio che non venga creato un sidecar tecnico necessario alla cancellazione, così da poter usare il solo CSV scelto.
45. Come utente, voglio vedere l’avanzamento della scansione, così da sapere che l’operazione è ancora attiva.
46. Come utente, voglio poter annullare una scansione in corso, così da interrompere un’operazione non più necessaria.
47. Come utente, voglio che una scansione annullata non lasci un manifest parziale, così da non usare accidentalmente risultati incompleti.
48. Come utente, voglio ricevere un log di audit anche quando annullo la scansione, così da conservare traccia dell’operazione interrotta.
49. Come utente, voglio ricevere un log diagnostico anche quando la scansione fallisce, contenente il tipo dell’eccezione, il messaggio e lo stack trace; se la destinazione richiesta non è scrivibile, il log deve essere salvato nella radice di scansione o, come ultima alternativa, nella cartella utente, e il percorso deve essere mostrato nell’esito.
50. Come utente, voglio che il log di scansione indichi lo stato finale, così da distinguere un completamento da un annullamento o un errore.
51. Come utente, voglio che il log di scansione indichi inizio, fine e durata, così da conoscere la finestra temporale dell’operazione.
52. Come utente, voglio che il log di scansione riporti il numero di elementi visitati previsto dal conteggio di scansione, così da valutare l’ampiezza del lavoro svolto.
53. Come utente, voglio che il log di scansione riporti il numero di file corrispondenti ai criteri, così da conoscere la dimensione del manifest.
54. Come utente, voglio vedere nel log le percentuali per formato calcolate sui file corrispondenti, così da comprendere la composizione dei risultati.
55. Come utente, voglio selezionare un manifest CSV nella schermata di cancellazione, così da elaborare le decisioni preparate durante la revisione.
56. Come utente, voglio che la cancellazione legga esattamente il CSV selezionato, così da non dipendere da dati nascosti o da file ausiliari.
57. Come utente, voglio che `Path completo` sia il riferimento autorevole per la cancellazione, così da identificare univocamente il file da trattare.
58. Come utente, voglio che l’applicazione verifichi la coerenza tra `Path completo`, `Nome file`, `Path cartella` e `Formato file`, così da rilevare righe alterate o incoerenti.
59. Come utente, voglio che una riga incoerente venga ignorata e registrata come errore, così da evitare una cancellazione basata su dati contraddittori.
60. Come utente, voglio che il valore `Yes` in `Delete` sia riconosciuto senza distinzione tra maiuscole e minuscole, così da non dipendere dalla capitalizzazione inserita nel CSV.
61. Come utente, voglio che `No` sia riconosciuto senza distinzione tra maiuscole e minuscole, così da poter escludere chiaramente un file.
62. Come utente, voglio che un valore vuoto in `Delete` impedisca la cancellazione, così da trattare l’assenza di una scelta come conservazione.
63. Come utente, voglio che valori diversi da `Yes`, `No` o vuoto siano registrati come errori, così da rendere visibili le decisioni ambigue.
64. Come utente, voglio che una riga con `No` venga riportata come saltata, così da verificare che la mia decisione sia stata rispettata.
65. Come utente, voglio che una riga con `Delete` vuoto venga riportata come saltata, così da verificare che il file non sia stato eliminato.
66. Come utente, voglio che l’applicazione verifichi l’esistenza del file prima di eliminarlo, così da non dichiarare riuscita un’operazione su un file assente.
67. Come utente, voglio che un file non più esistente non venga considerato eliminato e sia riportato nel report, così da conoscere la situazione reale.
68. Come utente, voglio che la data di ultima modifica corrente venga confrontata con quella del CSV a livello di giorno, così da rilevare file modificati in una data diversa dopo la scansione.
69. Come utente, voglio che un file la cui data non corrisponde al CSV venga saltato e segnalato, così da evitare di eliminare un file cambiato successivamente.
70. Come utente, voglio che la destinazione predefinita della cancellazione sia il cestino della piattaforma, così da poter recuperare i file quando il sistema lo consente.
71. Come utente, voglio che l’uso del cestino non richieda una schermata riepilogativa o una conferma aggiuntiva, così da eseguire direttamente l’azione già indicata nel manifest.
72. Come utente, voglio poter scegliere la cancellazione permanente come modalità opzionale, così da eliminare i file senza passare dal cestino quando necessario.
73. Come utente, voglio che la cancellazione permanente richieda una conferma esplicita, così da ridurre il rischio di perdita irreversibile.
74. Come utente, voglio che l’indisponibilità del cestino impedisca l’eliminazione del file interessato, così da non subire un passaggio automatico alla cancellazione permanente.
75. Come utente, voglio che l’indisponibilità del cestino venga registrata come esito, così da sapere perché il file non è stato trattato.
76. Come utente, voglio che l’applicazione non effettui mai automaticamente una cancellazione permanente quando il cestino non è disponibile, così da mantenere esplicito ogni intervento irreversibile.
77. Come utente, voglio vedere l’avanzamento della cancellazione, così da sapere quante righe sono state elaborate.
78. Come utente, voglio poter annullare una cancellazione in corso, così da interrompere l’elaborazione delle righe rimanenti.
79. Come utente, voglio che l’annullamento della cancellazione non tenti di ripristinare automaticamente i file già trattati, ma produca il resoconto delle righe elaborate, così da conoscere lo stato effettivo raggiunto.
80. Come utente, voglio ricevere un report CSV anche quando annullo la cancellazione, così da conservare gli esiti delle righe già elaborate.
81. Come utente, voglio che il report di cancellazione sia salvato accanto al manifest di input, così da ritrovarlo facilmente.
82. Come utente, voglio che il report contenga una riga per ogni riga di input prevista dal contratto dell’operazione, così da poter riconciliare manifest ed esiti.
83. Come utente, voglio che il report conservi le sei colonne originali, così da poter confrontare ogni esito con i dati ricevuti.
84. Come utente, voglio che il report aggiunga la colonna `Esito`, così da conoscere il risultato sintetico di ogni riga.
85. Come utente, voglio che il report aggiunga la colonna `Dettaglio`, così da comprendere il motivo di errori, salti o risultati particolari.
86. Come utente, voglio ricevere un log di audit per ogni cancellazione, così da disporre di una traccia leggibile dell’operazione.
87. Come utente, voglio ricevere il log di cancellazione anche in caso di annullamento o errori, così da non perdere le informazioni diagnostiche.
88. Come utente, voglio che il log di cancellazione indichi lo stato finale, così da distinguere completamento, annullamento ed errore.
89. Come utente, voglio che il log di cancellazione indichi inizio, fine e durata, così da conoscere la finestra temporale dell’operazione.
90. Come utente, voglio che il log riporti il numero di righe controllate, così da sapere fino a che punto è arrivata l’elaborazione.
91. Come utente, voglio che il log riporti il numero di righe con `Delete=Yes`, così da conoscere quante richieste di eliminazione sono state rilevate.
92. Come utente, voglio che il log distingua i file spostati nel cestino da quelli eliminati permanentemente, così da comprendere la natura delle azioni eseguite.
93. Come utente, voglio che il log riporti separatamente elementi saltati ed errori, così da distinguere decisioni deliberate da anomalie.
94. Come utente, voglio vedere nel log le percentuali per formato calcolate sulle righe valide per il conteggio `Delete=Yes`, così da comprendere la composizione delle richieste di eliminazione.
95. Come responsabile operativo, voglio usare manifest, report e log come evidenze dell’attività, così da poter ricostruire criteri, decisioni ed esiti.
96. Come sviluppatore, voglio mantenere il nucleo di scansione e cancellazione compatibile con Linux, così da poterlo eseguire e verificare anche fuori da Windows.
97. Come responsabile della distribuzione, voglio produrre per Windows un’applicazione `jpackage` in formato app-image, così da distribuire programma e runtime come unità portabile.
98. Come responsabile della distribuzione, voglio confezionare l’app-image Windows in un archivio ZIP, così da poterla trasferire ed estrarre senza un installer.
99. Come proprietario del progetto, voglio che il codice sorgente risieda in un repository GitHub privato, così da limitarne l’accesso ai collaboratori autorizzati.
100. Come sviluppatore, voglio verificare il comportamento usando file e cartelle temporanei, così da non coinvolgere dati reali degli utenti.
101. Come utente, voglio spostare i file marcati per l'eliminazione in una cartella di destinazione scelta da me, così da poterli archiviare invece di eliminarli.
102. Come utente, voglio scegliere la cartella di destinazione nella schermata di cancellazione, così da controllare dove verranno spostati i file.
103. Come utente, voglio che un file già presente nella cartella di destinazione non venga sovrascritto: verrà aggiunto un suffisso numerico al nome per preservare entrambi i file.
104. Come utente, voglio che il log di scansione riporti le esclusioni per formato, con conteggio e percentuale sul totale visitato dei file scartati perché non inclusi nei filtri selezionati, così da capire se occorre aggiungere la gestione di un nuovo tipo documento.
105. Come utente, voglio che durante la scansione di grandi quantità di documenti l'interfaccia indichi in modo continuo se l'operazione è attiva (esaminando file, scrivendo il manifesto, scrivendo il log di audit), così da sapere che l'applicazione non si è bloccata.
106. Come utente, voglio che il report CSV e il log prodotti dall'elaborazione del manifest siano denominati in base all'azione effettuata (`trash`, `deleting` o `moving`), così da riconoscere immediatamente il tipo di operazione.
107. Come utente finale, voglio che l'interfaccia chiami gli output “file CSV” e non “manifest”, così da usare termini comprensibili e orientati all'uso.
108. Come utente, voglio trovare nel log di scansione la cartella analizzata e la data soglia esclusiva, così da ricostruire i parametri usati.
109. Come utente, voglio che il calendario della data soglia si apra sulla data valida inserita manualmente, così da poterla correggere o confermare senza tornare alla data odierna.
110. Come utente, voglio che la seconda schermata si chiami **Move/Delete**, che lo spostamento sia predefinito e che la destinazione sia richiesta solo per tale modalità.
111. Come utente, voglio selezionare una cartella, non un file, come destinazione dello spostamento.
112. Come utente, voglio che lo spostamento dei file non alteri la data di ultima modifica delle cartelle sorgenti, così da preservarne i metadati.
113. Come utente, voglio che lo spostamento ricrei nella destinazione l'alberatura relativa alla root della scansione, così da conservare l'organizzazione originale.
114. Come utente, voglio che la root salvata dalla scansione sia riproposta nella schermata Move/Delete e resti modificabile, così da poter usare CSV prodotti in momenti diversi o correggere disallineamenti.
115. Come utente, voglio che file spostati e cartelle ricreate conservino le date di ultima modifica originali.
116. Come utente, voglio che ogni nuovo Move produca un registro di ripristino dedicato, così da poter annullare uno spostamento eseguito per errore.
117. Come utente, voglio ripristinare i file nei percorsi originali mantenendo le date originali di file e cartelle.
118. Come utente, voglio che il ripristino non sovrascriva file presenti nel percorso originale e rifiuti file modificati dopo il Move.
119. Come utente, voglio poter annullare e riprendere un ripristino senza elaborare nuovamente i file già riportati alla posizione originale.
120. Come utente, voglio che il ripristino elimini soltanto le cartelle create dal Move che sono ancora vuote, senza cancellare contenuti successivi.

## Implementation Decisions

### Piattaforma e distribuzione

- Il progetto userà **Java 21 LTS**.
- L’interfaccia desktop sarà realizzata con **JavaFX**.
- Tutti i testi destinati all’utente saranno in italiano.
- Windows sarà la piattaforma primaria di distribuzione.
- Il nucleo applicativo resterà compatibile con Linux.
- La distribuzione Windows sarà un **app-image prodotto con `jpackage`**, comprensivo di runtime Java.
- L’app-image sarà distribuito come archivio ZIP portabile.
- Il codice sorgente sarà mantenuto in un repository GitHub privato.

### Struttura funzionale

- L’interfaccia avrà due schermate principali: **Scansione** e **Move/Delete**.
- Le operazioni lunghe non dovranno bloccare l’interfaccia.
- Entrambe le operazioni esporranno avanzamento e annullamento.
- La scansione e la cancellazione saranno servizi applicativi distinti dall’interfaccia JavaFX, così da poter verificare il comportamento senza pilotare necessariamente la UI.
- L’accesso al filesystem, l’uso del cestino, l’orologio e la persistenza della configurazione costituiscono seam tecnici ragionevoli, perché dipendono dall’ambiente o introducono non determinismo.
- Le regole di selezione, validazione e conteggio resteranno indipendenti dalla rappresentazione grafica.

### Scansione

- Ogni scansione accetterà una sola radice.
- La radice potrà essere una cartella locale, una condivisione montata o un percorso UNC supportato dal filesystem della piattaforma.
- La visita sarà ricorsiva.
- Non verranno seguiti link simbolici o junction.
- I file nascosti saranno esclusi per impostazione predefinita.
- Una casella di controllo permetterà di includere i file nascosti.
- Gli errori di accesso saranno raccolti e riportati senza interrompere automaticamente l’intera scansione.
- La data limite verrà scelta con un `DatePicker`.
- Se l'utente inserisce manualmente una data valida, l'apertura del calendario userà tale data come valore corrente; saranno accettati almeno `dd/MM/yyyy`, `yyyy/MM/dd` e `yyyy-MM-dd`.
- La soglia temporale sarà l’inizio della data selezionata, alle ore 00:00.
- Saranno inclusi soltanto i file con istante di ultima modifica strettamente precedente a tale soglia.
- Il confronto delle estensioni sarà case-insensitive.
- Il catalogo predefinito della checklist comprenderà:
  - `TXT`, `LOG`, `PDF`;
  - `JPG`, `JPEG`, `PNG`, `GIF`, `BMP`, `TIF`, `TIFF`, `WEBP`, `HEIC`;
  - `DOC`, `DOCX`, `DOCM`, `DOT`, `DOTX`, `DOTM`, `RTF`;
  - `XLS`, `XLSX`, `XLSM`, `XLSB`, `XLT`, `XLTX`, `XLTM`, `CSV`;
  - `PPT`, `PPTX`, `PPTM`, `POT`, `POTX`, `POTM`, `PPS`, `PPSX`, `PPSM`;
  - `ODT`, `ODS`, `ODP`;
  - `ZIP`, `RAR`, `7Z`.
- La configurazione della checklist sarà persistente.
- L’utente sceglierà posizione e nome del file CSV; nell'interfaccia gli output non saranno chiamati “manifest”.
- L’interfaccia proporrà un nome con timestamp, senza impedire all’utente di modificarlo.
- Il manifest non sovrascriverà mai un file o un audit esistente: in caso di collisione verrà aggiunto un suffisso numerico (`_2`, `_3`, ...), comunicando nell’esito il percorso effettivo.
- Il manifest finale non sarà pubblicato se la scansione viene annullata.
- La strategia concreta per evitare la presenza di un manifest parziale dovrà garantire che il percorso finale contenga soltanto un file completato.
- Durante la scansione l'interfaccia riceverà aggiornamenti di progresso anche per i file visitati ma non corrispondenti ai filtri, con conteggio dei file esclusi, così che l'utente veda che l'operazione è attiva anche su grandi volumi di documenti.
- Al termine della scansione, prima di pubblicare il CSV, l'interfaccia riceverà stati intermedi ("Scrittura file CSV in corso...", "Scrittura log di audit in corso...").
- Il log di audit di scansione includerà una sezione "Esclusioni per formato" con conteggio e percentuale sul totale visitato per ogni estensione scartata perché non inclusa nei filtri selezionati, oltre al totale complessivo delle esclusioni.

### Contratto del manifest CSV

- Il manifest userà codifica UTF-8 con BOM.
- Il delimitatore sarà il punto e virgola.
- Il formato dovrà essere compatibile con Excel in configurazione italiana.
- Le intestazioni saranno, esattamente e in questo ordine:
  1. `Nome file`
  2. `Path cartella`
  3. `Path completo`
  4. `Formato file`
  5. `Data ultima modifica`
  6. `Delete`
- Non saranno aggiunte colonne tecniche.
- Non sarà richiesto alcun sidecar per la successiva cancellazione.
- `Data ultima modifica` userà il formato `yyyy/MM/dd`, senza componente oraria.
- `Delete` sarà inizializzato a `Yes`.
- La gestione CSV dovrà preservare correttamente valori contenenti delimitatori, virgolette, caratteri accentati e altri caratteri validi nei nomi e nei percorsi.

### Lettura e validazione per la cancellazione

- La schermata di cancellazione leggerà esclusivamente il CSV scelto dall’utente.
- `Path completo` sarà il valore autorevole per individuare il file.
- Prima di qualsiasi cancellazione verrà verificata la coerenza di:
  - `Nome file` rispetto al nome derivato da `Path completo`;
  - `Path cartella` rispetto alla cartella derivata da `Path completo`;
  - `Formato file` rispetto al formato derivato dal nome indicato da `Path completo`.
- Una riga incoerente sarà saltata e registrata come errore.
- `Yes`, senza distinzione tra maiuscole e minuscole, richiederà la cancellazione.
- `No`, senza distinzione tra maiuscole e minuscole, causerà il salto della riga.
- Un valore vuoto in `Delete` causerà il salto della riga.
- Qualsiasi altro valore in `Delete` sarà un errore.
- Prima della cancellazione sarà verificata l’esistenza del file.
- Prima della cancellazione sarà confrontata la data corrente di ultima modifica con `Data ultima modifica`.
- La cancellazione accetterà sia il formato prodotto dalla scansione `yyyy/MM/dd`, sia il formato `dd/MM/yyyy` con cui Excel italiano può risalvare la stessa data.
- Il confronto della data avverrà a livello di giorno.
- Una mancata corrispondenza della data impedirà la cancellazione e sarà riportata nel report.
- È accettato che una modifica o sostituzione avvenuta nello stesso giorno della data registrata non sia rilevabile con il contratto attuale del manifest.

### Modalità di cancellazione

- La modalità predefinita sarà lo spostamento in una cartella scelta dall'utente.
- Per la modalità cestino non sarà mostrata una schermata riepilogativa.
- Per la modalità cestino non sarà richiesta una conferma aggiuntiva.
- La cancellazione permanente sarà opzionale.
- La cancellazione permanente richiederà una conferma esplicita prima dell’avvio.
- Se il cestino non è disponibile, il file sarà saltato e l’indisponibilità sarà riportata.
- Non sarà mai effettuato un fallback automatico dalla modalità cestino alla cancellazione permanente.
- L’integrazione con il cestino sarà isolata dietro un seam di piattaforma, così da distinguere capacità disponibile, successo e fallimento senza vincolare le regole applicative a una specifica implementazione nativa.
- La destinazione sarà selezionata con un selettore di cartelle e i relativi controlli saranno attivi e visibili solo quando è selezionata la modalità di spostamento.
- Cestino e cancellazione definitiva non richiederanno né attiveranno il campo della cartella di destinazione.
- La scansione produrrà accanto al CSV un file `<nome>.meta.json` con la root selezionata, senza aggiungere colonne tecniche al CSV.
- Selezionando un CSV, la schermata Move/Delete proporrà la root letta dai metadati; per compatibilità con file precedenti userà il log di scansione come fallback. Il campo resterà sempre modificabile.
- Lo spostamento ricostruirà sotto la destinazione il percorso relativo alla root selezionata; i file esterni alla root saranno segnalati come errori e non spostati.
- Se un file con lo stesso nome esiste già nella medesima sottocartella di destinazione, verrà aggiunto un suffisso numerico (_2, _3, ...) per evitare sovrascritture.
- Lo spostamento non richiederà una schermata riepilogativa né una conferma aggiuntiva.
- Dopo ogni operazione di spostamento saranno ripristinate le date di ultima modifica delle cartelle sorgenti, delle cartelle ricreate nella destinazione e dei file spostati.
- Ogni Move produrrà, insieme a report e audit, un registro versionato `moving_<suffisso>.undo.csv`; solo i Move eseguiti da versioni che generano questo registro saranno ripristinabili.
- La modalità **Ripristina uno spostamento** resterà nella schermata Move/Delete e accetterà esclusivamente il registro `.undo.csv`, non i precedenti report `moving_*.csv`.
- Il registro conserverà percorso originale, destinazione effettiva, timestamp, dimensione, SHA-256 e snapshot delle cartelle; sarà aggiornato atomicamente dopo ciascun file per consentire annullamento e ripresa.
- Il ripristino procederà in ordine inverso, senza sovrascrittura, e rifiuterà target mancanti, modificati, non regolari o raggiunti tramite link.
- Al termine saranno ripristinate le date originali delle cartelle; saranno eliminate soltanto le cartelle create dal Move che risultano ancora vuote.
- Ogni tentativo di ripristino produrrà report e audit `undoing_<suffisso>.csv/.log`.

### Annullamento

- La richiesta di annullamento sarà cooperativa e impedirà l’avvio di nuove unità di lavoro appena il servizio la rileva.
- Una scansione annullata produrrà il CSV con i dati parziali raccolti fino all'annullamento, i metadati associati e il log di audit.
- Una cancellazione annullata non annullerà retroattivamente le operazioni già completate.
- Una cancellazione annullata produrrà il report relativo alle righe elaborate.
- Audit e stato finale dovranno rendere esplicito l’annullamento.

### Report di cancellazione

- Il report sarà salvato accanto al CSV di input.
- Per un manifest `scansione_<timestamp>.csv`, report e log useranno lo stesso timestamp e il prefisso dell'azione: `trash_<timestamp>.csv/.log` per il cestino, `deleting_<timestamp>.csv/.log` per la cancellazione permanente e `moving_<timestamp>.csv/.log` per lo spostamento in cartella.
- Conterrà le sei colonne originali, nello stesso ordine, seguite da:
  7. `Esito`
  8. `Dettaglio`
- Conterrà una riga per ogni riga di input secondo il contratto definito, riportando l’esito disponibile in relazione allo stato di elaborazione.
- `Esito` conterrà il risultato sintetico.
- `Dettaglio` conterrà la spiegazione utile per successi, salti ed errori.
- Il report manterrà le stesse caratteristiche di interoperabilità CSV richieste per il manifest: UTF-8 con BOM e delimitatore punto e virgola.

### Audit

- Ogni scansione produrrà un file `.log` in italiano accanto al CSV pertinente.
- Ogni cancellazione produrrà un file `.log` in italiano accanto al CSV pertinente.
- Il log sarà prodotto anche in caso di annullamento o errori.
- In caso di fallimento il log includerà classe, messaggio e stack trace dell’eccezione. Se non può essere scritto accanto alla destinazione richiesta, verrà tentata la radice di scansione e poi la cartella utente; l’esito mostrerà il percorso effettivo del log.
- Il log di scansione includerà:
  - stato;
  - data e ora di inizio;
  - data e ora di fine;
  - durata;
  - cartella analizzata;
  - data soglia esclusiva;
  - conteggio degli elementi visitati;
  - conteggio dei file corrispondenti;
  - percentuali per formato tra i file corrispondenti;
  - errori rilevanti, inclusi quelli di accesso.
- Il log di cancellazione includerà:
  - stato;
  - data e ora di inizio;
  - data e ora di fine;
  - durata;
  - righe controllate;
  - numero di righe con `Delete=Yes`;
  - numero di file spostati nel cestino;
  - numero di file eliminati permanentemente;
  - numero di file spostati in cartella;
  - numero di righe saltate;
  - numero di errori;
  - percentuali per formato tra le righe conteggiate come `Delete=Yes`.
- Le percentuali saranno calcolate usando come denominatore il totale dichiarato per ciascuna operazione: file corrispondenti per la scansione e righe `Delete=Yes` per la cancellazione.
- Il formato testuale preciso dei log e la rappresentazione delle percentuali quando il denominatore è zero non sono prescritti, purché i dati obbligatori e i nomi action-specific dei file siano rispettati.

## Testing Decisions

- I test verificheranno il comportamento esterno osservabile: file prodotti, contenuto dei CSV, contenuto informativo dei log, modifiche al filesystem, progressione degli esiti e rispetto dell’annullamento.
- I test non dipenderanno dalla struttura interna delle classi, dai metodi privati o dalla disposizione dei controlli JavaFX, salvo test mirati del collegamento essenziale tra UI e servizi applicativi.
- Tutti i test filesystem useranno directory e file temporanei creati appositamente.
- Nessun test dovrà leggere, spostare o eliminare dati reali dell’utente.
- Il seam principale per i test sarà il servizio applicativo pubblico di scansione o cancellazione, invocato con:
  - input espliciti;
  - filesystem temporaneo;
  - dipendenza controllabile per il cestino;
  - orologio controllabile quando influisce su timestamp, durata o nomi proposti;
  - segnale di annullamento controllabile.
- Il filesystem reale temporaneo sarà preferito a mock di basso livello per verificare ricorsione, percorsi, date, estensioni, esistenza e cancellazione.
- L’adattatore del cestino sarà sostituibile nei test con un’implementazione controllata, per evitare di usare il vero cestino dell’utente e per simulare disponibilità, indisponibilità, successo ed errore.
- Sarà verificata la scansione ricorsiva di cartelle annidate.
- Sarà verificato che link simbolici non vengano seguiti, dove il sistema di test consente di crearli.
- Sarà verificato il comportamento equivalente per junction in test Windows eseguiti su un ambiente che ne consente la creazione.
- Sarà verificata l’esclusione predefinita dei file nascosti.
- Sarà verificata l’inclusione dei file nascosti quando l’opzione è attiva, tenendo conto delle capacità della piattaforma.
- Sarà verificato che gli errori di accesso non interrompano l’intera scansione, dove i permessi dell’ambiente di test consentono di riprodurli in modo affidabile.
- Saranno verificati file modificati:
  - prima della soglia;
  - esattamente alla soglia;
  - dopo la soglia.
- Sarà verificato che soltanto il primo caso venga incluso.
- Sarà verificato il confronto case-insensitive delle estensioni.
- Sarà verificato ciascun formato del catalogo almeno attraverso test parametrizzati.
- Sarà verificata la persistenza della selezione dei formati attraverso il seam della configurazione.
- Sarà verificata la proposta di nome con timestamp usando un orologio controllabile.
- Sarà verificato che il manifest contenga il BOM UTF-8.
- Sarà verificato che il manifest usi il punto e virgola.
- Saranno verificate esattamente intestazioni, ordine e numero delle colonne.
- Sarà verificato il quoting CSV con nomi e percorsi contenenti punto e virgola, virgolette e caratteri non ASCII.
- Sarà verificato il formato `yyyy/MM/dd`.
- Sarà verificato che `Delete` sia inizializzato a `Yes`.
- Sarà verificata l’assenza di colonne tecniche e sidecar richiesti dalla cancellazione.
- Sarà verificato che l’annullamento della scansione non lasci un manifest al percorso finale.
- Sarà verificato che scansione completata, annullata e terminata con errori producano comunque il log previsto.
- Saranno verificati i conteggi e le percentuali del log di scansione su dataset temporanei noti.
- Sarà verificata la lettura di `Yes` e `No` con diverse combinazioni di maiuscole e minuscole.
- Saranno verificati i comportamenti per `Delete` vuoto e per valori non ammessi.
- Sarà verificata la coerenza tra `Path completo` e ciascuno degli altri campi derivabili.
- Sarà verificato che ogni incoerenza impedisca la cancellazione e generi un errore nel report.
- Sarà verificato il comportamento per file inesistente.
- Sarà verificato il comportamento per file con data corrispondente.
- Sarà verificato il comportamento per file con data non corrispondente.
- Sarà documentato tramite test che due versioni differenti con la stessa data di calendario non possono essere distinte usando il manifest definito.
- Sarà verificata la modalità cestino senza usare il cestino reale, tramite adattatore controllato.
- Sarà verificato che l’indisponibilità del cestino causi un salto e non una cancellazione permanente.
- Sarà verificato che un errore del cestino venga riportato senza fallback permanente.
- Sarà verificata la cancellazione permanente esclusivamente su file temporanei.
- Sarà verificato che la modalità permanente non possa iniziare senza il percorso applicativo di conferma esplicita.
- Sarà verificato che la modalità cestino non richieda conferma aggiuntiva.
- Sarà verificato l’annullamento durante la cancellazione con più righe, controllando che:
  - gli elementi già trattati mantengano il loro esito;
  - gli elementi non ancora trattati non vengano cancellati;
  - venga prodotto il report delle righe elaborate;
  - il log dichiari lo stato di annullamento.
- Saranno verificati intestazioni, ordine delle colonne e numero di righe del report.
- Saranno verificati `Esito` e `Dettaglio` per successi, salti ed errori rappresentativi.
- Saranno verificati i conteggi e le percentuali del log di cancellazione su un manifest noto.
- Saranno previsti test di integrazione specifici per Windows per percorsi UNC, junction, cestino e app-image.
- Saranno previsti test del nucleo su Linux per scansione, CSV, validazione, report, log e cancellazione permanente su dati temporanei.
- La build Windows dovrà verificare che l’app-image prodotto includa un runtime e possa avviare l’applicazione senza una Java preinstallata.
- Non è disponibile nel contesto un precedente progetto o una suite esistente da assumere come prior art; i test seguiranno quindi i contratti esterni definiti in questa specifica.

## Out of Scope

- Installazione tramite MSI, EXE installer o altri formati diversi dallo ZIP portabile contenente l’app-image.
- Distribuzione pubblica del repository sorgente.
- Versioni web, mobile o a riga di comando.
- Scansione simultanea di più cartelle radice nella stessa operazione.
- Attraversamento di link simbolici o junction.
- Analisi del contenuto interno dei file.
- Ricerca per contenuto, dimensione, proprietario o altri metadati non specificati.
- Formati di manifest diversi dal CSV definito.
- Colonne tecniche aggiuntive nel manifest.
- Sidecar tecnici diversi dal file metadati della scansione usato per proporre la root; l'utente può sempre correggere manualmente la root.
- Identificazione crittografica o tramite hash dei file.
- Rilevamento affidabile di modifiche o sostituzioni avvenute nello stesso giorno della data registrata.
- Fallback automatico dal cestino alla cancellazione permanente.
- Ripristino automatico dei file già eliminati quando l’utente annulla la cancellazione.
- Schermata riepilogativa o conferma aggiuntiva per la modalità cestino.
- Elaborazione di file non esplicitamente rappresentati nel manifest selezionato.
- Uso di dati reali dell’utente nei test automatici.
- Decisioni non fornite riguardo aggiornamenti automatici, firma del codice, telemetria, autenticazione, autorizzazioni applicative o sincronizzazione cloud.

## Further Notes

- `Path completo` è autorevole per individuare il file, ma non prevale silenziosamente sugli altri campi: una discrepanza con nome, cartella o formato rende la riga non valida.
- Il confronto della data durante la cancellazione è intenzionalmente limitato al giorno perché il manifest non contiene l’ora.
- La compatibilità con percorsi UNC, junction e cestino dipende dalle capacità della piattaforma e richiede verifica su Windows reale.
- La compatibilità Linux riguarda il nucleo applicativo; il pacchetto portabile richiesto da questa specifica è quello Windows.
- La semantica precisa del conteggio “elementi visitati” dovrà essere resa stabile e documentata prima dell’implementazione dei relativi test, senza omettere il dato dal log.
- Il comportamento per righe CSV strutturalmente illeggibili e la rappresentazione nel report dovranno preservare, per quanto tecnicamente possibile, il requisito di tracciamento di una riga per ogni riga di input senza autorizzare alcuna cancellazione.
- I report e i log di elaborazione usano i prefissi action-specific definiti sopra; in caso di collisione viene aggiunto un suffisso numerico `_2`, `_3`, ecc. alla coppia CSV/log senza sovrascrivere file esistenti.
- Il meccanismo concreto di persistenza della checklist non è prescritto; il contratto richiesto è che la selezione sopravviva alla chiusura e al successivo avvio dell’applicazione.
