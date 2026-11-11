# LEARNING: streaming-event-kafka

## Claim check e limpeza de órfãos (#7)

### O que foi construído
O upload deixou de passar pelo Kafka. O arquivo vai por streaming para um object store com API S3, a linha do
banco guarda a chave do objeto e o Kafka recebe só uma referência em JSON (`video.uploaded`). Um job agendado apaga
os objetos que nenhuma linha referencia.

### Conceitos
- **Claim check**: em vez de mandar o payload grande pela mensageria, guarda-se o payload em outro lugar e manda-se
  um "tíquete" (bucket, chave, tamanho). O broker fica pequeno e rápido, e o consumidor busca os bytes quando precisar.
- **Ordem das escritas**: objeto primeiro, linha depois, evento por último. Se o processo cair no meio, o pior caso é
  um objeto sem linha (limpável), nunca uma linha apontando para um objeto que não existe.
- **Transição de estado atômica**: o `UPDATE ... WHERE status = 'UPLOADING'` devolve quantas linhas mudaram. Zero
  significa que outro upload chegou antes, então esta requisição apaga o próprio objeto e responde 409. Um
  `SELECT` seguido de `UPDATE` deixaria dois uploads concorrentes passarem.
- **Sem transação aberta durante o upload**: segurar uma conexão do pool enquanto 500 MB trafegam esgota o pool.
  A transação só cobre a atualização da linha.
- **Período de graça do limpador**: o objeto existe antes de a transação confirmar, então parece órfão por um
  instante. O limpador só apaga o que é mais velho que `orphan-grace`, que precisa ser muito maior que a
  requisição mais longa.

