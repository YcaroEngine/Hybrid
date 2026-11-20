# Hybrid Engine (1.21.11 Multi-Version)

Hybrid Multi-Version server engine for Minecraft (**1.21.11 base**) that unifies **Java and Bedrock into one** (same default port or optional extra ports), with native support for all **Mods (NeoForge/Forge/Fabric)**, **Plugins (Paper/Purpur/Spigot/Bukkit)**, **Bedrock Packs**, and all versions in general.

## How to Install (NeoForge Base Required)

1. Go to the **Actions** tab, open the latest green build, and download the **`Hybrid-Universal-server`** (`.zip` file) under **Artifacts**.
2. **Extract the `.zip` file** on your device to get the **`server.jar`** inside.
3. In your hosting control panel (or PC), select the **NeoForge 1.21.11** software.
4. Open the file manager and **delete the original `server.jar`** from NeoForge.
5. Upload the extracted **Hybrid `server.jar`** to the main server directory.
6. Start the server using **Java 21**.
7. On first startup, **Hybrid** automatically downloads and builds the 1.21.11 environment, creates the `/mods`, `/plugins`, and `/bedrock-packs` folders, generates the full `server.properties`, and enables **Java + Bedrock** crossplay on the same port!
