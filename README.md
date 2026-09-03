# Scan128 Android V2.1 — ROI

Aplicação Android para:

- abrir automaticamente a câmara traseira;
- reconhecer números com OCR;
- exigir leituras consecutivas iguais antes de confirmar;
- gerar Code 128 localmente;
- vibrar quando um código é confirmado;
- permitir correção manual;
- copiar o número;
- guardar as últimas 12 leituras.

## Importante

O projeto é nativo Android e, depois de compilado, o OCR e a geração de Code 128 funcionam localmente.

Este pacote não inclui um APK pré-compilado porque o ambiente onde o projeto foi criado não dispõe do Android SDK/Gradle nem de acesso à Internet para os descarregar.

## Gerar o APK

1. Instala e abre o Android Studio.
2. Escolhe **Open** e seleciona a pasta `Scan128-Android`.
3. Deixa o Android Studio fazer o Gradle Sync e descarregar as dependências.
4. Liga um Android por USB se quiseres testar diretamente.
5. Para gerar o APK:
   - **Build**
   - **Build App Bundle(s) / APK(s)**
   - **Build APK(s)**
6. O ficheiro será criado em:
   `app/build/outputs/apk/debug/app-debug.apk`

Para distribuição, usa:
**Build > Generate Signed App Bundle / APK > APK**

## Stack

- CameraX
- Google ML Kit Text Recognition
- ZXing Code128Writer
- Kotlin
- Android Views / ViewBinding


## V2.1
- OCR filtrado pela caixa central real
- ignora números fora da zona de leitura
- aceita apenas sequências com 8+ dígitos
- prioriza prefixo 356012
- segunda prioridade para prefixo 00
- exige 3 leituras consecutivas iguais
- evita juntar números de linhas/etiquetas diferentes
