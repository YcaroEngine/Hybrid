<div align="right">
  <a href="README.md">🇦🇺 English</a> | 🇧🇷 <b>Português</b>
</div>

# Hybrid Engine (BETA)

**O software definitivo de servidor Minecraft 1.21.11: Híbrido (NeoForge + Paper), Multi-versão e com Cross-Play Nativo.**

O Hybrid é um software de servidor em fase beta projetado para unificar todo o ecossistema do Minecraft em um único arquivo. Rode **mods NeoForge** junto com **plugins Paper** na versão **1.21.11** e permita que clientes de **Java Edition** e **Bedrock Edition (26.60, 26.3, 26.2 e outras versões)** entrem no mesmo servidor usando a mesma porta, sem precisar configurar nada manualmente.

## Principais Recursos

* **Totalmente Híbrido 1.21.11 (NeoForge + Paper):** Suporte integrado para rodar **plugins Paper/Spigot 1.21.11** (`plugins/`) junto com **mods NeoForge 1.21.11** (`mods/`) no mesmo mundo.
* **Cross-Play Nativo (Mesma Porta e NetherNet):** Jogadores de celular, console e Windows (Bedrock Edition) entram no servidor usando o mesmo IP e a mesma porta do Java, com login direto sem prefixo `.` no nick e suporte a NetherNet.
* **Suporte Multi-Versão:** Permite a entrada de clientes **Bedrock 26.60, 26.3, 26.2** e outras versões, além de diversas versões do **Minecraft Java**.
* **Pacotes Bedrock Simplificados (`bedrock-packs/`):** Jogue seus arquivos `.mcpack`, `.mcaddon`, `.zip` ou pastas puras direto dentro de `bedrock-packs/` para sincronização automática.

## Como Instalar (Hospedagem ou PC)

1. Vá na aba **[Releases](../../releases)** e baixe o arquivo **`server.jar`**.
2. No painel da sua hospedagem (ou na pasta do seu PC), escolha **NeoForge 1.21.11**.
3. Apague o `server.jar` original que estiver na pasta do servidor.
4. Coloque o nosso **`server.jar`** no lugar dele.
5. Inicie o servidor e aguarde a configuração automática finalizar.