### Decisões e trade-offs
- **Dual write aceito**: o commit e a publicação no Kafka são duas escritas. Uma queda entre elas deixa a linha em
  `PROCESSING` sem evento. O outbox transacional (#10) resolve; construir um outbox parcial aqui só duplicaria
  trabalho.
- **SeaweedFS no lugar do MinIO**: a imagem do MinIO não pode mais ser baixada sem autenticação. As credenciais vêm
  de variáveis de ambiente (`AWS_ACCESS_KEY_ID`), que o SeaweedFS só lê quando não há `s3.json` montado. Por isso
  não existe `s3.json` no repositório: o arquivo impediria sobrescrever as credenciais por ambiente.
- **Serializers explícitos**: com o binder do Spring Cloud Stream no classpath, o `KafkaTemplate` compartilhado
  usava `ByteArraySerializer` e falhava com `String`. O erro só apareceu no teste de integração contra um broker
  real; um teste unitário com `KafkaTemplate` mockado nunca o mostraria.
- **Checksums `WHEN_REQUIRED`**: a partir do AWS SDK 2.30 o cliente envia CRC e corpo `aws-chunked` por padrão, e
  vários stores compatíveis rejeitam isso.

### Como rodar e testar
```
docker compose up -d --wait
./mvnw -B verify
```
Os testes de integração sobem Postgres, Kafka e SeaweedFS com Testcontainers, então precisam de Docker.

### Armadilhas comuns em produção
- Limpador com grace curto apaga uploads em andamento.
- Esquecer de apagar o objeto quando a atualização da linha falha deixa lixo cobrado para sempre.
- Ler o arquivo inteiro com `getBytes()` põe o upload inteiro na memória do heap.
- Tópico renomeado sem atualizar a verificação do CI passa despercebido até o deploy.

### Perguntas para o Heitor
1. O `502` quando a publicação falha é o contrato certo, ou o upload deveria responder `202` e deixar o outbox
   (#10) cuidar da publicação?
2. Vale limitar o tamanho do upload abaixo de 512 MB por padrão, já que o SeaweedFS local usa volumes de 1 GB?
3. O limpador deve também apagar os objetos de vídeos removidos (soft delete), ou isso fica para uma issue própria?

### Para ir além
- Padrão claim check: https://www.enterpriseintegrationpatterns.com/patterns/messaging/StoreInLibrary.html
- AWS SDK for Java v2, mudança de checksums padrão: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/
- SeaweedFS S3 API: https://github.com/seaweedfs/seaweedfs/wiki/Amazon-S3-API

## Transcodificação com FFmpeg e partição pausada (#8)

### O que foi construído
Um consumidor de `video.uploaded` baixa o objeto bruto, roda o FFmpeg uma vez para gerar duas renditions HLS (720p e
360p), envia tudo para o object store e marca o vídeo como `READY`. O trabalho pesado roda numa thread separada,
enquanto a partição do Kafka fica pausada.

### Conceitos
- **Pausar a partição em vez de aumentar `max.poll.interval.ms`**: o broker tira o consumidor do grupo se ele não
  chamar `poll()` dentro desse intervalo, e isso dispara um rebalance. Aumentar o limite só empurra o problema para
  um job ainda mais longo. Pausar a partição e devolver a thread do listener mantém o `poll()` rodando (sem buscar
  nada daquela partição), então o consumidor continua no grupo por mais demorado que seja o job.
- **Ack fora da thread do consumidor**: `AckMode.MANUAL` com `asyncAcks` deixa a thread do worker chamar
  `acknowledge()` e a thread do consumidor fazer o commit no próximo `poll()`. O ack vem antes do
  `resumePartition`, para que o offset já esteja na fila de commit quando a partição volta a ser lida.
- **Saída com escopo de tentativa**: cada execução grava em `hls/{contentId}/{attemptId}/`. Duas tentativas para o
  mesmo vídeo (reentrega, ou um worker que perdeu a partição) nunca sobrescrevem os arquivos uma da outra. Só a
  tentativa cuja transição `TRANSCODING -> READY` vence é referenciada pelo banco.
- **Manifesto por último**: segmentos, depois playlists de variante, depois `master.m3u8`. Quem encontra o master
  encontra tudo o que ele cita.
- **Transição condicional**: `UPDATE ... WHERE status = 'PROCESSING'` devolve 0 linhas quando o evento é duplicado
  ou velho, e o job simplesmente ignora.
- **Um processo, duas renditions**: `-filter_complex split=2` decodifica a entrada uma vez; `-var_stream_map` com
  `name:` dá nome aos diretórios das variantes. O worker envia o que estiver no diretório de saída, em vez de
  prever nomes de arquivo, porque a nomenclatura muda entre versões do FFmpeg.

### Decisões e trade-offs
- **FFmpeg atrás de uma interface** (`FfmpegRunner`): os testes unitários usam um stub que escreve arquivos falsos e
  nunca precisam do binário. O teste de ponta a ponta usa o FFmpeg de verdade e o `ffprobe`.
- **`ProcessBuilder` com lista de argumentos**: nunca uma string de shell, então nome de arquivo não injeta comando.
  O stderr é drenado numa thread; sem isso o FFmpeg trava quando o buffer do pipe enche.
- **Sem retry**: um job que falha vai para `ERROR`. Retries e DLT são o #12; deduplicação e limpeza de tentativas
  abandonadas são o #11. Um worker que morre no meio deixa o vídeo em `TRANSCODING`.
- **Dentro da aplicação**: o worker roda no mesmo processo da API por enquanto. Separar em outro processo fica para
  depois.
- **Teste que falha em vez de pular**: o teste do FFmpeg pula localmente sem o binário, mas com `REQUIRE_FFMPEG=true`
  (CI) falha, para que um binário ausente não passe em silêncio.

### Como rodar e testar
```
docker compose up -d --wait
./mvnw -B verify
```
Os testes `*IT` rodam no `verify` (failsafe) e sobem Postgres, Kafka e SeaweedFS com Testcontainers. O de ponta a
ponta precisa de `ffmpeg` e `ffprobe` no `PATH`.

### Armadilhas comuns em produção
- Qualquer trabalho bloqueante que sobre na thread do listener traz o rebalance de volta.
- Esquecer de drenar o stderr do processo trava o FFmpeg.
- Matar só o processo pai deixa filhos vivos (por exemplo um script wrapper) segurando o pipe aberto.
- Disco: o arquivo bruto (até 512 MB) é copiado para um diretório temporário; o tamanho do executor limita quantos
  jobs rodam ao mesmo tempo.

### Perguntas para o Heitor
1. As duas renditions (720p e 360p) e os bitrates fixos servem, ou vale uma escada derivada da resolução da entrada?
2. Um vídeo preso em `TRANSCODING` depois de uma queda do worker deve ser recuperado por um job de varredura (#11) ou
   pelo retry (#12)?
3. O worker deve virar um processo separado antes do #9, ou só quando a CPU da API for um problema medido?

### Para ir além
- Spring Kafka, pausar e retomar um container: https://docs.spring.io/spring-kafka/reference/kafka/pause-resume.html
- Apple HLS authoring spec: https://developer.apple.com/documentation/http-live-streaming
- FFmpeg, formato `hls` e `var_stream_map`: https://ffmpeg.org/ffmpeg-formats.html#hls-2

## Servir HLS e verificar a reprodução (#9)

### O que foi construído
A aplicação passou a servir o HLS que o worker grava no object store: `GET /api/videos/{id}/hls/{path}` abre o objeto
em streaming e devolve playlist ou segmento. Uma página `player.html` usa o hls.js, e um teste com Playwright abre o
Google Chrome de verdade e confirma que o vídeo toca.

### Conceitos
- **Playlists com URI relativa funcionam atrás de um proxy**: o FFmpeg escreve `720p/index.m3u8` e `seg_000.ts`, sem
  host. O navegador resolve esses nomes contra a URL do próprio `master.m3u8`, então basta servir a mesma árvore de
  caminhos. Nenhuma playlist é reescrita.
- **Só a tentativa que o banco aponta**: o prefixo vem de `hlsManifestKey` (sem `master.m3u8`). Tentativas
  antigas ou parciais do mesmo `contentId` continuam no bucket, mas nenhum caminho chega até elas.
- **Lista de permissão em vez de lista de bloqueio**: o caminho precisa casar com segmentos de `[A-Za-z0-9_-]` e um
  arquivo `.m3u8` ou `.ts`. Como o único ponto permitido é o da extensão, `..`, `%2e%2e` e barra invertida nunca
  casam, e a validação roda antes de qualquer chamada ao store. O firewall do Spring Security é a segunda barreira,
  não a única.
- **Cache por tipo de objeto**: segmentos e playlists de variante vivem sob um prefixo por tentativa que nunca
  muda, então `immutable` com um ano. O master tem URL estável por vídeo, então `no-cache`.
- **Streaming sem buffer**: `InputStreamResource` com `Content-Length` explícito copia do S3 para o cliente em blocos
  e o conversor fecha o stream, inclusive quando o cliente desconecta no meio do segmento.
- **O Chromium do Playwright não toca H.264/AAC**: o build incluído não tem os codecs proprietários, e o hls.js
  precisa do MSE com `avc1`/`mp4a`. Por isso o teste usa o canal `chrome` (Google Chrome instalado), e falharia por
  um motivo alheio a este código com o Chromium padrão.

### Decisões e trade-offs
- **Proxy em vez de URL pré-assinada**: o player fica na mesma origem (sem CORS no object store) e o CI não depende de
  o navegador alcançar o host do store. O custo é a banda da aplicação; redirect pré-assinado ou CDN ficam para depois.
- **hls.js por WebJar**, não por CDN: o CI não depende de um host de terceiros. A versão do pom e a do `player.html`
  andam juntas, e `HlsPlayerPageTest` falha se uma ficar para trás.
- **Estado em `window.player`**: o teste lê níveis, erro fatal e `currentTime` em vez de raspar o DOM.
- **Teste que falha em vez de pular**: sem Chrome o teste pula localmente, mas com `REQUIRE_BROWSER=true` (CI) falha.
- **Sem range requests, sem autenticação**: o hls.js busca segmentos inteiros, e a segurança continua `permitAll`.

### Como rodar e testar
```
docker compose up -d --wait
./mvnw spring-boot:run
# http://localhost:8080/player.html?video={id}
./mvnw -B verify
```
O `PlaybackIT` precisa de Docker, `ffmpeg`/`ffprobe` e Google Chrome. No CI, `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`
evita baixar os navegadores do Playwright.

### Armadilhas comuns em produção
- Esquecer de fechar o `ResponseInputStream` do S3 esgota o pool de conexões HTTP quando clientes desconectam.
- Validar o caminho depois de decodificar, ou só no firewall, abre brecha para travessia de diretório.
- `Cache-Control: immutable` em uma URL que muda de conteúdo (como o master, se ele fosse reescrito) deixa
  o player preso a uma versão velha.
- Testar reprodução com o Chromium do Playwright dá falso negativo por falta de codec.

### Perguntas para o Heitor
1. O proxy pela aplicação basta para o estudo, ou vale mover os segmentos para URL pré-assinada/CDN antes de medir?
2. Playback deve exigir autenticação (URL assinada com expiração) já neste marco, ou só quando houver usuários?
3. Vale suportar `Range` nos segmentos para clientes que o enviam (Safari nativo, por exemplo)?

### Para ir além
- hls.js: https://github.com/video-dev/hls.js/
- Playwright para Java, canais de navegador: https://playwright.dev/java/docs/browsers#google-chrome--microsoft-edge
- Apple HLS authoring spec: https://developer.apple.com/documentation/http-live-streaming

## Transactional outbox (#10)

### O que foi construído
O upload deixou de falar com o Kafka. Na mesma transação que move o vídeo para `PROCESSING`, ele insere uma linha em
`outbox_events` com o JSON de `video.uploaded`. Um `OutboxRelay` agendado lê as linhas não enviadas, publica no Kafka e
só então marca `sent_at`. Um teste de integração derruba o relay entre a publicação e a marcação e confirma que o
evento não se perde.

### Conceitos
- **Dual write**: gravar no banco e publicar no Kafka são duas escritas sem transação em comum. Qualquer ordem deixa
  uma janela em que uma foi feita e a outra não. O outbox troca as duas escritas por uma só (duas linhas, um commit) e
  empurra a publicação para um processo que pode tentar de novo.
- **Publicar antes de marcar como enviado**: o `sent_at` só fica visível no commit da passada, depois dos acks do
  broker. Se o processo morre no meio, o commit não acontece, a linha continua sem `sent_at` e a próxima passada
  publica de novo. Marcar antes de publicar perderia o evento.
- **At-least-once, não exactly-once**: a janela entre o ack e o commit gera duplicata, nunca perda. O consumidor já
  ignora a duplicata (`markTranscoding ... WHERE status = 'PROCESSING'`); efeito único de verdade é o #11.
- **`FOR UPDATE SKIP LOCKED`**: duas instâncias (ou duas passadas sobrepostas) não publicam a mesma linha ao mesmo
  tempo; a segunda pula o que a primeira travou em vez de esperar.
- **Parar na primeira falha**: se a linha 2 falha e a 3 é publicada, a ordem por chave no tópico não segue mais a ordem
  de commit. O relay para a passada na primeira falha e tenta de novo na próxima.
- **`acks=all` e idempotência**: a garantia do relay depende de o ack significar "replicado". São os padrões do
  Kafka 3.x, mas ficam declarados em `application.properties`.

### Decisões e trade-offs
- **A falha de envio faz commit, não rollback**: `attempts` e `last_error` precisam ser gravados, e uma segunda
  transação (`REQUIRES_NEW`) esperaria para sempre pelo lock que a primeira ainda segura. Então a falha é tratada
  dentro da passada e o commit leva junto as linhas já enviadas. Só uma exceção inesperada (o "crash") faz rollback.
- **A transação fica aberta durante os envios**: com broker lento, uma passada segura locks e uma conexão por até
  `batch-size x send-timeout`. O lote pequeno e o timeout limitam isso. A alternativa (reservar linhas com uma coluna
  de lease, publicar fora da transação e marcar depois) tem mais peças e ficou de fora.
- **Mudança de API**: sem Kafka o upload agora devolve `202`, não `502`; o evento sai quando o broker voltar.
- **Linhas enviadas não são apagadas** (fora do escopo); o índice `(sent_at, id)` mantém a consulta barata.
- **Um evento venenoso trava o relay** para preservar a ordem. Tratamento disso é o #12.

### Como rodar e testar
```
docker compose up -d --wait
./mvnw -B verify
```
`OutboxRelayCrashIT` precisa de Docker e roda o relay à mão (`runOnce()`), com o agendador desligado.

### Armadilhas comuns em produção
- Marcar como enviado antes do ack, ou em transação separada que commita antes da publicação, perde eventos.
- Sem `SKIP LOCKED`, duas instâncias publicam tudo em dobro; com `NOWAIT`, uma delas falha a cada passada.
- Apagar linhas enviadas sem política deixa a tabela crescer sem limite; o índice parcial ajuda, mas não basta.
- Esquecer que o consumidor precisa tolerar duplicatas: o outbox garante entrega, não efeito único.

### Perguntas para o Heitor
1. A parada na primeira falha basta para um tópico só, ou vale já separar a ordem por chave?
2. Quando entrarem outros eventos, o relay continua único ou vira um por tópico?
3. Qual retenção para as linhas enviadas (dias, ou arquivar)?

### Para ir além
- Chris Richardson, Transactional outbox: https://microservices.io/patterns/data/transactional-outbox.html
- PostgreSQL, bloqueio de linhas e `SKIP LOCKED`: https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE
- Kafka, garantias do produtor idempotente: https://kafka.apache.org/documentation/#producerconfigs_enable.idempotence
