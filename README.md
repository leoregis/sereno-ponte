# Sereno ponte

App Android pequeno (Kotlin, sem Flutter) que lê a pulseira, o relógio e a
balança pelo **Health Connect** e manda para a conta do Sereno a cada hora.
Só leitura: a ponte nunca grava no Health Connect e não guarda os dados no
celular.

## Como funciona

- **Ligar:** no Sereno, *Configurações → Integrações → Pulseira e balança →
  Gerar código*. O código vale 15 minutos e uma vez só; a ponte troca o
  código por um token do aparelho (o servidor guarda só o hash) e pode ser
  desligada no Sereno a qualquer momento.
- **Ler:** a cada hora (WorkManager, com internet) e ao tocar em *Enviar
  agora*. Na primeira vez, os últimos 30 dias; depois, só as alterações
  desde a última leitura (o *changes token* do Health Connect).
- **Enviar:** registros e agregados por hora vão para a função `saude-ponte`
  do Sereno, que grava com os mesmos ids do app Android do Sereno. O token de
  cada tipo só avança depois que o servidor aceitou tudo daquele tipo.

## Compilar

```
./gradlew testProdDebugUnitTest
./gradlew assembleProdRelease assembleDevRelease
```

Sem a chave da ponte, o release sai assinado com a chave de depuração (só
para testar). No GitHub Actions, a chave vem dos segredos
`PONTE_KEYSTORE_B64` e `PONTE_KEYSTORE_SENHA`; uma tag `v<versão>` gera a
release com `sereno-ponte.apk`, `sereno-ponte-dev.apk` e `versao.json`.

Dois sabores: `prod` (projeto e site de produção) e `dev`
(`br.com.orrico.ponte.dev`, projeto e site de desenvolvimento). O endereço
do servidor e o do site estão em `app/build.gradle.kts` — não são segredos.
