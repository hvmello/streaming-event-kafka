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
