    private static void setupModLoaderCore() {
        Path librariesDir = Path.of("libraries");
        try {
            if (!Files.exists(librariesDir)) {
                Files.createDirectories(librariesDir);
            }

            // Garante eula=true para o servidor oficial gerar o mundo e salvar playerdata
            Path eulaPath = Path.of("eula.txt");
            if (!Files.exists(eulaPath) || !Files.readString(eulaPath).contains("eula=true")) {
                Files.writeString(eulaPath, "# Auto-accepted by Hybrid Engine\neula=true\n");
                System.out.println("[Hybrid-Core] Accepted eula.txt automatically.");
            }

            // Procura o arquivo unix_args.txt gerado pelo instalador oficial do NeoForge
            Optional<Path> unixArgs;
            try (Stream<Path> walk = Files.walk(librariesDir)) {
                unixArgs = walk
                    .filter(p -> p.getFileName().toString().equals("unix_args.txt") && p.toString().contains("neoforge"))
                    .findFirst();
            }

            if (unixArgs.isEmpty()) {
                System.out.println("[Hybrid-Core] Fetching latest NeoForge 1.21.11 from official Maven...");
                String metadataUrl = "https://neoforged.net";
                String xml = new String(URI.create(metadataUrl).toURL().openStream().readAllBytes(), StandardCharsets.UTF_8);

                String latestVersion = "21.11.38-beta";
                Matcher m = Pattern.compile("<version>(21\\.11\\.[^<]+)</version>").matcher(xml);
                while (m.find()) {
                    latestVersion = m.group(1);
                }

                String installerUrl = "https://neoforged.net" + latestVersion + "/neoforge-" + latestVersion + "-installer.jar";
                Path installerPath = Path.of("modloader-installer.jar");

                System.out.println("[Hybrid-Core] Downloading NeoForge " + latestVersion + "...");
                try (InputStream in = URI.create(installerUrl).toURL().openStream()) {
                    Files.copy(in, installerPath, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[Hybrid-Core] Installing NeoForge " + latestVersion + " (Generating world engine)...");
                    ProcessBuilder pb = new ProcessBuilder("java", "-jar", installerPath.toString(), "--installServer");
                    pb.inheritIO();
                    pb.start().waitFor();
                    Files.deleteIfExists(installerPath);
                }

                // Busca novamente o unix_args.txt apos a instalacao
                try (Stream<Path> walk = Files.walk(librariesDir)) {
                    unixArgs = walk
                        .filter(p -> p.getFileName().toString().equals("unix_args.txt") && p.toString().contains("neoforge"))
                        .findFirst();
                }
            }

            if (unixArgs.isPresent()) {
                Path argsFile = unixArgs.get();
                int internalBackendPort = Main.CONFIG.getInt("server-port", 25565) + 1;
                System.out.println("[Hybrid-Core] Starting NeoForge 1.21.11 World Engine on internal port " + internalBackendPort + "...");

                // Inicia o motor oficial do NeoForge em thread virtual para gerar o mundo e salvar playerdata
                Thread.ofVirtual().start(() -> {
                    try {
                        ProcessBuilder serverPb = new ProcessBuilder(
                            "java",
                            "@" + argsFile.toString(),
                            "--nogui",
                            "--port", String.valueOf(internalBackendPort)
                        );
                        serverPb.inheritIO();
                        serverPb.start().waitFor();
                    } catch (Exception runEx) {
                        System.err.println("[Hybrid-Core] World Engine stopped: " + runEx.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            System.err.println("[Hybrid-Core] Error: " + e.getMessage());
        }
    }
